// 两类请求共用这些事件触发，没有定时轮询。
export function bindRequestReminderTriggers({ refresh, subscribe, windowTarget, documentTarget }) {
  let active = true
  const foreground = () => { if (active && !documentTarget.hidden) refresh() }
  windowTarget.addEventListener('focus', foreground)
  documentTarget.addEventListener('visibilitychange', foreground)
  const unsubscribe = subscribe(event => {
    if (active && event.kind === 'status' && event.state === 'open') refresh()
  })
  refresh()
  return () => {
    active = false
    windowTarget.removeEventListener('focus', foreground)
    documentTarget.removeEventListener('visibilitychange', foreground)
    unsubscribe()
  }
}
