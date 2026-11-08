// 单聊的实时通道：WebSocket 客户端，跑在【Electron 主进程】里。
//
// 为什么非要放主进程，不能放渲染进程（页面里）：
//   浏览器的 WebSocket API 【不允许自定义请求头】，而握手必须带
//   `Authorization: Bearer <token>`（网关要验 token、再注入 X-User-Id）。
//   主进程用的是 Node 的 ws 库，能带请求头 —— 这就是唯一的原因。
//
// 附带的好处（比"能带请求头"更重要）：
//   token 只由主进程从保险箱里读出来用，渲染进程从头到尾拿不到它，
//   页面里就算被注入了脚本，也偷不走这条连接的身份。
//
// 职责边界：
//   · 连接 / 断线重连 / 心跳 —— 全在主进程做，渲染进程不用管
//   · 收到的帧原样转发给渲染进程（业务怎么处理是页面的事）
//   · 渲染进程要发帧（目前只有 ack）走 IPC 进来
const { readCredential } = require('./credential-vault.cjs')

// ws 是 Node 的 WebSocket 客户端库（浏览器的 WebSocket 不能自定义请求头，所以主进程要用它）。
//
// ⚠️ 用 try/catch 包住 require，是为了"缺这个依赖时只让实时通道不可用，
//    不要让整个桌面应用起不来"：发消息、拉历史都走 HTTP，跟这个依赖无关。
//    真缺了会在控制台大声报错，并通过状态帧告诉页面。
let WebSocket = null
try {
  WebSocket = require('ws')
} catch (error) {
  console.error('[chat-socket] 缺少 ws 模块，实时收消息不可用。请先在 chat-web 目录执行: npm i ws')
  console.error('[chat-socket] 原因:', error?.message)
}

/** 心跳间隔：中间设备（Nginx / 网关）通常会掐掉空闲连接，30 秒发一次 ping 保住它 */
const HEARTBEAT_MS = 30_000
/** 重连退避：1s、2s、4s……最多 30s */
const RECONNECT_BASE_MS = 1_000
const RECONNECT_MAX_MS = 30_000

/**
 * 服务端"踢掉旧连接"时用的关闭码。
 *
 * ⚠️ 必须和 chat-message 的 {@code OnlineUserRegistry.CLOSE_CODE_KICKED} 一致。
 *
 * 用它区分两种完全不同的断线：
 *   · 网络断了（1000 / 1006 / …）      → 正常退避重连，越快越好；
 *   · 账号在别的地方被顶掉了（4001）   → 【绝对不能立刻重连】。
 *
 * 为什么后者不能立刻重连：同一个账号在两处登录时，新连接会踢掉旧连接。
 * 如果被踢的那端也马上重连，它又把对方踢掉，对方再马上重连……
 * 两边就进入无限对踢：每秒一轮重连，而每次重连都会触发一次"上线补拉"，
 * 等于自己给自己打了一波每秒一次的请求。用 1000（默认码）踢人是没法区分的，
 * 所以服务端那边专门用了 4001 这个应用自定义码。
 */
const KICKED_CLOSE_CODE = 4001
/**
 * 被踢之后隔多久再试一次。
 *
 * 为什么不干脆不重连：另一端可能已经退出了，那样本机就永远收不到实时消息。
 * 所以退一步 —— 用一次很长的固定等待，让两边的重连错开（一个 60 秒一轮的周期性互踢
 * 远好过每秒一轮），同时保留"对方退出后自愈"的能力。
 */
const KICKED_RETRY_MS = 60_000

let socket = null
let heartbeatTimer = null
let reconnectTimer = null
let reconnectAttempt = 0
/**
 * "暂时还没有 token"时的短重试。
 *
 * <p>为什么需要它：登录成功后渲染进程先发"存凭证"的 IPC、紧接着发"连 WS"的 IPC。
 * 而写凭证是<b>异步落盘</b>的，主进程这边又是<b>同步读文件</b>拿 token ——
 * 两条 IPC 之间没有先后保证，于是很容易读到旧文件（甚至文件还不存在）。
 *
 * <p>以前这里直接放弃并报 {@code no-token}：页面显示"已登录"、实时通道却永远不通，
 * 之后所有消息只能靠补拉（聚焦才算一次）—— 这就是"必须聚焦才能收到消息"的根因。
 *
 * <p>现在给 10 次 × 300ms = 3 秒的宽限：真的没登录时 3 秒后照样报 no-token，
 * 而"刚登录完、凭证还在写盘路上"这种情况就能自愈。
 */
const NO_TOKEN_RETRY_MS = 300
const NO_TOKEN_RETRY_MAX = 10
let noTokenRetries = 0
let tokenRetryTimer = null
/** 取主窗口的函数（每次发帧都现取，这样窗口重建之后照样能送到 —— macOS 上关窗不退出应用） */
let resolveWindow = null
let manuallyClosed = false

