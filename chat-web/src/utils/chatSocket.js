// 渲染进程侧的实时通道封装。
//
// 真正的 WebSocket 连接在【Electron 主进程】里（见 electron/chat-socket.cjs）——
// 因为浏览器的 WebSocket 不能自定义请求头，而握手要带 Authorization。
// 这里负责三件事：
//   ① 把主进程的 IPC 桥包装成干净的接口
//   ② 让全局只有【一条】连接，并提供一个"订阅帧"的机制（多个页面都可能要听）
//   ③ 处理"没有主进程"的情况（浏览器里跑 npm run dev）
//
// ⚠️ 为什么连接是"全局一条"而不是"进聊天页才连"：
//    消息推送不只聊天页要用 —— 会话列表的未读数、别的会话发来的消息，
//    都要求"App 一登录就一直在收"。进页面才连的话，你在联系人页时收到的消息
//    全都得靠上线补拉，实时推送就白做了。
//
// 没有主进程（浏览器里 npm run dev）时会怎样：
//    hasChatSocket 为 false，所有函数安静地什么都不做。
//    拉历史仍走 HTTP；发送消息需要桌面客户端 WS 通道。
//    刻意【不做】轮询兜底：多一条"看起来能用、行为却不一样"的路径，
//    以后排查问题时会怀疑人生。
//
// 关于 token：这个文件里【没有任何 token 相关的东西】——
//    主进程自己从保险箱里读，渲染进程连"我拿到过 token"这件事都不需要发生。

import { randomHex } from './random'
import { createMessageSender } from './messageSender'

let currentUserId = null
const receiptSender = createMessageSender({send:sendChatFrame,resultType:'group-receipt-result',
  validResult:(data,request) => data?.groupId === request.groupId && Number.isSafeInteger(data?.memberVersion) && Array.isArray(data?.members)})
export function reportGroupReceipt(frame) {
  if (!hasChatSocket) return Promise.reject(new Error('群回执需要桌面客户端'))
  ensureChatConnected()
  return receiptSender.submit({...frame,type:'group-receipt-report',clientMessageId:newClientMessageId()})
}
const groupSender = createMessageSender({
  send: sendChatFrame,
  resultType: 'group-send-result',
  validResult: (data, request) => Number.isSafeInteger(data?.messageId) && data.messageId > 0
    && Number.isSafeInteger(data?.seq) && data.seq > 0 && data.groupId === request.groupId
    && typeof data.createdAt === 'string' && Number.isFinite(Date.parse(data.createdAt)),
})
export function sendGroupChatMessage(groupId, content, clientMessageId, messageType=1) {
  if (!hasChatSocket) return Promise.reject(new Error('群消息发送需要桌面客户端'))
  ensureChatConnected()
  return groupSender.submit({type:'send-group-message',groupId:Number(groupId),content,clientMessageId,messageType})
}
const sender = createMessageSender({
  send: sendChatFrame,
  accepted: (request, data) => {
    for (const listener of listeners) {
      try { listener({ type: '__accepted', request, data, userId: currentUserId }) }
      catch (e) { console.error('[chat] 受理结果处理失败', e) }
    }
  },
})

export function sendChatMessage(toUserId, content, clientMessageId, messageType = 1) {
  if (!hasChatSocket) return Promise.reject(new Error('发送消息需要桌面客户端的 WebSocket 通道'))
  ensureChatConnected()
  return sender.submit({ type: 'send-message', toUserId, content, clientMessageId, messageType })
}

export const hasChatSocket =
  typeof window !== 'undefined' && !!window.electronAPI?.chat?.connect

/** 当前订阅者（页面/模块），收到帧时逐个回调 */
const listeners = new Set()
/** 主进程帧的取消订阅函数（只挂一次） */
let unsubscribeIpc = null
/** 是否已经让主进程去连了（幂等用） */
let connectRequested = false

