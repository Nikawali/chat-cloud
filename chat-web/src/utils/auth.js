// 登录凭证（token）的本地保管。
//
// ── 有效期为什么是 7 天 ──────────────────────────────────────────
// 后端 chat-auth 的 TokenService 里，Redis 上的 `auth:token:{token}` 就是
// `Duration.ofDays(7)`。token 生命周期【由后端说了算】，前端的 7 天只是
// 本地副本的"预期到期时间"，用来在启动时提前判掉明显过期的凭证。
// 真正的权威永远是 Redis：就算本地时间被改过，请求打到后端照样会被 401 拦下，
// 而 401 会在 api 层触发 clearLogin()。两边要一起改，所以这里用常量标出来。
//
// ── 存哪儿 ─────────────────────────────────────────────────────
// Electron 桌面端：交给【主进程】保管（electron/main.cjs + preload.cjs），
//   用 safeStorage 加密后落在 userData 目录下的文件里。
//   密钥由操作系统（Windows 上是 DPAPI）按当前用户账号派生，
//   渲染进程既拿不到密文也拿不到密钥 —— 页面上的 XSS 偷不走它。
// 纯浏览器（npm run dev 直接开浏览器）：退化成 localStorage，
//   因为根本没有主进程可用。这条路径只为开发方便，安全性等同于没有。
//
// ── 读写为什么一个同步一个异步 ─────────────────────────────────
// 读：主进程里的凭证在 init() 时一次性读进内存镜像，之后 getToken() 就是纯内存读取。
//     所以 api 层取 token 不需要到处写 await。
// 写：加密 + 落盘都在主进程，天然是异步的，丢过去就不用等 —— 写失败也不该阻塞登录。

const SESSION_KEY = 'chat_session'
const EXPIRE_FLAG_KEY = 'chat_session_expired'

/** 与后端 TokenService.TOKEN_TTL 保持一致：7 天 */
export const TOKEN_TTL_DAYS = Number(import.meta.env.VITE_CHAT_TOKEN_TTL_DAYS) || 7

/** 本地判断"过期前多久就当作已过期"。留一点余量，避免卡着到期点提交请求白跑一趟。 */
const EXPIRE_SKEW_MS = 60 * 1000

const TTL_MS = TOKEN_TTL_DAYS * 24 * 60 * 60 * 1000

/** 主进程 IPC 是否可用（打包后必然可用；浏览器里没有 window.electronAPI） */
function hasVaultApi() {
  return typeof window !== 'undefined' && !!window.electronAPI?.readCredential
}

// ---------------------------------------------------------------------------
// 存储后端
// ---------------------------------------------------------------------------

/**
 * 纯浏览器：localStorage。
 * 读写都是同步的 —— 后面那个同步读接口正好对得上。
 */
const webStore = {
  getRaw() {
    try {
      return localStorage.getItem(SESSION_KEY)
    } catch {
      return null
    }
  },
  getFlag() {
    try {
      return localStorage.getItem(EXPIRE_FLAG_KEY) === '1'
    } catch {
      return false
    }
  },
  setRaw(value) {
    try {
      if (value === null) localStorage.removeItem(SESSION_KEY)
      else localStorage.setItem(SESSION_KEY, value)
    } catch {
      // 存储不可用（隐私模式等），忽略
    }
  },
  setFlag(on) {
    try {
      if (on) localStorage.setItem(EXPIRE_FLAG_KEY, '1')
      else localStorage.removeItem(EXPIRE_FLAG_KEY)
    } catch {
      // 忽略
    }
  },
}

/**
 * Electron：真正的数据在主进程，这里只保留一份内存镜像。
 *
 * <p>镜像在 init() 时灌好；之后读是纯内存，写则同步更新镜像 + 异步丢给主进程。
 * "先改镜像再异步落盘"这个顺序很重要：用户在登录成功的一瞬间就发下一个请求，
 * 也能带上 token。
 */
const electronStore = {
  raw: null,
  flag: false,
  /**
   * 最近一次"凭证落盘"的 promise。
   *
   * <p>为什么要记它：主进程连 WebSocket 时要<b>同步读</b> credentials.json 拿 token，
   * 而这里的写盘是不等待的 IPC。登录成功后的顺序是
   * {@code saveToken(...)} → {@code restartChatConnection()} ——
   * 如果不等写入落地，主进程会读到旧文件（甚至不存在），报 {@code no-token}
   * 然后<b>直接放弃连接</b>。表现出来的就是"明明登录了，实时通道却是死的"，
   * 之后所有消息只能靠补拉（聚焦才算一次）。
   * 登录流程用 {@link flushCredentialWrite} 等它一下即可。
   */
  lastWrite: Promise.resolve(),

  getRaw() {
    return this.raw
  },
  getFlag() {
    return this.flag
  },
  setRaw(value) {
    this.raw = value
    const call = value === null
      ? window.electronAPI.clearCredential()
      : window.electronAPI.saveCredential(value)
    // 记住这次写入（吞掉异常：落盘失败也不回滚内存，本次运行内至少还能用，
    // 但不能让这个 promise 变成 unhandled rejection）
    this.lastWrite = Promise.resolve(call).catch((e) => {
      console.warn('[auth] 凭证落盘失败', e)
    })
  },
  setFlag(on) {
    this.flag = on
    window.electronAPI.setExpiredFlag(on)?.catch?.((e) => console.warn('[auth] 标记落盘失败', e))
  },
}