/**
 * 后端 WebSocket 地址。
 *
 * ⚠️ 打包后这里必须指向真实后端 —— 和前端那个 VITE_API_BASE 是同一类问题
 * （dev 时靠 localhost 默认值，打包后要用环境变量/配置覆盖），
 * 因为它跑在主进程里，读不到 Vite 的 import.meta.env。
 */
function wsUrl() {
  return process.env.CHAT_WS_URL || 'ws://localhost:8080/api/message/ws'
}

/** 从保险箱里取当前 token；没有、或者已过期都返回 null */
function currentToken() {
  const raw = readCredential() // 形如 '{"token":"...","expiresAt":1712345678901}'
  if (!raw) return null
  try {
    const session = JSON.parse(raw)
    if (!session || !session.token) return null
    if (session.expiresAt && Date.now() >= session.expiresAt) return null
    return session.token
  } catch {
    return null
  }
}

/** 把一个对象发给渲染进程（webContents.send 用的是结构化克隆，普通对象直接能传） */
function sendToRenderer(payload) {
  const win = resolveWindow ? resolveWindow() : null
  if (!win || win.isDestroyed()) {
    return
  }
  /*
    诊断用：窗口没聚焦时收到一条消息，就在终端里留一行。
    这条日志是给"不聚焦就收不到消息"那种问题用的 —— 它能把故障定位到半程：
      · 有这行、页面却没反应  → WS 和主进程都正常，问题在渲染进程（被节流/冻结）；
      · 连这行都没有          → 消息根本没到本进程，问题在服务端推送或连接；
      · 有这行、且已经修好（见 main.cjs 的 backgroundThrottling）→ 页面会自动跟上，
        这行只是留个证据，不打扰任何人（focused 时不打）。
  */
  if (payload && payload.type === 'message' && !win.isFocused()) {
    console.debug('[ws] 窗口未聚焦时收到消息帧: messageId=%s',
      payload.data && payload.data.messageId)
  }
  win.webContents.send('chat:frame', payload)
}

function clearTimers() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

function scheduleReconnect() {
  if (manuallyClosed || reconnectTimer) return
  const delay = Math.min(RECONNECT_BASE_MS * 2 ** reconnectAttempt, RECONNECT_MAX_MS)
  reconnectAttempt += 1
  sendToRenderer({ type: '__status', state: 'reconnecting', attempt: reconnectAttempt, delay })
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connect()
  }, delay)
}

/**
 * 被顶掉之后的重新尝试：<b>固定长等待</b>，不走指数退避。
 *
 * <p>刻意不复用 {@link scheduleReconnect}：那个的第一次延迟是 1 秒，
 * 而"被踢后 1 秒就重连"正是无限对踢的成因（见 KICKED_CLOSE_CODE 的注释）。
 */
function scheduleKickedRetry() {
  if (manuallyClosed || reconnectTimer) return
  sendToRenderer({ type: '__status', state: 'kicked-waiting', retryIn: KICKED_RETRY_MS })
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connect()
  }, KICKED_RETRY_MS)
}

