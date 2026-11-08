// 账号维度的内存缓存：我是谁 + 我的好友 + 待处理的好友申请。
//
// ── 为什么要把这三样提到 App 级 ────────────────────────────────
// 以前"我是谁""好友列表""好友申请"是每个页面挂载时各拉各的：
//   消息页拉 me + friends、联系人页拉 friends + requests、设置页拉 me、聊天页又拉 me + 单个人。
// 结果就是"切一个 tab 打三四个请求"，而这三样东西在【同一个进程内】几乎不会变
// （我是谁不变；好友要等我主动加/删才变；申请要靠别人发）。
// 把它们缓存成一份、各页只读，切页就 0 请求了。
//
// ── 失效点（这是缓存的命门，漏一个就是"旧数据"）────────────────
//   · 换账号 / 登出        → clearAccountData()（login 流程和 logout.js 会调）
//   · 我改了资料            → Settings 保存成功后直接写回 me
//   · 加 / 删 / 同意好友     → refreshFriends()
//   · 同意 / 拒绝申请        → refreshFriendRequests()
//   · 主布局启动 / 聚焦 / 前台 / 重连 → requestReminderStore统一刷新两类请求数量
//
// ── 为什么"好友"重连不重拉、"申请"要重拉 ─────────────────────────
// 好友列表变化只能是我自己发起的（加/删/同意/改备注），本机一定知道，缓存不会过期；
// 好友申请、群邀请的数量由共享汇总刷新维护，详情在对应列表页加载。
import { ref } from 'vue'
import { api } from '../api'
import { getSession, setSessionUserId } from './auth'
import { clearConversations, refreshConversations } from './conversationStore'
import { useLocalAccount, resolveLastAccount } from './messageStore'
import { friendRequests, refreshFriendRequests, refreshRequestReminders, resetRequestReminders } from './requestReminderStore'
import { resetGroupChatSessions } from './groupChatStore'
export { friendRequests, refreshFriendRequests }

/** 我是谁（{@code UserDetailVO}：userId / username / nickName / avatarUrl / gender / birthDate） */
export const me = ref(null)

/** 好友 map：peerUserId → 好友卡片（含 remark / nickName / avatarUrl） */
export const friends = ref(new Map())

/** 身份数据是否已经加载过至少一次（页面据此决定显示骨架屏还是空态） */
export const identityReady = ref(false)

/** 当前缓存归属哪个账号（用来发现"换账号"） */
let knownUserId = null

/** 身份与好友请求各自合并在飞请求；申请提醒由独立共享状态维护。 */
let mePromise = null
let friendsPromise = null

/** 取某个好友的卡片（按 peerUserId）；不是好友返回 null */
export function getFriend(peerUserId) {
  const id = Number(peerUserId)
  return Number.isFinite(id) ? (friends.value.get(id) || null) : null
}

/**
 * 拉"我是谁"。失败吞掉（返回 null）—— 缓存拿不到不该影响页面，等下次触发再试。
 *
 * <p><b>顺手做一件很关键的事</b>：把 userId 告诉本地消息库（{@link useLocalAccount}），
 * 也就是"messages/u<我>/ 这份数据归谁"。这一步以前只有 WS 的 welcome 帧会做，
 * 结果是——<b>只要实时通道没连上，本地库就处于 fail-closed 状态（读一律当空、写一律拒绝）</b>，
 * 表现出来就是"打开会话什么都没有、刚发的消息也没落盘"。
 * 现在 /user/me 这条路也会设，两条路谁先到算谁（{@code useAccount} 本身幂等）。
 *
 * <p>设完归属再刷新一次会话列表：启动时那次读取可能发生在归属确定之前（读到空），
 * 这里补一次，列表才不会一直空着。
 */
export function refreshMe() {
  if (mePromise) {
    return mePromise
  }
  mePromise = api.me()
    .then(async (data) => {
      me.value = data || null
      if (me.value?.userId != null) {
        // 记住"这次是谁"：写进本地会话记录（和 token 同一条，最准），再写一份 localStorage。
        // 这样下次启动即使连不上后端，也能用它在本地打开 u<userId>/ 那份历史。
        setSessionUserId(me.value.userId)
        rememberUserId(me.value.userId)
        // 等它完成：调用方（ensureMe / loadIdentity）拿到 me 之后马上要用本地库
        await useLocalAccount(me.value.userId)
        // 归属刚确定，本地库现在才真正可读 → 让共享会话列表重读一次
        refreshConversations()
        // 顺手留一份离线副本（断网时列表才有名字/头像）
        persistIdentity()
      }
      return me.value
    })
    .catch((e) => {
      console.warn('[account] 拉取"我是谁"失败', e)
      return null
    })
    .finally(() => {
      mePromise = null
    })
  return mePromise
}