/** 当前生效的存储后端。init 之前默认 web（此时也只有 web 能工作） */
let store = webStore

let initialized = false

/**
 * 启动时调用一次，必须在 `app.mount()` **之前** await 它。
 *
 * <p>Electron 下把主进程里加密存储的凭证读进内存镜像，同时校验一下
 * safeStorage 是否真的可用；纯浏览器下什么都不做。
 *
 * @returns {Promise<boolean>} 是否成功启用了主进程加密存储
 */
export async function initCredentialStore() {
  if (initialized) return store === electronStore

  initialized = true

  if (!hasVaultApi()) {
    // 纯浏览器：什么都不用做，localStorage 本来就同步可用
    return false
  }

  try {
    const encrypted = await window.electronAPI.isEncryptionAvailable()
    if (!encrypted) {
      // 系统加密不可用（部分 Linux 环境没配 keyring）：退回 localStorage。
      // 此时要把主进程里可能存在的旧凭证读回来，别让用户白白重新登录一次。
      console.warn('[auth] safeStorage 不可用，凭证改存 localStorage（未加密）')
      await pullVaultIntoWeb()
      return false
    }

    // 两个调用并发发出去，省一轮 IPC 往返
    const [raw, flag] = await Promise.all([
      window.electronAPI.readCredential(),
      window.electronAPI.getExpiredFlag(),
    ])
    electronStore.raw = raw
    electronStore.flag = flag === true

    // 先切到 electronStore，再迁移 —— 顺序不能反：
    // migrateFromLocalStorage 靠 store 是不是 electronStore 来判断往哪边搬
    store = electronStore

    // 老版本（升级前）存在 localStorage 里的明文凭证，搬到加密存储里
    await migrateFromLocalStorage()

    return true
  } catch (e) {
    // 主进程通道出问题也别让用户登不进来
    console.warn('[auth] 主进程凭证存储不可用，退回 localStorage', e)
    store = webStore
    return false
  }
}

/**
 * 把 localStorage 里遗留的明文凭证搬到主进程加密存储里，然后删掉明文。
 *
 * <p>这是给"已经用过旧版本"的机器做的一次性迁移。当前没有加密凭证时才搬，
 * 避免把更新的那份覆盖掉。
 */
async function migrateFromLocalStorage() {
  const legacy = webStore.getRaw()
  if (!legacy) return

  if (!electronStore.raw) {
    electronStore.raw = legacy
    electronStore.flag = webStore.getFlag()
    try {
      await window.electronAPI.saveCredential(legacy)
      await window.electronAPI.setExpiredFlag(electronStore.flag)
    } catch (e) {
      console.warn('[auth] 凭证迁移到加密存储失败，本轮仍按未登录处理', e)
      return // 迁移失败就别删明文了，否则用户直接被登出
    }
  }

  // 加密存储这份已经写好，明文可以清掉了
  webStore.setRaw(null)
  webStore.setFlag(false)
}

/**
 * safeStorage 不可用时的反向兜底：把主进程里的凭证读回 localStorage，
 * 免得用户在"能加密 → 不能加密"的环境切换中被动登出。
 */
async function pullVaultIntoWeb() {
  if (webStore.getRaw()) return
  try {
    const raw = await window.electronAPI.readCredential()
    if (!raw) return
    webStore.setRaw(raw)
    webStore.setFlag((await window.electronAPI.getExpiredFlag()) === true)
  } catch {
    // 拿不到就算了，最多重新登录一次
  }
}

/** 当前是否在用主进程加密存储（界面/日志可以据此提示安全级别） */
export function isSecureStorage() {
  return store === electronStore
}

// ---------------------------------------------------------------------------
// 业务接口
// ---------------------------------------------------------------------------

/**
 * 解析会话字符串。任何异常（被手动改坏、旧版本残留的纯字符串）都当作"没登录"，
 * 不能让存储层的问题把整个应用搞崩。
 *
 * @param {string|null} raw 原始字符串
 * @returns {object|null} 形如 { token, expiresAt }
 */
function parseSession(raw) {
  if (!raw) return null
  try {
    const session = JSON.parse(raw)
    if (!session || typeof session.token !== 'string' || !session.token) return null
    if (typeof session.expiresAt !== 'number') return null
    return session
  } catch {
    return null
  }
}

