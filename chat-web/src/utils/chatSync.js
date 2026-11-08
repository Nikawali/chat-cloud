// 全局消息同步 —— 把"收消息 + 回 ack"从页面里搬到 App 级。
// 以下 ACK/补拉说明针对单聊；群聊由 groupChatStore 在落盘后上报连续游标，使用独立群回执协议。
//
// 为什么必须放在 App 级，而不是聊天页里：
//   ① "收到消息 → 回 ack"跟"你正在看哪个页面"没关系。
//      如果 ack 只在聊天页打开时发生，那别人给你发的消息，只要你不打开那个会话，
//      发送方就永远看不到"已送达" —— 这是个比"缺触发点"更严重的洞。
//   ② 补拉需要一个不依赖页面的触发点（连上、定时、聚焦），
//      否则"一直在线、从不重连"这种情况下，消息会长期停在未送达。
//
// 页面只负责三件事，通过 {@link onChatEvent} 订阅：
//   · 显示新消息（按 messageId 去重）
//   · 根据回执更新"已送达 / 已读"的状态
//   · 用户正看着某个会话时，上报已读（这一步必须由页面做，因为它知道"用户在看谁"）
//
// 另外页面还关心"实时通道现在是什么状态"（没有通道 / 被别处顶掉了），
// 也用同一个订阅广播出去（kind='status'），免得页面再去订阅一层。
//
// ── ack 的时机（加了本地消息库之后）──────────────────────────────
// ack 的含义是"我已安全收到"。所以收消息的顺序是【先落盘 → 再派发 → 最后 ack】，
// 落盘失败就【不】ack：服务端会继续重推、下次补拉还会再来。
// 反过来做（先 ack 再落盘）的话，中间崩一次就是"服务端认为已送达、本地却什么都没有"，
// 而这条消息再也不会被补拉回来 —— 那才是真正的丢消息。
// ⚠️ 本地库只是缓存，服务端仍是唯一真相；纯浏览器里没有本地库，这些落盘调用是空操作，
//    ack 自动退回"我收到了"的旧语义。

import { api } from '../api'
import { hasChatSocket, restartChatConnection, sendChatFrame, subscribeChat } from './chatSocket'
import { appendLocalMessages, raiseLocalReceipt, useLocalAccount } from './messageStore'
import { isWindowVisible } from './env'
import { removePending } from './pendingMessages'
import { receiveGroupMessage, syncGroupMessages } from './groupChatStore'
import { setSessionUserId } from './auth'

/** 一次补拉多少条（服务端默认 20、上限 100） */
const PAGE_SIZE = 50
/** 一轮最多翻多少页：安全阀，防止某种异常情况下无限翻页 */
const MAX_PAGES = 20
/** 定时兜底的间隔：只在页面可见时才真的去拉 */
const PULL_INTERVAL_MS = 60_000

/**
 * 多久没收到服务端任何帧就认为通道"半死"。
 *
 * <p>心跳是客户端每 30 秒 ping 一次、服务端回 pong，所以 75 秒（2.5 个周期）
 * 还没收到任何一帧，基本可以确定这条连接已经不通了（而 WS 协议层不会报错）。
 */
const SOCKET_SILENCE_MS = 75_000

/**
 * 看门狗的巡检间隔。
 *
 * <p>比 {@link SOCKET_SILENCE_MS} 小得多是故意的：发现得越快，用户看不到消息的时间越短。
 * 这个定时器只做几个整数比较，成本可以忽略。
 */
const WATCHDOG_INTERVAL_MS = 30_000

/**
 * 两次"因沉默而主动重连"之间的最小间隔。
 *
 * <p>没有这个冷却，重连过程中（新连接还没 open、状态还停留在旧值）看门狗会一次接一次地
 * 拆连接 —— 那就不是自愈，而是重连风暴。
 */
const RESTART_COOLDOWN_MS = 60_000

