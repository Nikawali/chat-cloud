// Electron 主进程（CommonJS）。负责创建窗口、系统托盘、通知等系统级能力。
const { app, BrowserWindow, ipcMain, Notification } = require('electron')
const path = require('node:path')
const { registerCredentialHandlers, filePath: credentialFilePath } = require('./credential-vault.cjs')
const { registerChatHandlers } = require('./chat-socket.cjs')
const { registerMessageHandlers } = require('./message-store.cjs')
const { resolveProfile, profileDir } = require('./profile.cjs')

// ============================================================
//  关掉 Chromium 的"后台节流"——否则窗口一失去焦点，前端就等于暂停
// ============================================================
//
// 踩过的现象：点开别的窗口（我们的窗口被遮住）之后，消息**完全收不到**；
// 一聚焦回来，消息、已读、未读"哗"地一次性全出现。
//
// 原因：Chromium 会把"不可见/被遮挡"的页面当成后台页做三件事 ——
//   ① 节流定时器（setTimeout 从 100ms 变成 1s，隐藏久了变成 1 分钟一次）
//   ② 降低整个渲染进程的优先级（renderer backgrounding）
//   ③ 窗口被别的窗口盖住时，页面直接进 hidden 状态（occluded）
// 而我们的"收消息 → 落盘 → 回 ack"全跑在**渲染进程**里（主进程只负责 WS 连接）：
// 渲染进程一被节流/冻结，WS 帧虽然到了主进程，但推不进页面、append 的 promise 也不落地，
// 于是 ack 发不出去 → 发送方永远停在"已发送"，接收方未读也不涨。
//
// 这四个开关必须【在 app ready 之前】设置，之后加就没用了（Chromium 只在启动时读）。
// 它们和窗口上的 backgroundThrottling: false 是一套，缺一个就可能有漏网的场景：
//   只设 backgroundThrottling 时，"被遮挡"的窗口仍可能被记为 hidden。
app.commandLine.appendSwitch('disable-background-timer-throttling')   // 定时器不降频
app.commandLine.appendSwitch('disable-renderer-backgrounding')        // 渲染进程不降优先级
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows')// 被遮挡 ≠ 后台
// 顺带：窗口被遮挡时不再降低 GPU/合成的刷新（否则动画会明显卡顿）
app.commandLine.appendSwitch('disable-features', 'CalculateNativeWinOcclusion')
//
// ⚠️ 代价：后台也会持续占用少量 CPU（定时器照常跑）—— 对一个聊天客户端完全值得：
//    用户看不到的那个窗口，正是最需要"继续收消息、继续回 ack"的时候。


// ============================================================
//  多开 / 多账号：按 profile 隔离整个数据目录
// ============================================================
//
// 问题（两个成因叠在一起）：
//   ① 登录凭证存在 userData/credentials.json，Chromium 的 Local Storage 也在 userData 下，
//      而 userData 默认是"一个应用一个目录"。于是同一台机器开第二个实例时，
//      两个窗口读的是【同一份凭证】→ 只能同时登一个号，后登的顶掉前一个。
//   ② 更基础的一层：Chromium 对同一个数据目录是有锁的，两个进程同时用它本身就会互相干扰
//      （缓存、localStorage 抢写）。这才是"第二个实例起不来/起不干净"的直接原因。
//
// 解法：给每个实例一个 profile 名字，把 userData / sessionData 都指到它自己的子目录。
// 于是每个实例拥有完全独立的凭证 + localStorage + 缓存 = 完全独立的一个"客户端"，
// 想同时登几个号就开几个实例。
//
// 用法（VS Code 里开两个终端）：
//   $env:CHAT_PROFILE='alice'; npm run electron:dev    # 第一个账号（顺带起 dev server）
//   $env:CHAT_PROFILE='bob';   npm run electron:only   # 第二个账号（复用上面那个 dev server）
// 也可以直接给 electron 传参：electron . --profile=alice
//
// ⚠️ 不设 profile 时【一行路径都不动】—— 和以前完全一样，已经登录的状态不会丢。
//    （所以第一次带 profile 启动会是一个干净的、需要重新登录的客户端，这是预期的。）
const PROFILE = resolveProfile()