/**
 * 确保连接已经建立（幂等，重复调用没副作用）。
 *
 * 调用时机：App 启动时如果已登录、以及每次登录成功之后。
 */
export function ensureChatConnected() {
  if (!hasChatSocket || connectRequested) {
    return
  }
  connectRequested = true

  // 先挂监听再 connect：反过来的话，连上瞬间到达的帧会丢
  unsubscribeIpc = window.electronAPI.chat.onFrame((frame) => {
    if (frame.type === 'welcome') {
      if (currentUserId != null && currentUserId !== frame.userId) { sender.reset(); groupSender.reset(); receiptSender.reset() }
      currentUserId = frame.userId
    }
    sender.receive(frame)
    groupSender.receive(frame)
    receiptSender.receive(frame)
    for (const listener of listeners) {
      try {
        listener(frame)
      } catch (e) {
        // 某个订阅者出错不能影响别人（也不能把通道搞挂）
        console.error('[chat] 处理推送帧出错', frame, e)
      }
    }
  })

  window.electronAPI.chat.connect().catch((e) => {
    console.error('[chat] 建立实时连接失败', e)
  })
}

/**
 * 订阅推送帧。
 *
 * @param {(frame: object) => void} onFrame
 * @returns {() => void} 取消订阅的函数 —— 页面卸载时调用（<b>不要</b>在这里断开连接）
 */
export function subscribeChat(onFrame) {
  ensureChatConnected()
  listeners.add(onFrame)
  return () => listeners.delete(onFrame)
}

/**
 * 换一条新连接（<b>登录成功之后必须调</b>）。
 *
 * 为什么要重连：握手时带的是"当时那个 token"。用户重新登录会拿到新 token，
 * 而旧 token 已经被服务端删掉了 —— 那条连接虽然还开着，但它代表的是一个已经失效的身份。
 * 所以登录成功后必须先断开再连。
 */
export function restartChatConnection() {
  groupSender.reset(); receiptSender.reset()
  sender.reset()
  currentUserId = null
  if (!hasChatSocket) {
    return
  }
  window.electronAPI.chat.disconnect()
    .catch(() => {})
    .finally(() => {
      connectRequested = false
      if (unsubscribeIpc) unsubscribeIpc()
      unsubscribeIpc = null
      ensureChatConnected()
    })
}

/** 断开并停止重连（登出时调） */
export function disconnectChatPermanently() {
  groupSender.reset(); receiptSender.reset()
  sender.reset()
  currentUserId = null
  if (!hasChatSocket) {
    return
  }
  connectRequested = false
  if (unsubscribeIpc) {
    unsubscribeIpc()
    unsubscribeIpc = null
  }
  window.electronAPI.chat.disconnect().catch(() => {})
}

/**
 * 发一个帧给服务端（仅确认传输提交；业务受理由 sendChatMessage 等待 send-result）。
 *
 * <p>返回值是 promise，但调用方通常不需要 await —— ack 发失败也没关系：
 * 服务端收不到 ack 会重推，客户端下次收到同一条消息时会再 ack 一次（服务端幂等）。
 */
export function sendChatFrame(frame) {
  if (!hasChatSocket) {
    return Promise.resolve({ ok: false, reason: 'no-ipc' })
  }
  return window.electronAPI.chat.send(frame)
}

/**
 * 生成 clientMessageId（发消息时的幂等 id）。
 *
 * <p>它必须是<b>不可预测且不会撞车</b>的随机值：服务端拿它 + (sender, clientMessageId)
 * 唯一索引保证"同一条消息只落一行"。随机数怎么取（为什么不用 Math.random / randomUUID）
 * 统一在 utils/random.js 里说明。
 *
 * <p>拼成 8-4-4-4-12 的 UUID 形状只是为了"看起来像个 id"，
 * 长度 36 < 服务端 varchar(64) 的限制。
 */
export function newClientMessageId() {
  const hex = randomHex(16)
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