let started = false
/** 防止并发补拉（定时器和"聚焦"可能同时触发） */
let syncing = false
let unsubscribeSocket = null
let pullTimer = null
/** 看门狗定时器（发现"半死连接"就主动重连） */
let watchdogTimer = null
/** 实时通道的最新状态（'open' / 'closed' / 'kicked' / 'no-token' / 'no-module' …），页面用它做提示 */
let socketState = null
/** 最后一次收到任何帧的时间（含 pong）：用来判断连接是不是"半死" */
let lastFrameAt = 0
/** 连接变成 open 的时刻：兜住"连上了却一个帧都没收到"（那时 lastFrameAt 还是 0） */
let socketOpenedAt = 0
/** 上一次因"沉默"主动重连的时间（冷却用） */
let lastRestartAt = 0
/** 订阅者（页面） */
const listeners = new Set()

/**
 * 启动全局同步。幂等，重复调用没副作用。
 *
 * 调用时机：App 启动时已登录、以及每次登录成功之后。
 */
export function startChatSync() {
  if (started) {
    return
  }
  started = true

  // ① 订阅实时帧（内部会确保 WebSocket 连接已经建立）
  unsubscribeSocket = subscribeChat(handleFrame)

  // ② 定时兜底
  scheduleNextPull()

  // ③ 看门狗：WS"半死"（连接看着还在、但一帧都收不到）时主动重连。
  //    为什么必须有它：WS 协议层不会把这种故障报成 close，主进程的重连逻辑根本不会启动，
  //    于是 ack 发不出去、对方的回执也收不到 —— 而且**永远不会自愈**（见 checkSilence）。
  watchdogTimer = setInterval(checkSilence, WATCHDOG_INTERVAL_MS)

  // ④ 从后台切回前台 / 窗口重新聚焦 → 立刻对齐一次
  //    （电脑睡眠、锁屏、切走很久回来时特别有用：那时 WS 很可能已经半死，
  //      而"补拉"不依赖连接状态，是当下最可靠的对齐手段）
  window.addEventListener('focus', onForeground)
  document.addEventListener('visibilitychange', onForeground)
}

/** 停止全局同步（登出时调） */
export function stopChatSync() {
  started = false
  syncing = false
  if (unsubscribeSocket) {
    unsubscribeSocket()
    unsubscribeSocket = null
  }
  if (pullTimer) {
    clearTimeout(pullTimer)
    pullTimer = null
  }
  if (watchdogTimer) {
    clearInterval(watchdogTimer)
    watchdogTimer = null
  }
  // 复位"沉默"相关的基准：下一次登录是全新的一条连接，不该继承上一轮的判定
  lastFrameAt = 0
  socketOpenedAt = 0
  lastRestartAt = 0
  window.removeEventListener('focus', onForeground)
  document.removeEventListener('visibilitychange', onForeground)
}

/**
 * 看门狗：连接"看着是 open、但已经很久没收到任何入站帧"时，主动重连一次。
 *
 * <p><b>它补的是哪个洞</b>：WS 有一种故障是 TCP 没断、写也不报错、就是收不到东西
 * （网络切换、WiFi 假死、睡眠唤醒之后最常见）。这种状态下：
 * <ul>
 *   <li>ws 库不会抛 close/error → 主进程的自动重连<b>不会启动</b>；</li>
 *   <li>客户端发的 ack 会"写成功"进黑洞 → 不走 HTTP 兜底 → 对方永远看不到"已送达"；</li>
 *   <li>对方的 delivered/read 回执推过来收不到 → 我的勾永远停在旧状态；</li>
 *   <li>而 /conversations 只在 status:open 时才拉 → 也就永远没有对账的机会。</li>
 * </ul>
 * 重连一次，上面四件事全部自愈（连上后会自动补拉 + 对账，见 handleFrame 的 __status 分支）。
 *
 * <p><b>判据是"多久没收到入站帧"</b>：心跳是客户端每 30 秒 ping、服务端回 pong，
 * 所以健康的空闲连接每 30 秒都会刷新一次 {@code lastFrameAt}，不会误判。
 * {@code socketOpenedAt} 是给"连上了却一个帧都没收到"（连 welcome 都没有）兜底的。
 */
