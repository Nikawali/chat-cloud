// Preload（CommonJS）：主进程与渲染进程之间的安全桥梁。
// 网页只能通过 window.electronAPI 使用这里暴露的能力，无法直接碰 Node。
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  // 桌面通知
  notify: (title, body) => ipcRenderer.invoke('notify', { title, body }),
  // 获取运行环境版本
  getVersions: () => ipcRenderer.invoke('get-versions'),
  // 当前实例的运行身份（profile 名 / 数据目录 / 凭证文件路径）。
  // 同时开多个客户端排查"为什么还是同一个账号"时就看这里。
  instance: () => ipcRenderer.invoke('app:instance'),

  // ---- 凭证保险箱（登录 token 的加密存储，实现见 credential-vault.cjs）----
  //
  // 只暴露"动作"，不暴露密钥、不暴露文件路径。
  // 渲染进程拿不到密文，也就无法离线爆破或搬到别的机器上复用。
  isEncryptionAvailable: () => ipcRenderer.invoke('credential:is-encryption-available'),
  saveCredential: (value) => ipcRenderer.invoke('credential:save', value),
  readCredential: () => ipcRenderer.invoke('credential:read'),
  clearCredential: () => ipcRenderer.invoke('credential:clear'),
  getExpiredFlag: () => ipcRenderer.invoke('credential:get-expired-flag'),
  setExpiredFlag: (on) => ipcRenderer.invoke('credential:set-expired-flag', on),

  // ---- 单聊的实时通道（WebSocket 建在主进程里，见 electron/chat-socket.cjs）----
  //
  // 渲染进程能做的只有三件：让主进程连/断、收主进程转来的帧、让主进程发一个帧（ack）。
  // 注意这里【没有】任何涉及 token 的方法 —— token 只活在主进程里。
  chat: {
    connect: () => ipcRenderer.invoke('chat:connect'),
    disconnect: () => ipcRenderer.invoke('chat:disconnect'),
    send: (frame) => ipcRenderer.invoke('chat:send', frame),
    /**
     * 订阅推送帧。
     * 返回一个"取消订阅"的函数 —— 页面卸载时必须调用，
     * 否则每次进聊天页都会多挂一个监听器，同一条消息会被处理好几遍（然后 ack 好几次）。
     */
    onFrame: (callback) => {
      const handler = (_event, frame) => callback(frame)
      ipcRenderer.on('chat:frame', handler)
      return () => ipcRenderer.removeListener('chat:frame', handler)
    },
  },

  // ---- 本地消息库（磁盘上的聊天记录，实现见 message-store.cjs）----
  //
  // 只暴露"按会话读 / 追加 / 推进回执水位"这几个动作，渲染进程看不到目录布局、拿不到文件句柄。
  //
  // 为什么不让渲染进程自己写 localStorage/IndexedDB：
  //   ① 浏览器存储按 origin 隔离，而 dev 是 http://localhost:5173、打包后是 file://，
  //      两者是【两份数据】—— 开发时存的东西打包后看不到；
  //   ② localStorage 只有几 MB，消息一多就满；
  //   ③ 主进程已经按 profile 隔离过 userData 了，跟着它走天然就是"一个账号一份"。
  messages: {
    group: (payload) => ipcRenderer.invoke('messages:group', payload),
    /**
     * 告诉本地库"现在登录的是谁"（幂等）。
     * 两个来源互补：WS 连上时 welcome 帧带的 userId、页面调 /user/me 拿到的 userId。
     * ⚠️ 换账号时它会把上一个人的本地消息清掉（服务端还有，登回去能重建）——
     * 不这么做，同一台机器上 A 登出、B 登录就会看到 A 的会话列表（串号）。
     */
    useAccount: (myUserId) => ipcRenderer.invoke('messages:use-account', myUserId),
    /**
     * 读某个会话的本地快照。
     *
     * 参数有两种写法，都支持：
     *   load(peerUserId)                                   —— 全量（老行为）
     *   load({ peerUserId, size, beforeMessageId })         —— 只取一页（往上翻页用）
     * 不传 size 就是全量，所以老的调用点不用改。
     */
    load: (peerUserIdOrOptions) => ipcRenderer.invoke('messages:load', peerUserIdOrOptions),
    /**
     * 追加一批【服务端确认过的】消息（按 messageId 去重，本地已有的跳过）。
     * ⚠️ messageId 为空的本地待发消息不要传进来 —— 那是 pendingMessages.js（发件箱）的活。
     */
    append: (payload) => ipcRenderer.invoke('messages:append', payload),
    /** 推进"已送达/已读"水位（回执帧只给 upToMessageId，所以存水位而不是每条的时间戳） */
    receipt: (payload) => ipcRenderer.invoke('messages:receipt', payload),
    /** 记下"这个会话我读到哪条了" —— 消息页的红点靠它算（只前进不后退） */
    read: (payload) => ipcRenderer.invoke('messages:read', payload),
    /** 本地会话列表（消息页秒开用；权威数据仍在服务端，见待办里的会话列表接口） */
    list: () => ipcRenderer.invoke('messages:list'),
    /** 清本地消息（不传 peerUserId = 全清）：排查"本地数据脏了"最快的一刀 */
    clear: (payload) => ipcRenderer.invoke('messages:clear', payload),
    /** 存储位置与规模（两个窗口显示不同目录，才说明多开隔离真的生效了） */
    info: () => ipcRenderer.invoke('messages:info'),
    /**
     * 从磁盘目录推断"上次用这台机器的是哪个账号"。
     * 断网 / 后端没起时用它把本地库打开 —— 否则本地库不知道 u<userId>/ 该用哪一层，
     * 只能 fail-closed（读一律当空），界面上就是"明明有历史却一片空白"。
     */
    lastAccount: () => ipcRenderer.invoke('messages:last-account'),
    /**
     * 把附件本体（图片/文件的字节）存到本地：{@code messages/u<我>/media/<文件名>}。
     * 服务端只保留 30 天，本地这份才是长期的那份。
     */
    saveMedia: (payload) => ipcRenderer.invoke('messages:save-media', payload),
    /** 读本地存着的附件本体（bytes 为 null = 本地没有，调用方去服务端重新取） */
    readMedia: (payload) => ipcRenderer.invoke('messages:read-media', payload),
  },
})
