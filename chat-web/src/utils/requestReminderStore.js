import { ref } from 'vue'
import { api } from '../api'
import { getToken } from './auth'
import { onChatEvent } from './chatSync'
import { createRequestReminderCounter } from './requestReminderCounter'
import { bindRequestReminderTriggers } from './requestReminderTriggers'

export const pendingFriendRequestCount = ref(0)
export const pendingGroupInvitationCount = ref(0)
export const friendRequests = ref([])
const counter = createRequestReminderCounter({
  owner: getToken,
  load: () => api.requestReminderSummary(),
  changed: summary => {
    pendingFriendRequestCount.value = summary.friendRequestCount
    pendingGroupInvitationCount.value = summary.groupInvitationCount
  },
})
let started = false, unsubscribe = null, generation = 0, detailsRequest = null

export function refreshRequestReminders(force = false) {
  return counter.refresh(force).catch(e => {
    console.warn('[request-reminders] 更新提醒失败，保留上次结果', e)
    return { friendRequestCount: pendingFriendRequestCount.value, groupInvitationCount: pendingGroupInvitationCount.value }
  })
}
export function resetRequestReminders() {
  generation++; detailsRequest = null; friendRequests.value = []; counter.clear()
}
export function refreshFriendRequests(refreshSummary = true) {
  const account = getToken(), version = generation
  if (!account) { resetRequestReminders(); return Promise.resolve([]) }
  if (detailsRequest?.account === account) return detailsRequest.promise
  const state = { account, promise: null }
  state.promise = Promise.all([
    api.listFriendRequests().then(rows => {
      if (version === generation && account === getToken()) friendRequests.value = rows || []
      return friendRequests.value
    }).catch(e => { console.warn('[request-reminders] 获取好友请求失败', e); return friendRequests.value }),
    refreshSummary ? refreshRequestReminders(true) : Promise.resolve(),
  ]).then(([rows]) => rows).finally(() => { if (detailsRequest === state) detailsRequest = null })
  detailsRequest = state
  return state.promise
}
export function startRequestReminders() {
  if (started) return
  started = true
  unsubscribe = bindRequestReminderTriggers({ refresh: refreshRequestReminders, subscribe: onChatEvent,
    windowTarget: window, documentTarget: document })
}
export function stopRequestReminders() {
  started = false
  unsubscribe?.(); unsubscribe = null
  resetRequestReminders()
}