/**
 * 取出会话；已过期就顺手清掉。
 *
 * <p>过期检查不是安全措施（本地时间可以改），纯粹为了体验 ——
 * 别让用户点了半天才发现凭证早过期了。
 */
function loadSession() {
  const session = parseSession(store.getRaw())
  if (!session) return null

  if (Date.now() >= session.expiresAt - EXPIRE_SKEW_MS) {
    clearLogin(true)
    return null
  }
  return session
}

/**
 * 登录成功后调用，把 token 和它的到期时间一起存起来。
 *
 * @param {string} token   后端 Result 里的 data，也就是 token 字符串
 * @param {number} [ttlMs] 自定义有效期（毫秒），不传就用 TOKEN_TTL_DAYS
 * @returns {object|null} 存好的会话对象；token 为空时返回 null
 */
export function saveToken(token, ttlMs = TTL_MS) {
  if (!token) {
    // 后端返回成功但 token 为空属于异常响应，宁可当没登录也不要存个空串
    clearLogin()
    return null
  }

  const session = { token, expiresAt: Date.now() + ttlMs }
  // 新登录：userId 还不知道（要等 /user/me），所以这里不写；拿到之后由 setSessionUserId 补上。
  // 顺手清掉旧记录里的 userId 是必须的 —— 否则换账号登录后，旧 userId 会跟着新 token 留下来，
  // 断网时就会打开上一个人的本地目录（串号）。
  store.setRaw(JSON.stringify(session))
  store.setFlag(false)
  return session
}

/**
 * 把"这份凭证属于哪个 userId"补进本地会话记录。
 *
 * <p><b>为什么要存它</b>：本地消息库的文件按账号分目录（{@code messages/u<userId>/}），
 * 读写前必须先知道"我是谁"。而 token 本身是后端发的随机串，前端解不出 userId ——
 * 以前只能等 WS 的 welcome 帧或 {@code /user/me}，于是<b>断网/后端没起时本地库打不开</b>，
 * 磁盘上明明有历史，界面却是空的。把 userId 记在会话里之后，离线也能打开对应那一层。
 *
 * <p><b>为什么不单独存一个键</b>：它和 token 写在<b>同一条记录</b>里，
 * 换账号登录时两者一起变 —— 不会出现"token 是 B、userId 还是 A"这种错配（那会读串目录）。
 *
 * @param {number} userId 当前登录用户的 id
 */
export function setSessionUserId(userId) {
  const session = loadSession()
  const id = Number(userId)
  if (!session || !Number.isInteger(id) || id <= 0 || session.userId === id) {
    return
  }
  // setRaw 会顺带更新内存镜像 + 异步落盘（写操作在保险箱里是串行的，不会和别的写打架）
  store.setRaw(JSON.stringify({ ...session, userId: id }))
  store.setFlag(false)
}

/**
 * 等"凭证落盘"完成。
 *
 * <p><b>登录流程必须在连实时通道之前 await 它一下。</b>
 * 主进程建 WS 时要同步读 credentials.json 拿 token，而 {@code setRaw} 里的写盘 IPC
 * 是不等待的。不等的话主进程读到的是旧文件 → 报 {@code no-token} 并放弃连接，
 * 而页面这边显示的是"已登录"—— 于是实时通道永远不通，消息只能靠补拉。
 *
 * <p>纯浏览器后端（localStorage）没有这个 promise，await undefined 立即返回，无开销。
 */
export async function flushCredentialWrite() {
  try {
    await store.lastWrite
  } catch {
    // 落盘失败已在 setRaw 里记过日志；这里只保证"等过了"，不让登录流程失败
  }
}

/**
 * 取 token。没有 / 已过期都返回空字符串，调用方只需判断"有没有"。
 *
 * @returns {string}
 */
export function getToken() {
  return loadSession()?.token || ''
}

/**
 * 取完整会话信息（token + 到期时间），给界面显示"还有多久过期"用。
 *
 * @returns {object|null} 形如 { token, expiresAt }
 */
export function getSession() {
  return loadSession()
}

/** 是否已登录（token 存在且未过期） */
export function isLoggedIn() {
  return !!getToken()
}

/**
 * 清空登录状态。
 *
 * @param {boolean} [expired] 是否是"自然过期"导致的清除。
 *        是的话打个标记，界面下次挂载可以提示"登录已过期，请重新登录"——
 *        用户手动登出则不需要这种提示。
 */
export function clearLogin(expired = false) {
  store.setRaw(null)
  store.setFlag(expired)
}

/**
 * 取出并清除"上次是因为过期被登出"的标记。
 * 只生效一次 —— 界面提示过后就不会再提示第二遍。
 *
 * @returns {boolean}
 */
export function consumeExpiredFlag() {
  if (!store.getFlag()) return false
  store.setFlag(false)
  return true
}