function checkSilence() {
  // 1) 只有"连接自称 open"才有意义：正在重连/已断开时，主进程自己在那里退避重连，
  //    这时候再拆一次只会打架。没有主进程（纯浏览器）也没有这条通道可管。
  if (!started || !hasChatSocket || socketState !== 'open') {
    return
  }
  // 2) 最近还收到过帧 → 健康（含 pong），什么都不做
  const lastSeen = lastFrameAt || socketOpenedAt
  if (!lastSeen || Date.now() - lastSeen <= SOCKET_SILENCE_MS) {
    return
  }
  // 3) 冷却：上一次因沉默重连还不到 60 秒，先等它连完
  if (Date.now() - lastRestartAt < RESTART_COOLDOWN_MS) {
    return
  }

  lastRestartAt = Date.now()
  /*
    ⚠️ 这里【故意不】把 lastFrameAt 改成"现在"。
    它同时被 shouldPullOnTimer() 用来判断"要不要继续补拉"：如果把它改成现在，
    重连还没成功的那段时间就会被当成"连接健康"，窗口在后台时补拉会停掉 ——
    而那时候恰恰是最需要补拉的时候。防重复重连交给下面的冷却时间就够了。
  */
  console.warn('[chat-sync] 实时通道已静默 %d 秒（连接看着还在），主动重连一次',
    Math.round(SOCKET_SILENCE_MS / 1000))
  restartChatConnection()
}

/**
 * 订阅聊天事件。
 *
 * @param {(event: {kind: string, message?: object, conversationId?: number, upToMessageId?: number, state?: string, reason?: string}) => void} listener
 *        kind: 'message'（新消息）| 'delivered'（已送达回执）| 'read'（已读回执）
 *              | 'status'（实时通道状态）| 'synced'（一轮补拉结束，见 {@link syncNow}）
 * @returns {() => void} 取消订阅
 */
