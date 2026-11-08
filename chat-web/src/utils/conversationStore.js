// 会话列表 —— 消息页（列表）和底部导航（角标）共用的【一份】状态。
//
// ── 为什么不各读各的 ────────────────────────────────────────────
// 以前这两个组件各自去读本地库、各自重算未读，于是"什么时候该刷新"这件事
// 要在两处分别维护 —— 事实是它漏过两次：
//   · 读完消息后没人通知底部角标（红点不消失）；
//   · 一轮补拉结束后没人更新角标。
// 现在"谁在什么时候该刷新"只有这里一处，两个组件只消费它的响应式数据，
// 不可能再出现"改了 A 忘了 B"。
//
// ── 为什么列表是"合并"而不是"整份替换" ──────────────────────────
// 列表有两个数据源：本地库的 index（秒开、离线可用）和服务端 /conversations（权威）。
// 任何一个【暂时】变小都不该让界面缩水（服务端有 200 条上限、本地索引可能正在重建）。
// 合并的语义也更准确：列表 = 「服务端知道的」∪「这台设备本地有的」，
// 同一个会话以服务端那份为准（未读数、回执水位、最后一条都是权威值）。
import { computed, ref } from 'vue'
import { groupUnreadTotal } from './groupChatStore'
import { api } from '../api'
import { onChatEvent } from './chatSync'
import { listLocalConversations, onLocalRead, raiseLocalReceipt } from './messageStore'

/** 合并后的会话行（形状与本地 index 摘要、服务端 ConversationVO 都一致） */
export const conversations = ref([])

/**
 * 所有会话的未读总和（底部导航角标）。
 *
 * <p>直接用摘要里的 unreadCount 加总：那是落盘时算好的（见 message-store 的 unreadOf），
 * 所以既不用再查服务端，也不会像"收到一条就 +1"那样在重推/补拉时重复计数。
 */
export const unreadTotal = computed(() =>
  conversations.value.reduce((sum, row) => sum + (Number(row?.unreadCount) || 0), 0) + groupUnreadTotal.value)

/**
 * 两份摘要合并成一份：新到的那份覆盖旧的，但「最后一条」那几个字段
 * <b>只许被"更新的那一份"覆盖</b>（按 lastMessageId 比）。
 *
 * <p>为什么必须单独护住这几个字段：本地索引只是缓存，它可能<b>落后</b>于服务端
 * （换了台机器登录、在浏览器或另一个 profile 里聊过、离线期间对方发的）。
 * 如果照"后来的赢"处理，那么任何一次本地重读（来新消息、一轮补拉结束、读掉一个会话）
 * 都会把列表里本来正确的那句预览换回一句很旧的 —— 表现就是"预览自己往回退了"，
 * 要等下一次服务端刷新才恢复。排序也依赖 lastMessageAt，会跟着一起乱。
 *
 * <p>反过来，"更新的那份"必须照单全收：本地刚收到消息时，服务端那份还没刷新，
 * 这时候只有本地那份是对的（这正是列表能即时冒泡的原因）。
 *
 * @param {object} base 已有的那一行（通常来自服务端，权威）
 * @param {object} incoming 新到的这一行（可能来自本地索引，也可能来自服务端）
 * @returns {object} 合并后的行
 */
function mergeRow(base, incoming) {
  const baseId = Number(base?.lastMessageId) || 0
  const incomingId = Number(incoming?.lastMessageId) || 0
  if (incomingId >= baseId) {
    return { ...base, ...incoming }
  }
  // incoming 更旧：只吸收它带来的其它字段（水位这类），「最后一条」整组和未读数保持不动
  const {
    lastMessageId, lastMessageAt, lastMessagePreview, lastMessageType,
    lastMessageSenderUserId, unreadCount, ...rest
  } = incoming
  return { ...base, ...rest }
}

/**
 * 合并两份会话行。同一个 peer 以 incoming 为准，但保留 local 里有、incoming 里没有的字段。
 *
 * @param {Array<object>} local 当前列表
 * @param {Array<object>} incoming 新到的行（服务端返回，或本地库刚读出来的）
 */
function mergeRows(local, incoming) {
  const byPeer = new Map()
  for (const row of local || []) {
    if (row && row.peerUserId != null) {
      byPeer.set(Number(row.peerUserId), row)
    }
  }
  for (const row of incoming || []) {
    if (!row || row.peerUserId == null) {
      continue
    }
    const peerId = Number(row.peerUserId)
    byPeer.set(peerId, byPeer.has(peerId) ? mergeRow(byPeer.get(peerId), row) : row)
  }
  return [...byPeer.values()]
}

/** 合并服务端那份（权威值）进来 */
export function mergeFromServer(serverRows) {
  conversations.value = mergeRows(conversations.value, serverRows)
}

/**
 * 把某个会话的未读清成 0 —— "我正在看这个会话"时调。
 *
 * <p>为什么需要一个单独的入口，而不是等下一次同步：<b>读一下就把红点清掉</b>是聊天软件
 * 最基本的反馈，而现在的刷新时机只有"连上/重连"和"本地库变化"：
 * <ul>
 *   <li>{@code markLocalRead} 在"本地读游标已经到顶"时是<b>空操作</b>（不会广播），
 *       于是红点没人清 —— 实测就是这样：本地游标 127、服务端的未读早已是 0，
 *       但界面上那个 25 是登录时拉到的旧值，一直挂着；</li>
 *   <li>为了这一个数字去再拉一次 /conversations 不值得（读一条消息打一个请求）。</li>
 * </ul>
 * 所以这里直接在内存里清掉，下一次重连再和服务端对账（那时服务端也已经是 0）。
 *
 * @param {number} peerUserId 会话里的对方
 */