/**
 * 拉好友列表。失败保留旧值（不清空）—— 清空会把界面洗成"没有好友"，
 * 那比"还是旧列表"更糟。
 */
export function refreshFriends() {
  if (friendsPromise) {
    return friendsPromise
  }
  friendsPromise = api.listFriends()
    .then((list) => {
      friends.value = new Map((list || []).map((friend) => [friend.userId, friend]))
      // 留一份离线副本：断网时列表每一行才显示得出名字/头像（头像走首字兜底）
      persistIdentity()
      return friends.value
    })
    .catch((e) => {
      console.warn('[account] 拉取好友列表失败', e)
      return friends.value
    })
    .finally(() => {
      friendsPromise = null
    })
  return friendsPromise
}

/**
 * 一次把三样都拉齐（登录成功 / 应用启动已登录时调）。
 *
 * <p>拉完再判定"是不是换账号了"：是的话清掉界面那份会话列表
 * （本地消息库按 {@code u<userId>} 分目录，已经天然隔离，这里清的是内存里那份）。
 * 判定用 me.userId 和上次记的比 —— 这是"当前是谁"唯一可靠的前端依据。
 *
 * <p><b>⚠️ 顺序很关键：先把"离线也能用"的那两件事做完，再去等网络。</b>
 * <ol>
 *   <li>{@link openLocalStoreFromMemory}：用"上次是谁"把本地消息库打开。
 *       不做这一步的话，后端没起（或者断网）时本地库会一直 fail-closed ——
 *       磁盘上明明有聊天记录，界面上却一条都看不到，等于"没网就没历史"；</li>
 *   <li>{@link restoreIdentityFromCache}：名字和头像先用上次缓存的，
 *       否则离线时列表每行都是"没有名字的一行"。</li>
 * </ol>
 * 这两步都不依赖网络，所以放在 {@code await Promise.all} 之前。
 */
export async function loadIdentity() {
  await openLocalStoreFromMemory()
  restoreIdentityFromCache()

  await Promise.all([refreshMe(), refreshFriends(), refreshRequestReminders()])
  identityReady.value = true

  const id = me.value?.userId ?? null
  if (id != null && knownUserId !== id) {
    if (knownUserId != null) {
      // 换账号：别把上一个人的会话合进来（串号级错误）
      clearConversations()
      // 清完要按新账号重读一次本地索引 —— refreshMe 里那次读取发生在这次清空【之前】，
      // 不补读的话列表会空着，直到下一次别的事件触发刷新
      refreshConversations()
    }
    knownUserId = id
  }
}

// ==================== 离线兜底：本地库归属 + 身份缓存 ====================
//
// 这两块解决同一个问题：**后端没起 / 断网时，本地明明有数据，界面却是空的**。
//
// 根因是"本地消息库要先知道我是谁"（文件按 u<userId>/ 分目录），而"我是谁"原先只有
// 两条来源：WS 的 welcome 帧、以及 /user/me —— 两条都要连上后端。
// 于是没网时 owner 永远是 null → 本地库 fail-closed（读一律当空）→ 消息页和聊天页全空。
//
// 解法：登录成功时把 userId 记在 localStorage 里，下次启动先用它把本地库打开。
// 它不敏感（就一个数字）、而且和 token 是同一次登录写下的，所以"记住的账号"和
// "token 代表的账号"不会打架；等 /user/me 或 welcome 帧回来之后会照常校正
// （真换了账号，loadIdentity 的换账号检测会清掉会话列表）。

/** 上次登录的 userId（离线时用它打开本地消息库） */
const LAST_USER_KEY = 'chat_last_user_id'
/** 名字/头像/好友列表的离线副本（断网时列表才不是"无名行"） */
const IDENTITY_CACHE_KEY = 'chat_identity_cache'

/** 读"上次是谁"；没有或格式不对返回 null */
function readRememberedUserId() {
  try {
    const id = Number(localStorage.getItem(LAST_USER_KEY))
    return Number.isInteger(id) && id > 0 ? id : null
  } catch {
    // localStorage 不可用（隐私模式等）：当没有
    return null
  }
}

/**
 * 从本地会话记录（保险箱里那份）读"这份 token 属于谁"。
 *
 * <p>这是<b>最准</b>的来源：userId 和 token 写在同一条记录里，换账号时一起变，
 * 不会出现"记住的是 A、token 其实是 B"这种错配。
 */
function rememberedUserIdFromSession() {
  try {
    const id = Number(getSession()?.userId)
    return Number.isInteger(id) && id > 0 ? id : null
  } catch {
    return null
  }
}

