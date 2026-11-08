// 传输成功不等于受理成功；只有匹配的 send-result/code=200 才算受理。
export function createMessageSender({ send, accepted = () => {}, schedule = setTimeout, cancel = clearTimeout,
  resultType = 'send-result', validResult = data => data?.messageId != null && data?.conversationId != null }) {
  const pending = new Map()
  const waits = [500, 1000, 5000]
  function receive(frame) {
    if (frame?.type !== resultType) return
    const item = pending.get(frame.clientMessageId)
    if (!item) return
    if (frame.code === 200 && validResult(frame.data, item.frame)) {
      cancel(item.timer)
      pending.delete(frame.clientMessageId)
      item.resolve(frame.data)
      accepted(item.frame, frame.data)
    } else if (frame.code >= 400 && frame.code < 500) {
      cancel(item.timer)
      pending.delete(frame.clientMessageId)
      item.reject(Object.assign(new Error(frame.message || '发送被拒绝'), { code: frame.code }))
    }
  }
  function submit(frame) {
    const existing = pending.get(frame.clientMessageId)
    if (existing && !existing.expired) return existing.promise
    if (existing) cancel(existing.timer)
    const item = { frame, attempt: 0, expired: false }
    item.promise = new Promise((resolve, reject) => { item.resolve = resolve; item.reject = reject })
    pending.set(frame.clientMessageId, item)
    function attempt() {
      item.timer = schedule(() => {
        if (++item.attempt < waits.length) attempt()
        else {
          item.expired = true
          item.reject(new Error('发送结果未确认，请点击重试'))
          // 超时后接收迟到成功回执；不再自动发送，登出立即清理。
          item.timer = schedule(() => pending.delete(frame.clientMessageId), 60_000)
        }
      }, waits[item.attempt])
      Promise.resolve().then(() => {
        if (pending.get(frame.clientMessageId) === item && !item.expired) return send(frame)
      }).catch(() => {})
    }
    attempt()
    return item.promise
  }
  function reset() {
    for (const item of pending.values()) {
      cancel(item.timer)
      item.reject(new Error('登录状态已变化，发送已停止'))
    }
    pending.clear()
  }
  return { submit, receive, reset }
}