function connect() {
  if (!WebSocket) {
    // 依赖缺失：明确告诉页面"实时通道不可用"，而不是静默什么都不做
    sendToRenderer({
      type: '__status',
      state: 'no-module',
      message: '实时通道不可用：主进程缺少 ws 模块（请在 chat-web 目录执行 npm i ws）',
    })
    return { ok: false, reason: 'no-module' }
  }

  /*
    ⚠️ 这里必须先把 manuallyClosed 清掉。
    为什么：登录成功时渲染进程走的是"先 disconnect、再 connect"（restartChatConnection），
    而 disconnect() 会把 manuallyClosed 置 true。如果 token 此刻还读不到（凭证正在写盘、
    或者文件暂时读失败），下面那个 `!manuallyClosed` 判断就会让代码【跳过】3 秒短重试、
    直接报 no-token —— 而且之后再没有任何东西会重新触发连接，通道就永久死在那儿了。
    "调用 connect()" 本身就是"我要连"的意思（登出那条路不会调它），所以在这里复位是安全的。
  */
  manuallyClosed = false

  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return { ok: true, already: true }
  }

  const token = currentToken()
  if (!token) {
    /*
      没有 token 有两种完全不同的情况，必须区别对待：
        ① 用户确实没登录 —— 不该一直重试（连上去也会被网关 401）；
        ② 刚登录完、凭证还在写盘的路上 —— 必须重试几次，否则这条通道永远连不上。
      用"短间隔 + 有限次数"同时照顾两者：最多 10 次 × 300ms = 3 秒，
      期间状态报 waiting-token（界面能显示"正在建立通道"），超时才报 no-token。
      显式断开（disconnect）之后不会有人再调 connect()，所以重试不会把登出的用户又连回去。
    */
    if (noTokenRetries < NO_TOKEN_RETRY_MAX) {
      noTokenRetries += 1
      sendToRenderer({ type: '__status', state: 'waiting-token', attempt: noTokenRetries })
      if (tokenRetryTimer) {
        clearTimeout(tokenRetryTimer)
      }
      tokenRetryTimer = setTimeout(() => {
        tokenRetryTimer = null
        connect()
      }, NO_TOKEN_RETRY_MS)
      return { ok: true, waiting: true }
    }
    noTokenRetries = 0
    sendToRenderer({ type: '__status', state: 'no-token' })
    return { ok: false, reason: 'no-token' }
  }

  // 拿到 token 了：清掉重试计数与等待中的那次重试
  noTokenRetries = 0
  if (tokenRetryTimer) {
    clearTimeout(tokenRetryTimer)
    tokenRetryTimer = null
  }

  const ws = new WebSocket(wsUrl(), { headers: { Authorization: `Bearer ${token}` } })
  /*
    把这一条连接记成"当前连接"，之后每个事件都先确认"我还是当前那条"。
    为什么必须确认：看门狗（渲染进程发现半死会主动重连）和登录换 token 都会
    "先断开旧的、再建新的"。而半死的旧连接调完 close() 之后，它的 close 事件
    可能要等 30 秒的关闭超时才到 —— 那时新连接早就 open 了，
    旧连接的事件一到就会把状态改回 closed、还会再排一次重连（界面闪一下、多拆一次）。
  */
  socket = ws

  ws.on('open', () => {
    if (ws !== socket) {
      return   // 已经被换掉了：旧连接的事件全部丢掉
    }
    reconnectAttempt = 0
    sendToRenderer({ type: '__status', state: 'open' })

    // 心跳：连接活着的时候每 30 秒发一个 ping。
    // 服务端回 pong（渲染进程也会收到，页面可以顺便确认通道是活的）。
    if (heartbeatTimer) clearInterval(heartbeatTimer)
    heartbeatTimer = setInterval(() => {
      if (socket === ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'ping' }))
      }
    }, HEARTBEAT_MS)
  })

  ws.on('message', (raw) => {
    if (ws !== socket) {
      return
    }
    const text = raw.toString()
    try {
      sendToRenderer(JSON.parse(text))
    } catch {
      // 不是 JSON 的帧不该出现；原样转给页面，日志里留个痕
      console.warn('[chat-socket] 收到非 JSON 帧:', text.slice(0, 200))
      sendToRenderer({ type: '__error', message: '收到非 JSON 帧', raw: text.slice(0, 200) })
    }
  })

  ws.on('close', (code, reasonBuffer) => {
    if (ws !== socket) {
      return   // 旧连接迟到的 close：不报状态、也不重连（当前连接是好的）
    }
    clearTimers()
    const reason = reasonBuffer ? reasonBuffer.toString() : ''

    // 账号在别处被顶掉了：绝不能走普通重连（那会变成无限对踢，见 KICKED_CLOSE_CODE 的注释）
    if (code === KICKED_CLOSE_CODE) {
      console.warn('[chat-socket] 连接被服务端顶掉:', reason || '(无原因)')
      sendToRenderer({ type: '__status', state: 'kicked', code, reason })
      if (!manuallyClosed) scheduleKickedRetry()
      return
    }

    sendToRenderer({ type: '__status', state: 'closed', code, reason })
    if (!manuallyClosed) scheduleReconnect()
  })

  ws.on('error', (error) => {
    if (ws !== socket) {
      return
    }
    // error 之后一定会跟一个 close，重连逻辑放在 close 里，这里只上报
    sendToRenderer({ type: '__status', state: 'error', message: error?.message || String(error) })
  })

  return { ok: true }
}

function disconnect() {
  manuallyClosed = true
  clearTimers()
  reconnectAttempt = 0
  // 手工断开时也要停掉"等 token"的重试，否则登出后它还会把连接拉起来
  noTokenRetries = 0
  if (tokenRetryTimer) {
    clearTimeout(tokenRetryTimer)
    tokenRetryTimer = null
  }
  if (socket) {
    try {
      socket.close()
    } catch {
      // 关不上就算了（连接可能早就断了）
    }
    socket = null
  }
  return { ok: true }
}

/** 渲染进程发帧（目前只有 ack） */
function sendFrame(frame) {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    return { ok: false, reason: 'not-open' }
  }
  try {
    socket.send(typeof frame === 'string' ? frame : JSON.stringify(frame))
    return { ok: true }
  } catch (e) {
    return { ok: false, reason: e?.message || 'send-failed' }
  }
}

/**
 * 注册 IPC。在 app ready、窗口建好之后调用。
 *
 * @param getWindow 返回主窗口的函数（<b>存起来而不是取一次</b>：窗口可能被关闭再重建）
 */
function registerChatHandlers(ipcMain, getWindow) {
  resolveWindow = getWindow
  ipcMain.handle('chat:connect', () => connect())
  ipcMain.handle('chat:disconnect', () => disconnect())
  ipcMain.handle('chat:send', (event, frame) => sendFrame(frame))
}

module.exports = { registerChatHandlers }
