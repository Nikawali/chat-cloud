// 好友请求与群邀请共用一个汇总请求，防止跨账号迟到响应和重复刷新。
export function createRequestReminderCounter({ owner, load, changed, now = Date.now }) {
  let generation = 0, account = '', inFlight = null, lastSuccess = null
  let summary = empty()
  function empty() { return { friendRequestCount: 0, groupInvitationCount: 0 } }
  function clear() {
    generation++; account = ''; inFlight = null; lastSuccess = null; summary = empty()
    changed(summary)
  }
  function refresh(force = false) {
    const current = owner()
    if (!current) { clear(); return Promise.resolve(summary) }
    if (current !== account) { clear(); account = current }
    if (inFlight) return force ? inFlight.catch(() => null).then(() => refresh(true)) : inFlight
    if (!force && lastSuccess != null && now() - lastSuccess < 2000) return Promise.resolve(summary)
    const version = generation
    const request = Promise.resolve().then(() => version === generation && current === owner() ? load() : null).then(value => {
      if (version !== generation || current !== owner()) return summary
      if (!value || ![value.friendRequestCount, value.groupInvitationCount].every(n => Number.isSafeInteger(n) && n >= 0)) {
        throw new Error('请求提醒汇总响应不合法')
      }
      summary = { friendRequestCount: value.friendRequestCount, groupInvitationCount: value.groupInvitationCount }
      lastSuccess = now(); changed(summary)
      return summary
    }).finally(() => { if (inFlight === request) inFlight = null })
    inFlight = request
    return request
  }
  return { refresh, clear }
}
