// 本地消息库（渲染进程侧的门面）—— 真正落盘的是主进程的 electron/message-store.cjs。
//
// 这一层只做三件事：
//   ① 判断"有没有本地库"（纯浏览器里没有主进程 → 全部安静降级）
//   ② 把返回值统一成固定形状，调用方不用到处写 `?.` 和 try/catch
//   ③ 把"IPC 失败"翻译成一个布尔值 —— 因为调用方要靠它决定"要不要 ack"
//
// ── 为什么"要不要 ack"要看落盘的成败（本模块存在的核心理由）────────
// ack 的含义是"这条消息我已经安全存好了"。加了本地库之后，这个"存好"就从
// "内存里收到了"变成了"磁盘上有了"。所以：
//   · 落盘成功 → 才发 ack（服务端可以放心地把它标成已送达、不再重推）；
//   · 落盘失败 → 照常显示，但【不发 ack】→ 服务端继续重推 / 下次补拉还会来。
// 反过来做（先 ack 再落盘）的话，落在中间的崩溃就是"服务端认为已送达、本地却什么都没有"，
// 而这条消息再也不会被补拉回来 —— 那是真正意义上的丢消息。
//
// ⚠️ 纯浏览器（npm run dev 直接开浏览器）里没有主进程，也就没有本地库：
//    这时这些函数一律返回"成功"，让 ack 退回加本地存储之前的语义（"我收到了"）。
//    不这么做的话，浏览器里一条消息都确认不了，整个发送方界面永远停在"已发送"。
//
// 另外：本地库只是【缓存】，服务端才是唯一真相。本地坏了/删了，靠 history 能重建。

import { restoreGroupSessions } from './groupChatStore'
const store = typeof window !== 'undefined' ? window.electronAPI?.messages : null

/** 有没有本地库可写（Electron 下才有）。界面可以据此提示"历史不会存到本地" */
export const hasMessageStore = !!store?.load

/** 空快照：没有本地库、或读失败时都返回它（形状固定，调用方不用判空） */
function emptySnapshot(peerUserId) {
  return {
    ok: false,
    peerUserId: Number(peerUserId) || null,
    conversationId: null,
    messages: [],
    // 没有本地库时"还有更早的吗"没有意义，一律 false（界面据此停止往上翻）
    hasMore: false,
    deliveredUpTo: 0,
    readUpTo: 0,
    lastMessageId: 0,
  }
}

/**
 * 告诉本地库"现在登录的是谁"（幂等，重复调用无害）。
 *
 * <p><b>为什么需要它</b>：本地消息只按【对方】分文件，本身不含账号维度。同一台机器上换账号时，
 * 上一个人的数据必须被隔离掉 —— 否则消息页会直接列出别人的会话、进会话还会把别人的消息混进来。
 * 存储层现在的做法是"换人就清空上一个人的缓存"（服务端还有，登回去能重建）。
 *
 * <p>调用点有两处、互补：WS 连上时 welcome 帧带的 userId（<b>服务端确认的身份</b>），
 * 以及页面调 /user/me 拿到的 userId。谁先到算谁。
 *
 * <p>⚠️ 身份没确定之前，存储层<b>读一律当空、写一律拒绝</b>（fail-closed）：
 * 宁可让消息晚一步落盘（调用方不 ack，服务端会重推），也不能写进别人的目录。
 *
 * @param {number|string|null} myUserId 当前登录用户 id；传 null 只清掉内存里的身份（登出时）
 * @returns {Promise<boolean>} 是否设置成功（没有本地库时返回 false，不算错误）
 */
export async function useLocalAccount(myUserId) {
  if (!hasMessageStore) {
    return false
  }
  try {
    const result = await store.useAccount(myUserId ?? null)
    if (result?.ok && myUserId != null) await restoreGroupSessions().catch(e => console.warn('[group-chat] 恢复会话失败', e))
    if (result?.applied) {
      console.info('[message-store] 本地消息库归属已更新:', result.reason)
    }
    return !!result && result.ok === true
  } catch (e) {
    console.warn('[message-store] 设置本地库归属失败', e)
    return false
  }
}

