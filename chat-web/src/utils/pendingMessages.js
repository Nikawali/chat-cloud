// 未确认消息的本地暂存（可以理解成一个很小的"发件箱"）。
//
// ── 它解决什么问题 ──────────────────────────────────────────────
// 服务端靠 clientMessageId（+ 唯一索引）保证发送幂等，但这个 id 之前只活在【页面内存】里：
// 刷新页面 / 重启应用之后它就没了。于是"请求其实已经到了服务端、只是受理结果在回程丢了"
// 这种情况下，用户刷新后重打一遍会拿到一个**新的** id —— 服务端认不出是同一条，落两条。
//
// 把"还没被服务端确认的消息"连同它的 clientMessageId 落到本地，刷新后恢复出来、
// 允许用【同一个 id】重发，服务端的幂等才算真正闭环：
// 重发时它返回的是第一次落库的那条（duplicated=true），界面直接变成"已发送"。
//
// ── 存在哪儿 / 代价 ─────────────────────────────────────────────
// localStorage。纯浏览器里没有别的选择；Electron 下主进程有加密保险箱，
// 但那是给 token 用的（量小、频率低）。消息正文放 localStorage 是**明文**的，
// 认真的产品应该换成 IndexedDB 或主进程加密存储 —— 这里先把"幂等闭环"补上。
//
// ── 什么时候清 ─────────────────────────────────────────────────
// 发送成功（拿到服务端 messageId）立刻删。失败 / 中断的留着，等用户点"重发"。
// 另外超过 MAX_AGE_MS 的直接丢弃：一周前的未发消息没有重发价值，留着只会让人困惑。
const STORAGE_KEY = 'chat_pending_messages'

/** 最多留这么多条：正常情况只有"刚发出去还没回来"的那几条，这里是防异常膨胀 */
const MAX_ITEMS = 200

/** 超过这个时间的直接丢掉（7 天） */
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000

/**
 * 读出所有未确认的消息，顺手做一次过期清理。
 *
 * <p>任何异常（被手动改坏、localStorage 不可用）都当成"没有" ——
 * 这个模块挂了最多是刷新后丢了重发能力，绝不能让聊天页打不开。
 */
function readAll() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []

    const now = Date.now()
    return parsed.filter((item) =>
      item
      && typeof item.clientMessageId === 'string'
      && typeof item.content === 'string'
      && Number.isFinite(item.createdAt)
      && now - item.createdAt < MAX_AGE_MS)
  } catch {
    return []
  }
}

function writeAll(list) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list.slice(-MAX_ITEMS)))
  } catch {
    // 隐私模式等场景写不进去：忽略。最多是刷新后少了重发能力，不影响正常收发
  }
}

/**
 * 记下一条"还没被服务端确认"的消息。<b>发送前</b>调用。
 *
 * @param {{clientMessageId: string, peerUserId: number, ownerUserId: number|null, content: string}} item
 *        ownerUserId 是"我自己"，用于换账号时隔离（见 listPending）
 */
export function addPending(item) {
  const list = readAll().filter((x) => x.clientMessageId !== item.clientMessageId)
  list.push({ ...item, createdAt: Date.now() })
  writeAll(list)
}

/** 服务端确认了（拿到了 messageId）→ 删掉，别让它下次又被恢复出来 */
export function removePending(clientMessageId) {
  const list = readAll()
  const next = list.filter((x) => x.clientMessageId !== clientMessageId)
  if (next.length !== list.length) {
    writeAll(next)
  }
}

/**
 * 取"我和某个人之间、还没被确认的消息"，用来在页面加载时恢复出来（状态标成"发送失败，可重发"）。
 *
 * <p><b>必须按 ownerUserId 过滤</b>：同一台机器上换账号登录时，
 * 不能把上一个账号没发出去的消息展示给新账号 —— 那是别人的草稿。
 * 拿不到"我是谁"时（ownerUserId 为 null）宁可什么都不恢复。
 *
 * @param {number} peerUserId 会话里的对方
 * @param {number|null} ownerUserId 我自己的 userId
 */
export function listPending(peerUserId, ownerUserId) {
  if (ownerUserId == null) {
    return []
  }
  return readAll().filter((x) => x.peerUserId === peerUserId && x.ownerUserId === ownerUserId)
}