export function onChatEvent(listener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * 当前的实时通道状态。
 *
 * <p>页面初次渲染时用它拿初始值（那时订阅还没有建立，拿不到之前的 status 事件），
 * 之后的更新走 {@link onChatEvent} 的 'status' 事件。
 */
export function getSocketState() {
  return socketState
}

/**
 * 实时通道现在可不可靠（给界面显示提示用）。
 *
 * <p>把"状态不是 open"和"open 但已经很久没收到任何帧（半死）"合在一起判断 ——
 * 后者是 WebSocket 最阴的一种故障：连接看着好好的、写也不报错，就是收不到东西。
 * 界面必须把这件事说出来，否则用户只会觉得"消息不来"。
 *
 * @returns {{reliable: boolean, state: string|null, silentMs: number|null}}
 */
export function getChannelHealth() {
  const silentMs = lastFrameAt ? Date.now() - lastFrameAt : null
  return {
    reliable: !socketLooksDead(),
    state: socketState,
    silentMs,
  }
}

/**
 * 补拉一遍"发给我的、还没送达的"。
 *
 * <p><b>关键实现细节：每拉一页就 ack 一页</b>，而不是全部拉完再一起 ack。
 * 因为这个接口没有游标参数（只有 size），"下一页"是靠"上一页已经被标成送达"来实现的 ——
 * 如果攒到最后才 ack，第二次请求会拿到<b>完全一样的第一页</b>，循环就永远退不出来。
 *
 * @param {string} reason 触发原因（只用于日志，排查"到底是谁触发的补拉"）
 */
export async function syncNow(reason) {
  if (!started || syncing) {
    return
  }
  syncing = true
  syncGroupMessages().catch(e => console.warn('[group-chat] 群同步暂不可用',e))
  try {
    let total = 0
    /** 上一页的最后一条 id：用来发现"翻页没有前进" */
    let lastIdOfPreviousPage = null
    for (let page = 0; page < MAX_PAGES; page++) {
      const list = (await api.undeliveredMessages(PAGE_SIZE)) || []
      if (list.length === 0) {
        break
      }

      // 翻页游标就是"上一页有没有被确认"。所以如果这一页和上一页的最后一条一样，
      // 说明 ack 根本没落地，再翻下去只会拿到同一页 —— 立刻停手，别做无意义的重复请求。
      const lastId = list[list.length - 1].messageId
      if (page > 0 && lastId === lastIdOfPreviousPage) {
        console.warn('[chat-sync] 补拉翻页没有前进（ack 没落地），本轮停止')
        break
      }
      lastIdOfPreviousPage = lastId

      // 【先落盘，再派发，最后 ack】：ack 说的是"我已经安全存好了"。
      const persisted = await persistBySender(list)

      for (const vo of list) {
        emit({ kind: 'message', message: vo })
      }
      // 计数按"实际拉到的条数"记：ack 失败时也拉到了、也派发出去了，
      // 日志里少算会让"补拉完成：0 条"这种奇怪的结果出现在真正需要排查的时候
      total += list.length

      // 没落盘的这一页不能 ack：否则服务端以为送到了，而这些消息本地一条都没留下。
      // 停手是对的 —— 不停的话下一页还是同一页（补拉的游标就是"上一页确认了没有"）。
      if (!persisted) {
        console.warn('[chat-sync] 本地落盘失败，本轮不 ack（下次补拉还会再来）')
        break
      }

      // ack 发不出去就不必再翻页了：下一页还是这一页
      if (!(await ackMessages(list.map((vo) => vo.messageId)))) {
        break
      }
      if (list.length < PAGE_SIZE) {
        break   // 不足一页 → 已经拉完了
      }
    }
    if (total > 0) {
      console.info(`[chat-sync] 补拉完成（${reason}）：${total} 条`)
    }
    // 【本轮对齐结束】的信号。
    //
    // 谁在用：消息页（Message.vue）收到它会顺手从服务端拉一次会话列表（权威的未读数
    // + 每个会话的回执水位）。这样"触发点"就复用了这里的四个（连上/60 秒/切回前台/聚焦），
    // 而请求是分开的 —— 补拉是任何页面都要做的可靠性动作（会翻页、会 ack），
    // 会话列表只有消息页可见时才需要，两者混成一个请求会让语义变浑、还互相拖累。
    emit({ kind: 'synced', reason })
  } catch (e) {
    // 补拉失败不打扰用户：下一次重连 / 定时 / 聚焦还会再来
    console.warn('[chat-sync] 补拉失败，稍后会再试', e)
  } finally {
    syncing = false
  }
}

// ==================== 内部 ====================

function emit(event) {
  for (const listener of listeners) {
    try {
      listener(event)
    } catch (e) {
      // 某个页面出错不能影响别的订阅者，也不能把同步流程搞挂
      console.error('[chat-sync] 页面处理事件出错', event, e)
    }
  }
}

/**
 * 回送达 ack：告诉服务端"这几条我收到了"。
 *
 * <p><b>优先走实时通道</b>（快，而且身份天然绑在连接上、不用再走一遍鉴权），
 * 拿不到通道或者发送失败时<b>退回 HTTP</b>。
 * 为什么必须有这条退路：WebSocket 只建在 Electron 主进程里（浏览器不能自定义握手请求头），
 * 纯浏览器跑时一次 ack 都发不出去 —— 发送方就永远看不到"已送达"。
 *
 * <p>两条路服务端走的是同一个幂等逻辑，所以"都发一遍"也无害。
 *
 * @param {number[]} messageIds
 * @returns {Promise<boolean>} ack 是否成功发出（补拉靠它决定还要不要继续翻页）
 */
async function ackMessages(messageIds) {
  if (!messageIds || messageIds.length === 0) {
    return true
  }
  if (hasChatSocket) {
    const result = await sendChatFrame({ type: 'ack', messageIds }).catch(() => null)
    if (result && result.ok) {
      return true
    }
    // 这里不加 else 的日志：连接刚好在重连时这是常态，退回 HTTP 就够了
  }
  try {
    await api.markMessagesDelivered(messageIds)
    return true
  } catch (e) {
    // 失败不抛：服务端收不到 ack 会重推，客户端下次补拉时还会再 ack 一次
    console.warn('[chat-sync] 回送达 ack 失败（服务端会重推，下次补拉也会再来）', e)
    return false
  }
}

function handleFrame(frame) {
  /*
    记下"最后一次收到帧的时间"，用来识别半死连接（见 socketLooksDead）。
    放在最前面、对所有帧都生效 —— 包括被下面 default 分支忽略掉的 pong：
    心跳回包恰恰是判断"这条连接还活着"的唯一证据，正是我们要的信息。
  */
  lastFrameAt = Date.now()

  switch (frame.type) {
    case 'group-message':
      receiveGroupMessage(frame.data).then(result => {
        if (result?.inserted && !result.mine) emit({kind:'group-message',message:frame.data})
      }).catch(e => console.error('[group-chat] 接收消息落盘失败，等待服务端重推',e))
      break
    case '__accepted': {
      const { request, data, userId } = frame
      if (userId == null) break
      const message = { ...data, clientMessageId: request.clientMessageId,
        senderUserId: userId, content: request.content, messageType: request.messageType }
      appendLocalMessages(request.toUserId, [message])
      removePending(request.clientMessageId)
      emit({ kind: 'accepted', peerUserId: request.toUserId, message })
      break
    }
    case 'message':
      // 收到推送 → 落盘 → 派发 → ack，见 onIncomingMessage 的注释。
      // ack 的含义是"客户端收到了"，和"用户看没看"无关，所以必须在这里做 —— 不能等页面。
      onIncomingMessage(frame.data)
      break
    case 'delivered':
      // 顺手把本地的"已送达"水位推上去（不 await：这只是一份缓存，
      // 失败了下次 history 里的 deliveredAt 会对齐回来）
      raiseLocalReceipt(frame.conversationId, frame.upToMessageId, 'delivered')
      emit({
        kind: 'delivered',
        conversationId: frame.conversationId,
        upToMessageId: frame.upToMessageId,
      })
      break
    case 'read':
      raiseLocalReceipt(frame.conversationId, frame.upToMessageId, 'read')
      emit({
        kind: 'read',
        conversationId: frame.conversationId,
        upToMessageId: frame.upToMessageId,
      })
      break
    case '__status':
      // 通道状态变化也广播出去（页面据此提示"没有实时通道""被别处顶掉了"）
      socketState = frame.state
      emit({ kind: 'status', state: frame.state, reason: frame.reason, retryIn: frame.retryIn })

      if (frame.state === 'open') {
        // 记下"这条连接是什么时候 open 的"：看门狗用它兜住"连上了却一个帧都没收到"的情况
        // （那时 lastFrameAt 还是 0，只靠它会误判成"健康"）。
        socketOpenedAt = Date.now()

        // 连上 / 重连成功 → 立刻补拉。
        // 这是"不丢消息"的主路径：断线期间的消息、服务端超过 3 分钟放弃重推的消息，
        // 全靠这一次补拉捞回来。
        //
        // 注意"被顶掉"（kicked）不会走到这里 —— 那时连接并没有建立，
        // 也不需要抢着补拉：另一边正连着，把消息 ack 走反而会抢了它的活。
        syncNow('重连')
      }
      break
    case 'welcome':
      setSessionUserId(frame.userId)
      // 服务端在连接刚建立时就把"这条连接是谁的"发过来了（userId）。
      // 本地消息库用它确定归属：换账号时它要清掉上一个人的缓存，否则会串号。
      // 这条帧是【服务端确认过的身份】，比页面自己调 /user/me 更早、也更可信。
      useLocalAccount(frame.userId)
      break
    default:
      // pong / error 不需要在这里处理
      break
  }
}

/**
 * 一条推送来的新消息：落盘 → 派发 → ack（补拉那条路在 syncNow 里，顺序是同一套）。
 */
async function onIncomingMessage(data) {
  if (!data || data.messageId == null) {
    return
  }
  // 推给我的消息，"发送者"就是对方，所以可以直接按对方 userId 落盘。
  // 认不出对方（senderUserId 为空，服务端不该出现）就没法落盘 → 这次不 ack：
  // 消息在服务端仍然是"未送达"，会重推，最坏重推到 180 秒窗口结束、然后交给补拉 ——
  // 也就是说这个"多推几次"是有界的，而"没落盘却 ack 了"会静默留下本地缺一条。
  // 和 persistBySender 里那条分支保持同一个取舍：宁可重推，也不要静默地不存。
  const persisted = data.senderUserId == null
    ? false
    : await appendLocalMessages(data.senderUserId, [data])

  emit({ kind: 'message', message: data })

  if (persisted) {
    // 重复 ack 也没关系（服务端幂等），但重推和补拉都会带来重复，
    // 反正页面那边会按 messageId 去重，这里就不额外维护一份"已确认"的记录了
    ackMessages([data.messageId])
  } else {
    console.warn('[chat-sync] 新消息未能落盘，本次不 ack（服务端会重推，下次补拉也会再来）',
      data.messageId)
  }
}

/**
 * 把一批"发给我的"消息按发送者分组落盘。
 *
 * <p>为什么不按 conversationId 分组：本地文件是按【对方 userId】分的 ——
 * 路由（/message/chat/:userId）和服务端接口都是按对方定位会话，
 * 而"还没聊过天"时根本不存在 conversationId。
 * 补拉回来的消息一定是"发给我的"，所以对方 = senderUserId，不用再问服务端。
 *
 * @returns {Promise<boolean>} 是否全部落盘成功（false → 调用方不能 ack）
 */
async function persistBySender(list) {
  const byPeer = new Map()
  for (const vo of list) {
    if (!vo || vo.messageId == null) {
      continue
    }
    if (vo.senderUserId == null) {
      // 认不出对方就没法落盘。返回 false 会让本轮不 ack：消息在服务端仍然是"未送达"，
      // 不会丢，只是会多推几次 —— 这是刻意的保守选择（宁可重推，也不要静默地不存）。
      console.warn('[chat-sync] 消息没有 senderUserId，无法落盘', vo.messageId)
      return false
    }
    if (!byPeer.has(vo.senderUserId)) {
      byPeer.set(vo.senderUserId, [])
    }
    byPeer.get(vo.senderUserId).push(vo)
  }

  for (const [peerUserId, messages] of byPeer) {
    if (!(await appendLocalMessages(peerUserId, messages))) {
      return false
    }
  }
  return true
}

/*
 * 可见性判断统一用 utils/env.js 的 isWindowVisible（聊天页以前也有一份一模一样的）。
 */

function onForeground() {
  if (started && isWindowVisible()) {
    syncNow('切回前台')
  }
}

/**
 * 判断实时通道"是不是真的在通"。
 *
 * <p>只看 {@code socketState === 'open'} 不够 —— 这是个**半死连接**很容易骗过人的地方
 * （本项目在服务端那边已经踩过：半死连接写数据是不报错的，所以服务端靠"多久没收到客户端的帧"
 * 判断死活）。客户端这边对称地也需要一条：**多久没收到服务端的任何帧**。
 * WS 协议层不会告诉你对端已经不响应用户了，只能靠"心跳回包有没有来"推断。
 *
 * <p>心跳是客户端每 30 秒发一次 ping、服务端回 pong，所以：
 * 超过 2.5 个心跳周期（75 秒）一个帧都没收到 → 认为这条连接实际不通。
 * 这时候必须继续补拉，否则窗口一不可见就彻底收不到消息了。
 */
function socketLooksDead() {
  if (socketState !== 'open') {
    return true
  }
  // 刚连上还没收到任何帧：给一个心跳周期的宽限，别一上来就判定它死了
  if (!lastFrameAt) {
    return false
  }
  return Date.now() - lastFrameAt > SOCKET_SILENCE_MS
}

/**
 * 定时补拉要不要真的跑。
 *
 * <p><b>这里原来只看"窗口可见不可见"，那是个坑</b>：
 * 不可见时不拉，理由写的是"WS 还在收推送，用户也看不到"。
 * 但这条理由只在<b>实时通道真的健康</b>时成立 —— 一旦 WS 断了/被顶掉/半死，
 * 窗口又恰好不可见，就会出现"一条拉取路径都没有"：
 * 消息躺在服务端，只有用户切回前台触发 {@link onForeground} 时才会被补拉回来。
 * 表现就是"必须聚焦才能收到消息"，而且极难联想成"实时通道掉了"。
 *
 * <p>所以改成两条判据取"或"：
 * <ul>
 *   <li>窗口可见 → 拉（用户在看，补齐了才有意义）</li>
 *   <li><b>通道不可靠</b>（断了 / 半死）→ 也要拉（这是唯一能收到消息的路了）</li>
 * </ul>
 * 通道正常时依旧跳过不可见窗口 —— 那条省电/省请求的优化保留。
 */
function shouldPullOnTimer() {
  return isWindowVisible() || socketLooksDead()
}

function scheduleNextPull() {
  if (pullTimer) {
    clearTimeout(pullTimer)
  }
  pullTimer = setTimeout(async () => {
    if (shouldPullOnTimer()) {
      await syncNow('定时')
    }
    // 用 setTimeout 链而不是 setInterval：上一次跑完再排下一次，网络慢时不会有请求堆叠
    scheduleNextPull()
  }, PULL_INTERVAL_MS)
}