/**
 * 读某个会话的本地快照。
 *
 * @param {number} peerUserId 对方 userId
 * @param {{size?: number, beforeMessageId?: number}} [options]
 *        <p>不传就是<b>全量</b>（老行为）；聊天页会传 {@code size} 做分页：
 *        进会话只要最近一页，往上翻再按 {@code beforeMessageId} 取更早的一页 ——
 *        这样"进会话就要渲染几百条消息"就没了。
 * @returns {Promise<object>} { conversationId, messages, hasMore, deliveredUpTo, readUpTo, lastMessageId }
 *          失败时是空快照（messages 为空数组、hasMore 为 false）—— 调用方照常当"本地没有"
 */
export async function loadLocalMessages(peerUserId, options = {}) {
  if (!hasMessageStore) {
    return emptySnapshot(peerUserId)
  }
  try {
    // 只在真的传了分页参数时才发对象：保持"全量"这条老调用路径的入参形状不变
    const payload = options.size || options.beforeMessageId || options.clientMessageIds
      ? { peerUserId, size: options.size, beforeMessageId: options.beforeMessageId, clientMessageIds:options.clientMessageIds }
      : peerUserId
    const result = await store.load(payload)
    if (!result || result.ok !== true) {
      console.warn('[message-store] 读本地消息失败，按"本地没有"处理', result)
      return emptySnapshot(peerUserId)
    }
    return {
      ...emptySnapshot(peerUserId),
      ...result,
      ok: true,
      messages: Array.isArray(result.messages) ? result.messages : [],
      hasMore: result.hasMore === true,
    }
  } catch (e) {
    // 本地库出问题绝不能挡住聊天：退化成"没有本地记录"，后面的 history 照样能把消息拉回来
    console.warn('[message-store] 读本地消息异常，按"本地没有"处理', e)
    return emptySnapshot(peerUserId)
  }
}

/**
 * 追加一批【服务端确认过的】消息。
 *
 * @param {number} peerUserId 对方 userId（本地按它分文件）
 * @param {Array<object>} messages 服务端回来的消息（MessageVO 或它的一部分字段即可）
 * @returns {Promise<boolean>} 是否"已经安全存好" —— 调用方据此决定要不要 ack
 *          ⚠️ 没有本地库（浏览器）时返回 true，见文件顶部说明
 */
export async function appendLocalMessages(peerUserId, messages) {
  const list = Array.isArray(messages) ? messages.filter((m) => m && m.messageId != null) : []
  if (list.length === 0 || peerUserId == null) {
    return true   // 没什么要存的，对调用方来说等于成功了
  }
  if (!hasMessageStore) {
    return true
  }
  try {
    const result = await store.append({ peerUserId, messages: list })
    return !!result && result.ok === true
  } catch (e) {
    console.warn('[message-store] 写本地消息失败（本次不会 ack，服务端会重推或下次补拉）', e)
    return false
  }
}

/**
 * "本地回执水位被推进了"的订阅者（"已送达 / 已读"这类水位）。
 *
 * <p><b>为什么需要这个事件</b>：水位有两个来源 ——
 *   ① WS 回执帧（{@code chatSync} 收到就写本地库，并且自己会派发 'delivered'/'read' 事件）；
 *   ② {@code GET /message/conversations}（重连对账时由 conversationStore 批量写回来）。
 * ② 这条路<b>没有任何 WS 事件</b>：如果重连那一刻聊天页正开着，它内存里的水位还是旧的，
 * 勾就一直不变 —— 退出去再进来才正常，看起来像"勾不刷新"的 bug。
 * 所以把"水位真的推进了"这件事广播出去，聊天页订阅它、就地更新内存里的水位。
 *
 * <p>只会广播 {@code applied === true} 的那次（水位没变化时没必要惊动任何人）。
 *
 * @param {(conversationId: number, upToMessageId: number, level: 'delivered'|'read') => void} listener
 * @returns {() => void} 取消订阅
 */
const receiptListeners = new Set()

