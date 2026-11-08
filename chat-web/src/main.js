import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// Element Plus 的暗色变量。必须【在基础样式之后】引入，否则会被后面的默认变量覆盖。
// 它挂在 html.dark 上，所以"换主题"只是给 <html> 加/去一个 class（见 utils/theme.js）。
import 'element-plus/theme-chalk/dark/css-vars.css'
import './style.css'
import App from './App.vue'
import router from './router'
import { initCredentialStore, isSecureStorage, isLoggedIn } from './utils/auth'
import { loadIdentity } from './utils/accountData'
import { startChatSync } from './utils/chatSync'
// 副作用 import：把 Element Plus 的四个提示快捷方法统一成同一种外观（页面正中、红底白字）。
// 必须在这里、且在 createApp 之前引入 —— 晚于任何组件的 import 都可能让早期提示漏掉样式。
import './utils/toast'
import { initTheme } from './utils/theme'

// 挂载之前先把凭证读进来。
//
// 为什么必须等：Electron 下凭证加密存在主进程的文件里，读它是一次异步 IPC。
// 如果不等，页面渲染出来时 getToken() 还是空的，用户会看到"明明刚登录过却又
// 回到登录页"。等这一下的代价是启动时多几毫秒，换来的是全局同步取 token。
//
// 纯浏览器下 initCredentialStore() 直接返回 false，等于没开销。
//
// 用 async 自执行函数而不是顶层 await：顶层 await 要求 Vite 的 build.target
// 至少是 esnext，一旦以后有人把 target 调低就会突然构建失败。包一层更稳。
async function bootstrap() {
  // 主题要在【挂载之前】应用：偏好存在 localStorage，而 <html> 的 class 不会跟着刷新保留。
  // 放到挂载之后再应用的话，选了深色的人每次启动都会先看到一帧白屏。
  // 它是同步的（只读 localStorage + 改一个 class），不产生额外等待。
  initTheme()

  try {
    await initCredentialStore()
  } catch (e) {
    // 凭证存储出问题绝不能挡住应用启动，否则用户连登录页都看不到
    console.error('[chat] 初始化凭证存储失败，按未登录启动', e)
  }

  createApp(App).use(ElementPlus).use(router).mount('#app')

  if (isSecureStorage()) {
    console.info('[chat] 登录凭证由系统加密存储保管（safeStorage）')
  }

  // 已登录就把实时通道接上，并启动全局消息同步（连接建在 Electron 主进程里）。
  //
  // 为什么放在这里而不是"进聊天页才连"：
  //   · 收消息 + 回 ack 跟"你看的是哪个页面"无关 —— 只要你不打开那个会话，
  //     发送方就永远看不到"已送达"，所以这件事必须由 App 级来做；
  //   · 全局同步还负责"连上/重连/定时/切回前台"时的补拉（不丢消息的兜底）。
  //
  // 纯浏览器里这些调用都是空操作（没有主进程）。
  if (isLoggedIn()) {
    startChatSync()
    // 账号维度的内存缓存（我是谁 / 好友 / 申请）在这里拉一次：
    // 之后各页只读缓存，切页 0 请求。纯浏览器（没有 WS 的 welcome 帧）也靠它兜住。
    loadIdentity()
  }
}

bootstrap()