/** 记住/忘记"上次是谁"。传 null 表示清掉（登出） */
function rememberUserId(userId) {
  try {
    if (userId == null) {
      localStorage.removeItem(LAST_USER_KEY)
    } else {
      localStorage.setItem(LAST_USER_KEY, String(userId))
    }
  } catch {
    // 记不住最多是"下次离线看不到历史"，不值得打扰用户
  }
}

/**
 * 用"上次是谁"把本地消息库打开（不依赖网络）。
 *
 * <p>打开之后 {@code listLocalConversations()} / {@code loadLocalMessages()} 才读得到东西，
 * 消息页和聊天页也才能在断网时照常显示历史。
 */
async function openLocalStoreFromMemory() {
  /*
    三个来源按"准不准"排序，逐个降级：
      ① 本地会话记录里的 userId —— 和 token 同一条记录，最准（换账号会一起变）；
      ② localStorage 里"上次登录的 userId" —— 同一个浏览器/profile 内够用；
      ③ 从磁盘目录推断 —— 老版本升级上来、或者上面两个都没有时的最后兜底
         （messages/u<userId>/ 的目录名本身就是 userId，取最近用过的那个）。
    三步都不碰网络，所以断网/后端没起时本地库也能打开。
  */
  let remembered = rememberedUserIdFromSession()
  if (remembered == null) {
    remembered = readRememberedUserId()
  }
  if (remembered == null) {
    remembered = await resolveLastAccount()
  }
  if (remembered == null) {
    return
  }
  rememberUserId(remembered)   // 统一写进 localStorage，下次就不用再猜
  await useLocalAccount(remembered)
  /*
    顺手把它记成"当前账号"：这样万一本机记住的是 A、而 token 其实是 B
    （比如 token 过期后直接换了账号登录、没走登出），下面 loadIdentity 的
    "换账号"检测就能发现，清掉 A 的会话再按 B 重读 —— 不至于把两个人的列表混在一起。
  */
  knownUserId = remembered
  // owner 一确定，本地索引就可读了 → 让共享会话列表立刻读一次（0 请求）
  refreshConversations()
}

/** 把名字/头像/好友列表写成离线副本（字段只留界面要用的，别把整份 VO 塞进去） */
function persistIdentity() {
  try {
    localStorage.setItem(IDENTITY_CACHE_KEY, JSON.stringify({
      me: me.value
        ? {
            userId: me.value.userId,
            username: me.value.username ?? null,
            nickName: me.value.nickName ?? null,
            avatarUrl: me.value.avatarUrl ?? null,
          }
        : null,
      friends: [...friends.value.values()],
    }))
  } catch {
    // 存不下就算了：离线时最多是没有名字，不影响收发消息
  }
}

/** 从离线副本恢复"我是谁 + 好友"。只在内存里还没有值时填，绝不覆盖刚拉到的网络数据。 */
function restoreIdentityFromCache() {
  try {
    const raw = localStorage.getItem(IDENTITY_CACHE_KEY)
    if (!raw) {
      return
    }
    const cached = JSON.parse(raw)
    if (me.value == null && cached?.me) {
      me.value = cached.me
    }
    if (friends.value.size === 0 && Array.isArray(cached?.friends)) {
      friends.value = new Map(cached.friends.map((friend) => [friend.userId, friend]))
    }
  } catch {
    // 缓存坏了就当没有（下次成功拉取会重写）
  }
}

/**
 * 保证"我是谁"可用（拿不到就现拉一次）。
 *
 * <p>给"发消息时要填 senderUserId"这种点用：正常情况下 welcome 帧 + 启动加载早就有了，
 * 这里是兜住"刚好卡在还没加载完"的那个窄窗口（浏览器没有 WS 时尤其可能）。
 */
export async function ensureMe() {
  if (me.value?.userId != null) {
    return me.value
  }
  await refreshMe()
  return me.value
}

/** 加好友相关的列表一起刷新（同意申请后：好友多了、申请少了） */
export function reloadSocial() {
  return Promise.all([refreshFriends(), refreshFriendRequests()])
}

/**
 * 登出 / 换账号前清空（内存里的身份数据不能跨账号残留）。
 *
 * <p>连离线副本一起删：{@code chat_last_user_id} 和 {@code chat_identity_cache}
 * 都是"上一个账号"的东西，留着会让下一个人在断网时看到前一个人的会话列表和名字 ——
 * 那是串号级别的错误，比"离线看不到历史"严重得多。
 */
export function clearAccountData() {
  resetGroupChatSessions()
  resetRequestReminders()
  me.value = null
  friends.value = new Map()
  identityReady.value = false
  knownUserId = null
  mePromise = null
  friendsPromise = null
  rememberUserId(null)
  try {
    localStorage.removeItem(IDENTITY_CACHE_KEY)
  } catch {
    // 存储不可用时忽略
  }
}