export function onLocalReceipt(listener) {
  receiptListeners.add(listener)
  return () => receiptListeners.delete(listener)
}

/**
 * 推进"已送达 / 已读"水位。
 *
 * <p>回执帧给的是"送到/读到某条为止"，所以本地存的也是水位（不是每条消息的时间戳）。
 * 这是个"通知类"动作：失败了也不影响界面（水位还会从 history 的 deliveredAt/readAt 补回来），
 * 所以调用方不需要 await，返回 false 也不用管。
 *
 * @param {number} conversationId 会话 id（回执帧里带的就是它）
 * @param {number} upToMessageId 送到/读到哪一条为止
 * @param {'delivered'|'read'} level
 */
export async function raiseLocalReceipt(conversationId, upToMessageId, level) {
  if (!hasMessageStore || conversationId == null || upToMessageId == null) {
    return false
  }
  try {
    const result = await store.receipt({ conversationId, upToMessageId, level })
    const applied = !!result && result.ok === true && result.applied === true
    if (applied) {
      // 真的推进了 → 广播（订阅方据此更新内存里的水位，见 onLocalReceipt 的说明）
      for (const listener of receiptListeners) {
        try {
          listener(conversationId, upToMessageId, level)
        } catch (e) {
          // 某个订阅者出错不能影响别人，更不能让水位写失败
          console.warn('[message-store] 回执事件订阅者出错', e)
        }
      }
    }
    return applied
  } catch (e) {
    console.warn('[message-store] 写本地回执水位失败（下次 history 会对齐）', e)
    return false
  }
}

/**
 * "本地读游标被推进了"的订阅者。
 *
 * <p>为什么需要一个事件：未读红点有两个地方在显示 ——
 *   ① 消息页每一行（{@code Message.vue} 的列表）；
 *   ② 底部导航上的总数角标（{@code MainLayout}）。
 * 它们的数据都来自本地库的会话摘要，而"读掉消息"这件事以前<b>没有任何通知</b>：
 * 只有聊天事件（收到消息）会触发重算。于是"在别的页面收到消息 → 进会话读掉 → 红点还挂着"，
 * 得等下一次同步（最多 60 秒）才消失；双栏布局下更明显 —— 聊天页开着时左边列表就在旁边，
 * 那一行的红点也不该等你返回才消。
 *
 * <p>{@link markLocalRead} 是"推进本地读游标"的唯一入口，所以通知就发在这里：
 * 一个点广播，两个角标各自重算，不会出现"改了一处忘了另一处"。
 */
const readListeners = new Set()

/**
 * 订阅"本地已读游标推进"。
 *
 * @param {(peerUserId: number, upToMessageId: number) => void} listener
 * @returns {() => void} 取消订阅
 */
export function onLocalRead(listener) {
  readListeners.add(listener)
  return () => readListeners.delete(listener)
}

/**
 * 记下"这个会话我读到哪条为止了"（界面在打开会话、或看着会话收到新消息时调）。
 *
 * <p>它只影响<b>消息页的未读红点</b>，所以失败了也不用管（下次进会话会再记一次）。
 * 和服务端那次 {@code POST /message/read} 是同一件事的两面：那边是权威，
 * 这边是为了让红点离线也是对的。
 *
 * @param {number} peerUserId 会话里的对方
 * @param {number} upToMessageId 我读到哪一条了
 * @returns {Promise<boolean>} 是否真的写进去了（会话还没落过本地时会是 false，那不是错误）
 */
export async function markLocalRead(peerUserId, upToMessageId) {
  if (!hasMessageStore || peerUserId == null || upToMessageId == null) {
    return false
  }
  try {
    const result = await store.read({ peerUserId, upToMessageId })
    const applied = !!result && result.ok === true && result.applied === true
    if (applied) {
      // 只广播"真的推进了"的情况：游标没变（重复上报）时红点本来就不会变，
      // 广播只会让两个订阅者白读一次盘
      for (const listener of readListeners) {
        try {
          listener(peerUserId, upToMessageId)
        } catch (e) {
          // 某个订阅者出错不能影响别人（更不能让已读上报失败）
          console.warn('[message-store] 已读事件订阅者出错', e)
        }
      }
    }
    return applied
  } catch (e) {
    console.warn('[message-store] 写本地已读游标失败（红点可能不准，下次进会话会再记）', e)
    return false
  }
}