if (PROFILE) {
  // 必须先读默认值：setPath 之后再调 getPath('userData') 拿到的就是新目录了
  const baseDir = app.getPath('userData')
  const dir = profileDir(baseDir, PROFILE)

  app.setPath('userData', dir)
  try {
    // sessionData（cookies / localStorage / 缓存）默认跟随 userData，
    // 这里显式设一遍，是为了不依赖"默认跟随"这个行为
    app.setPath('sessionData', dir)
  } catch (e) {
    console.warn('[profile] 设置 sessionData 失败（可忽略，它会默认跟随 userData）:', e?.message)
  }
  console.log(`[profile] 使用独立数据目录: ${PROFILE} → ${dir}`)
} else {
  // 没设 profile 时也要大声打出来。
  // 多开却"还是同一个账号"最常见的原因，就是 profile 根本没传到 electron 上
  // （比如在 VS Code 的 NPM 脚本面板上点 ▶ —— 那不会带上你在终端里设的环境变量）。
  // 两个窗口如果都打印这一行"默认数据目录"，那就是它，而不是隔离逻辑没生效。
  console.log(`[profile] 未设置 profile，使用默认数据目录 → ${app.getPath('userData')}`)
}

/** 当前实例的运行身份：profile 名 + 数据目录 + 凭证文件路径（界面显示 / 排查多开问题用） */
function instanceInfo() {
  return {
    profile: PROFILE || null,
    userDataDir: app.getPath('userData'),
    credentialFile: credentialFilePath(),
  }
}

// 判断是否打包成应用；开发时 isPackaged = false
const isDev = !app.isPackaged

/** 主窗口引用：单聊的 WebSocket 模块要把收到的帧发给它 */
let mainWindow = null

function createWindow() {
  const win = new BrowserWindow({
    width: 1080,
    height: 720,
    // 标题缀上 profile：同时开两个账号时，任务栏里能分清哪个窗口是哪个
    title: PROFILE ? `Chat [${PROFILE}]` : 'Chat',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true, // 安全：渲染进程无法直接碰 Node
      nodeIntegration: false, // 安全：网页里禁用 Node
      /*
        关键：窗口失焦/被遮挡时【不要】节流这个页面。
        默认值是 true —— 那会让"收消息 → 落盘 → ack"这条链在后台停住
        （详细原因见文件顶部那段注释）。
      */
      backgroundThrottling: false,
    },
  })

  // 页面自己的 <title> 会覆盖掉构造参数里的标题，所以这里再缀一次，保证 profile 一直看得见
  if (PROFILE) {
    win.webContents.on('page-title-updated', (event, title) => {
      event.preventDefault()
      win.setTitle(`${title} [${PROFILE}]`)
    })
  }

  mainWindow = win
  win.on('closed', () => {
    mainWindow = null
  })

  // 开发模式加载 Vite 开发服务器（热更新）；打包后加载本地静态文件。
  //
  // ⚠️ 端口从环境变量读，别写死 5173：Vite 在 5173 被占用时会自动换端口（5174、5175…），
  //    而窗口还去加载 5173 的话，加载的是【另一个】dev server 的页面 ——
  //    那个页面是你之前那次启动留下的旧代码，你会以为"我改的东西怎么不生效"。
  //    真要换端口时这样启动：$env:VITE_DEV_SERVER_URL='http://localhost:5174'; npm run electron:dev
  if (isDev) {
    win.loadURL(process.env.VITE_DEV_SERVER_URL || 'http://localhost:5173')
  } else {
    win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
  }
}

// ---- 系统级能力：通过 ipcMain 暴露给渲染进程 ----

// 桌面通知
ipcMain.handle('notify', (event, { title, body }) => {
  if (Notification.isSupported()) {
    new Notification({ title, body }).show()
    return true
  }
  return false
})

// 运行环境版本（演示：证明网页能拿到主进程信息）
ipcMain.handle('get-versions', () => ({
  electron: process.versions.electron,
  chrome: process.versions.chrome,
  node: process.versions.node,
}))

// 当前实例的身份（profile 名 / 数据目录 / 凭证文件）。
// 同时开了多个客户端却"还是同一个账号"时先看这个：两个窗口必须显示不同的目录。
ipcMain.handle('app:instance', () => instanceInfo())

app.whenReady().then(() => {
  // 注册凭证保险箱的 IPC 通道（safeStorage 同样要求 app ready 之后才能用）
  registerCredentialHandlers(ipcMain)

  // 本地消息库：同样在 app ready 之后注册（它要 app.getPath('userData')，
  // 而那个路径在 ready 之前不可靠 —— 尤其是带 profile 时，setPath 必须已经生效）。
  // 它不依赖窗口，所以放 createWindow 之前之后都行，这里紧跟凭证保险箱，两个"落盘"能力挨在一起。
  registerMessageHandlers(ipcMain)

  createWindow()

  // 单聊 WebSocket：也注册在主进程里（原因见 chat-socket.cjs 顶部注释）。
  // 必须在 createWindow 之后注册 —— 它要把收到的帧发给那个窗口。
  registerChatHandlers(ipcMain, () => mainWindow)

  // macOS：点 Dock 图标且无窗口时重新建窗
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

// 除 macOS 外，所有窗口关闭即退出
app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