export function clearUnreadForPeer(peerUserId) {
  const id = Number(peerUserId)
  if (!Number.isFinite(id)) {
    return
  }
  const row = conversations.value.find((item) => Number(item?.peerUserId) === id)
  // 本来就没有未读 → 不动（不重新赋值，避免无谓的渲染）
  if (!row || !Number(row.unreadCount)) {
    return
  }
  conversations.value = conversations.value.map((item) =>
    (Number(item?.peerUserId) === id ? { ...item, unreadCount: 0 } : item))
}

/**
 * 清空。
 *
 * <p>两个时机必须清：换账号（否则会把上一个人的会话合进来 —— 那是串号级错误）、
 * 以及最后一个订阅者卸载时（登出）。不清的话模块级状态会跨会话残留。
 */
export function clearConversations() {
  conversations.value = []
}

/** 合并窗口：一页补拉可能连着来几十条消息，不值得读几十次盘 */
const REFRESH_MERGE_MS = 100
let refreshTimer = null

/**
 * 从本地库读一次并合并。
 *
 * <p>只读 {@code index.json} 一个小文件，**不碰任何消息正文**；
 * 本地库还没就绪（身份未定，fail-closed）时读出来是空数组，合并后等于没动，不会误删。
 */
export async function refreshConversations() {
  const local = await listLocalConversations()
  conversations.value = mergeRows(conversations.value, local)
}

/** 把刷新合并成一次（事件可能连着来几十个） */
function scheduleRefresh() {
  if (refreshTimer) {
    return
  }
  refreshTimer = setTimeout(() => {
    refreshTimer = null
    refreshConversations()
  }, REFRESH_MERGE_MS)
}

/** 上次从服务端同步成功的时间（毫秒）：首连时"首进那次"和"status:open 那次"几乎同时到，用它去重 */
let lastServerSyncAt = 0
/** 两次服务端同步的最小间隔 —— 小于它说明是同一件事触发的重复请求，直接跳过 */
const SERVER_SYNC_MIN_MS = 2000

/**
 * 从服务端拉一次会话列表（权威值），合并进共享那份，并顺手把回执水位写进本地库。
 *
 * <p>什么时候调：<b>连上 / 重连</b>、以及消息页首次开张时（见 {@link startConversationStore}）。
 * 平时（切页、收到消息）不调 —— 那些走本地那份（见文件头）。
 *
 * <p>拉 200 条是"尽量完整"：服务端默认 50、上限 200，个人 IM 一次拉满就不用分页了。
 * 失败就静默退回本地那份：列表照常能用（旧一点），下次重连还会再试。
 */
export async function syncConversationsFromServer() {
  const now = Date.now()
  if (now - lastServerSyncAt < SERVER_SYNC_MIN_MS) {
    return
  }
  lastServerSyncAt = now

  try {
    const list = (await api.messageConversations(200)) || []
    // 忽略缺少对方身份的无效单聊摘要。
    const peers = list.filter((row) => row && row.peerUserId != null)
    mergeFromServer(peers)
    // 回执水位：送达/已读是一次性 WS 推送，我离线期间对方读了的那些永远推不到我这来，
    // 这两个字段就是那份真相 —— 写进本地库，打开会话时勾才对（不用为此再拉消息）
    for (const row of peers) {
      if (row.deliveredUpTo) {
        raiseLocalReceipt(row.conversationId, row.deliveredUpTo, 'delivered')
      }
      if (row.readUpTo) {
        raiseLocalReceipt(row.conversationId, row.readUpTo, 'read')
      }
    }
  } catch (e) {
    console.warn('[conversations] 拉取会话列表失败，先用本地那份', e)
  }
}

let startedCount = 0
let unsubscribeChat = null
let unsubscribeRead = null

/**
 * 开始维护列表（引用计数）。
 *
 * <p>为什么引用计数：消息页和主布局都会"要这份数据"，各自卸载时不能把对方要用的订阅也拆了。
 * 最后一个使用者卸载（= 登出，主布局随之销毁）时才真正停掉并清空。
 */
export function startConversationStore() {
  startedCount += 1
  if (startedCount > 1) {
    return
  }

  /*
    订阅四类事件，统一在【这里】决定"什么时候需要刷新"：
      · message   —— 来了新消息：预览/时间/排序/未读都变了（只重读本地，0 请求）
      · synced    —— 一轮补拉/对齐结束：补拉下来的消息同样会改未读（只重读本地）
      · status:open —— 连上/重连：这是"从服务端对账一次"的触发点
      · localRead —— 我把某个会话读掉了：未读要变 0（只重读本地）
  */
  unsubscribeChat = onChatEvent((event) => {
    if (event.kind === 'message' || event.kind === 'synced') {
      scheduleRefresh()
    } else if (event.kind === 'status' && event.state === 'open') {
      scheduleRefresh()
      syncConversationsFromServer()
    }
  })
  unsubscribeRead = onLocalRead(() => scheduleRefresh())

  refreshConversations()
  // 首次开张也拉一次服务端：这样"启动就落在这里 / 纯浏览器没有 WS"这两种情况，
  // 不依赖 status:open 事件也能拿到权威列表（status:open 那次会被 2 秒去重挡掉，不会重复）
  syncConversationsFromServer()
}

/** 停止维护（引用计数归零时才真的停）。停的时候清空，避免跨账号残留 */
export function stopConversationStore() {
  startedCount = Math.max(0, startedCount - 1)
  if (startedCount > 0) {
    return
  }
  if (unsubscribeChat) {
    unsubscribeChat()
    unsubscribeChat = null
  }
  if (unsubscribeRead) {
    unsubscribeRead()
    unsubscribeRead = null
  }
  if (refreshTimer) {
    clearTimeout(refreshTimer)
    refreshTimer = null
  }
  clearConversations()
}