/**
 * 从磁盘推断"上次用这台机器的是哪个账号"（{@code null} = 推断不出来）。
 *
 * <p>用途只有一个：<b>断网 / 后端没起时也能把本地库打开</b>。
 * 本地库要先知道"我是谁"才能读（文件按 {@code u<userId>/} 分目录），
 * 而这个身份原先只能靠 WS 的 welcome 帧或 {@code /user/me} —— 两条都要后端。
 * 结果就是"没网就没历史"：磁盘上明明有，界面一片空白。
 *
 * <p>主进程那边是直接看目录名推断的（目录名本身就是 userId），不碰任何网络。
 */
export async function resolveLastAccount() {
  if (!hasMessageStore || typeof store.lastAccount !== 'function') {
    return null
  }
  try {
    const result = await store.lastAccount()
    const id = Number(result?.userId)
    return Number.isInteger(id) && id > 0 ? id : null
  } catch (e) {
    console.warn('[message-store] 推断"上次是哪个账号"失败（离线时可能读不到历史）', e)
    return null
  }
}

/**
 * 读本地存着的附件本体（图片/文件字节）。
 *
 * @param {string} name 服务端签发的文件名（如 {@code ab12….png}）
 * @returns {Promise<Uint8Array|null>} {@code null} = 本地没有这份内容（不是错误），
 *          调用方应该去服务端重新取一次再 {@link saveLocalMedia} 回来
 */
export async function readLocalMedia(name) {
  if (!hasMessageStore || typeof store.readMedia !== 'function') {
    return null
  }
  try {
    const result = await store.readMedia({ name })
    return result && result.ok === true && result.bytes ? result.bytes : null
  } catch (e) {
    console.warn('[message-store] 读本地附件失败（会退回服务端取）', e)
    return null
  }
}

/**
 * 把附件本体存到本地（{@code messages/u<我>/media/}）。
 *
 * <p>服务端那份永久保存，但它要联网才拿得到；本地这份是"断网也能看"的那份，
 * 而且清浏览器缓存、换设备缓存目录都不影响它。
 *
 * @param {string} name 服务端签发的文件名
 * @param {Uint8Array} bytes 文件内容
 * @returns {Promise<boolean>} 是否存成功（失败不致命：下次显示时还会再尝试）
 */
export async function saveLocalMedia(name, bytes) {
  if (!hasMessageStore || typeof store.saveMedia !== 'function') {
    return false
  }
  try {
    const result = await store.saveMedia({ name, bytes })
    return !!result && result.ok === true
  } catch (e) {
    console.warn('[message-store] 存本地附件失败（不影响显示，下次再存）', e)
    return false
  }
}

/** 本地会话列表（消息页秒开用）。没有本地库时返回空数组 */
export async function listLocalConversations() {
  if (!hasMessageStore) {
    return []
  }
  try {
    const result = await store.list()
    return result && Array.isArray(result.conversations) ? result.conversations : []
  } catch (e) {
    console.warn('[message-store] 读本地会话列表失败', e)
    return []
  }
}

/** 清本地消息：不传 peerUserId 就是全清。排查"本地数据脏了"时用 */
export async function clearLocalMessages(peerUserId) {
  if (!hasMessageStore) {
    return false
  }
  try {
    const result = await store.clear(peerUserId === undefined ? undefined : { peerUserId })
    return !!result && result.ok === true
  } catch (e) {
    console.warn('[message-store] 清本地消息失败', e)
    return false
  }
}

/** 存储位置与规模（设置页显示 / 排查多开时看它：两个窗口必须显示不同目录） */
export async function getLocalStoreInfo() {
  if (!hasMessageStore) {
    return null
  }
  try {
    return (await store.info()) || null
  } catch (e) {
    console.warn('[message-store] 读本地库信息失败', e)
    return null
  }
}
