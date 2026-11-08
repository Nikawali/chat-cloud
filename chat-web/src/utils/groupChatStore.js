import { shallowRef, computed } from 'vue'
import { getToken, getSession } from './auth'
import { newClientMessageId, sendGroupChatMessage, subscribeChat, reportGroupReceipt } from './chatSocket'
import { api } from '../api'
import { saveLocalMedia } from './messageStore'
import { assertAttachmentFile } from './attachmentUpload'

const sessions = shallowRef(new Map())
const inFlight = new Map()
const metadataRequests = new Map()
const receiptTasks = new Map(), receiptTimers = new Map(), receiptAttempts = new Map()
const queryTasks = new Map(), queriedAt = new Map()
let reconnectTask = null
let pulling = null
// Reuses single-chat reconnect/foreground/60s triggers. Always starts from this SQLite database's contiguous position.
export async function syncGroupMessages() {
  if (!currentAccount() || !bridge()) return
  if (pulling) return pulling
  listen()
  const token=account
  const task=(async()=>{
    let after=0
    const ids=[]
    for (;;) {
      const heads=await api.groupSyncHeads(after)
      if (token!==account || token!==getToken()) return
      if (!Array.isArray(heads)) throw new Error('群列表不合法')
      if (!heads.length) return ids
      for (const head of heads) {
        try {
          if (!Number.isSafeInteger(head.groupId) || head.groupId<=after) throw new Error('群列表分页没有前进')
          const id=head.groupId
          ids.push(id)
          await operation({op:'open',groupId:id,group:head},token)
          const old=groupChatSession(id)?.receiptState
          await operation({op:'receipt-state',groupId:id,data:{...head,members:old?.memberVersion===head.memberVersion ? old.members : []},partial:true},token)
          for (let page=0;page<20;page++) {
            const cursor=await operation({op:'cursors',groupId:id},token)
            if (cursor.receivedSeq>=head.lastSeq) break
            const result=await api.pullGroupMessages(id,head.memberVersion,cursor.receivedSeq)
            if (token!==account || token!==getToken()) return
            if (result.groupId!==id || result.memberVersion!==head.memberVersion || !Array.isArray(result.messages)) throw new Error('群补拉结果不匹配')
            await operation({op:'receive-batch',groupId:id,messages:result.messages},token)
            const next=await operation({op:'cursors',groupId:id},token)
            if (next.receivedSeq<=cursor.receivedSeq) throw new Error('群补拉仍有缺口，等待下轮同步')
          }
          scheduleReceipt(id)
        } catch(e) {
          if (token!==account || token!==getToken()) return
          console.warn('[group-chat] 补拉失败，保留本地游标等待重试',head.groupId,e)
        }
      }
      const next=heads.at(-1).groupId
      if (!Number.isSafeInteger(next) || next<=after) throw new Error('群列表分页没有前进')
      after=next
      if (heads.length<100) return ids
    }
  })()
  pulling=task
  try {return await task} finally {if(pulling===task) pulling=null}
}
function scheduleReceipt(id,delay=250) {
  id=Number(id)
  if (receiptTimers.has(id)) {
    if(delay<=250) return
    clearTimeout(receiptTimers.get(id));receiptTimers.delete(id)
  }
  const token=account
  receiptTimers.set(id,setTimeout(()=>{
    receiptTimers.delete(id)
    if(token!==account || token!==getToken()) return
    syncGroupReceipt(id).catch(e=>console.warn('[group-chat] 游标未确认，稍后重试',e))
  },delay))
}
// Query snapshots only for an opened group, or all groups on reconnect.
export async function queryGroupReceipt(id,ownOnly=false,force=false) {
  id=Number(id)
  if(!currentAccount() || !groupChatSession(id)) return
  const key=id+':'+ownOnly
  if(queryTasks.has(key)) return queryTasks.get(key)
  if(!force && !ownOnly && Date.now()-(queriedAt.get(id)||0)<2000) return
  const token=account
  const task=(async()=>{
    const local=await operation({op:'cursors',groupId:id},token)
    const state=await reportGroupReceipt({groupId:id,deviceId:local.deviceId,ownOnly})
    await operation({op:'receipt-state',groupId:id,data:state,partial:ownOnly},token)
    if(!ownOnly) queriedAt.set(id,Date.now())
    scheduleReceipt(id)
  })()
  queryTasks.set(key,task)
  try {await task} finally {if(queryTasks.get(key)===task) queryTasks.delete(key)}
}
export async function refreshAllGroupReceipts() {
  if(!currentAccount()) return
  if(reconnectTask) return reconnectTask
  const token=account
  const task=(async()=>{
    await restoreGroupSessions()
    let ids
    try {ids=await syncGroupMessages()} catch(e) {console.warn('[group-chat] 重连获取群列表失败，先对账缓存群',e)}
    for(const id of ids || [...sessions.value.values()].map(s=>s.group.groupId)) {
      if(token!==account || token!==getToken()) return
      try {await queryGroupReceipt(id,false,true);scheduleReceipt(id)} catch(e) {console.warn('[group-chat] 重连群水位对账失败',id,e)}
    }
  })()
  reconnectTask=task
  try {await task} finally {if(reconnectTask===task) reconnectTask=null}
}
// Global progress reporting is independent of the current page. Confirmed positions survive restart.
export async function syncGroupReceipt(id) {
  id=Number(id)
  if(!currentAccount() || !groupChatSession(id)) return
  if(receiptTasks.has(id)) {scheduleReceipt(id);return receiptTasks.get(id)}
  const token=account
  const task=(async()=>{
    try {
      let next=await operation({op:'cursors',groupId:id},token)
      if(!next.memberVersion) {
        await queryGroupReceipt(id,true)
        next=await operation({op:'cursors',groupId:id},token)
      }
      if(next.receivedSeq<=next.confirmedReceivedSeq && next.readSeq<=next.confirmedReadSeq) return
      const {deviceId,memberVersion,receivedSeq,readSeq}=next
      const state=await reportGroupReceipt({groupId:id,deviceId,memberVersion,receivedSeq,readSeq})
      await operation({op:'receipt-state',groupId:id,data:state,partial:true},token)
      await operation({op:'confirm-receipt',groupId:id,cursors:next},token)
      receiptAttempts.delete(id)
    } catch(e) {
      if(token===account && token===getToken() && e.code!==403) {
        if(e.code===409) await queryGroupReceipt(id,true).catch(()=>{})
        const attempt=(receiptAttempts.get(id)||0)+1
        receiptAttempts.set(id,attempt)
        scheduleReceipt(id,Math.min(30000,1000*2**Math.min(attempt-1,5)))
      }
      throw e
    }
  })()
  receiptTasks.set(id,task)
  try {await task} finally {if(receiptTasks.get(id)===task) receiptTasks.delete(id)}
}
let account = '', unsubscribe = null, restoring = null
export const groupConversations = computed(() => [...sessions.value.values()])
export const groupUnreadTotal = computed(() => groupConversations.value.reduce((sum,s) => sum + (Number(s.unreadCount) || 0),0))
export async function markGroupMessagesRead(id) {
  if (!currentAccount()) return
  const session = groupChatSession(id)
  if (!session?.loaded || !session.unreadCount) return
  const upToSeq = session.messages.reduce((max,m)=>Math.max(max,m.seq || 0),0)
  await operation({op:'local-read',groupId:Number(id),upToSeq})
}
const owner = () => Number(getSession()?.userId)
const bridge = () => window.electronAPI?.messages?.group
export function resetGroupChatSessions() {
  unsubscribe?.(); unsubscribe = null
  sessions.value = new Map(); account = ''; restoring = null; inFlight.clear()
  metadataRequests.clear(); pulling=null
  for (const timer of receiptTimers.values()) clearTimeout(timer)
  receiptTimers.clear(); receiptTasks.clear(); receiptAttempts.clear()
  queryTasks.clear(); queriedAt.clear(); reconnectTask=null
}
function currentAccount() {
  const token = getToken()
  if (token !== account) { resetGroupChatSessions(); account = token }
  return !!token
}
function put(id, session) {
  const next = new Map(sessions.value); next.set(String(id), session); sessions.value = next
}
function merge(session, older = false) {
  if (!session) return
  const previous = sessions.value.get(String(session.group.groupId))
  // 重启后的 sending 代表结果未知；保留原 ID，供用户重试确认。
  session.messages = session.messages.map(m => m.mine && m.status === 'sending' && !inFlight.has(m.clientMessageId)
    ? {...m,status:'failed',error:'上次发送结果未确认，请点击重试'} : m)
  if(session.delta || older) {
    const rows=new Map((previous?.messages || []).map(m=>[m.key,m]))
    const oldest=previous?.messages.find(m=>m.seq!=null)?.seq
    for(const m of session.messages) {
      if(!older && previous?.hasMore && m.seq!=null && m.seq<oldest && !rows.has(m.key)) continue
      rows.set(m.key,m)
    }
    session.messages=[...rows.values()].sort((a,b)=>(a.seq ?? Infinity)-(b.seq ?? Infinity) || a.createdAt.localeCompare(b.createdAt))
    session.loaded=previous?.loaded || false
    if(!older) session.hasMore=previous?.hasMore || false
    if(!session.loaded) {
      const accepted=session.messages.filter(m=>m.seq!=null)
      session.hasMore ||= accepted.length>30
      session.messages=[...accepted.slice(-30),...session.messages.filter(m=>m.seq==null)]
    }
  }
  put(session.group.groupId, {...session,draft:previous?.draft || ''})
}
async function operation(payload, token = account) {
  if (!token || token !== getToken() || token !== account) throw new Error('登录状态已变化')
  if (!bridge()) throw new Error('群消息需要桌面客户端的本地消息库')
  const response = await bridge()({...payload,ownerUserId:owner()})
  if (token !== getToken() || token !== account) throw new Error('登录状态已变化')
  if (!response?.ok) throw new Error(response?.reason || '本地消息保存失败')
  if (response.session) merge(response.session, !!payload.beforeSeq)
  if (['receive','receive-batch','accept','local-read'].includes(payload.op)) scheduleReceipt(payload.groupId)
  return response
}
function listen() {
  if (unsubscribe) return
  unsubscribe = subscribeChat(frame => {
    if (account !== getToken()) return
    if (frame?.type === 'welcome') {
      refreshAllGroupReceipts().catch(e=>console.warn('[group-chat] 重连对账失败',e))
    }
    if (frame?.type === 'group-receipt') {
      const state = groupChatSession(frame.groupId)?.receiptState
      if (state) {
        operation({op:'receipt-state',groupId:frame.groupId,data:{...state,members:[frame.data]},partial:true}).catch(e=>console.warn('[group-chat] 回执保存失败',e))
      }
      return
    }
    // 页面切走或发送超时后，迟到的成功受理仍要落盘。
    if (frame?.type !== 'group-send-result' || frame.code !== 200 || account !== getToken()) return
    const session = sessions.value.get(String(frame.data?.groupId))
    if (!session?.messages.some(m => m.mine && m.clientMessageId === frame.clientMessageId)) return
    operation({op:'accept',groupId:frame.data.groupId,clientMessageId:frame.clientMessageId,data:frame.data})
      .catch(e => console.warn('[group-chat] 保存受理结果失败，保留原 ID 供重试',e))
  })
}
export async function restoreGroupSessions() {
  if (!currentAccount() || !Number.isSafeInteger(owner()) || owner() <= 0 || !bridge()) return
  if (restoring) return restoring
  listen()
  const task = operation({op:'list'}).then(result => {
    for (const session of result.sessions || []) {
      if (!sessions.value.has(String(session.group.groupId))) merge(session)
    }
  })
  restoring = task
  try { await task } finally { if (restoring === task) restoring = null }
}
// 全局 WS 分发入口调用，不依赖群聊页面挂载。落盘成功后合并上报连续群游标。
export async function receiveGroupMessage(message) {
  if (!currentAccount()) return
  listen()
  const token = account
  const result = await operation({op:'receive',groupId:message?.groupId,message},token)
  const group = result.session?.group
  if (group?.metadataPending && (!metadataRequests.has(group.groupId) || Date.now() - metadataRequests.get(group.groupId) > 60000)) {
    metadataRequests.set(group.groupId,Date.now())
    api.groupDetail(group.groupId).then(detail => {
      if (token !== getToken() || token !== account) return
      return operation({op:'open',groupId:group.groupId,group:detail},token)
    }).catch(e => console.warn('[group-chat] 群资料暂时不可用，消息已保存',e))
  }
  return { inserted:result.inserted, mine:message.senderUserId === owner() }
}
export function openGroupConversation(group) {
  if (!currentAccount() || !Number.isSafeInteger(Number(group?.groupId)) || Number(group.groupId) <= 0 || !group.memberRole) return false
  const previous = sessions.value.get(String(group.groupId))
  put(group.groupId,{...previous,group:{...group},messages:previous?.messages || [],draft:previous?.draft || '',
    unreadCount:previous?.unreadCount || 0,loaded:previous?.loaded || false,openedAt:previous?.openedAt || new Date().toISOString()})
  return true
}
export async function loadGroupConversation(group) {
  if (!openGroupConversation(group)) throw new Error('无法打开群会话')
  listen()
  await operation({op:'open',groupId:Number(group.groupId),group})
  await loadCachedGroupConversation(group.groupId)
  scheduleReceipt(Number(group.groupId))
}
export async function loadCachedGroupConversation(id) {
  if (!currentAccount() || !bridge()) return
  listen()
  await operation({op:'load',groupId:Number(id)})
}
export async function loadOlderGroupMessages(id) {
  if(!currentAccount()) return
  const session=groupChatSession(id)
  const beforeSeq=session?.messages.find(m=>m.seq!=null)?.seq
  if(!session?.hasMore || !beforeSeq) return
  await operation({op:'load',groupId:Number(id),beforeSeq})
}
export function groupChatSession(id) {
  const current = sessions.value
  return account && account === getToken() ? current.get(String(id)) || null : null
}
export function setGroupDraft(id,draft) {
  if (!currentAccount()) return
  const session = sessions.value.get(String(id))
  if (session) put(id,{...session,draft})
}
export function sendGroupText(id,content,sender,retryId) {return submitGroupMessage(id,content,sender,retryId)}
export function sendGroupAttachment(id,file,sender) {
  assertAttachmentFile(file)
  return submitGroupMessage(id,'',sender,null,file)
}
async function submitGroupMessage(id, content, sender, retryId, file) {
  if (!currentAccount()) throw new Error('请先登录')
  const token = account, groupId = Number(id), session = groupChatSession(id)
  if (!session || sender?.userId !== owner()) throw new Error('发送身份尚未确认')
  const existing = retryId ? session.messages.find(m => m.mine && m.clientMessageId === retryId) : null
  if (retryId && !existing) throw new Error('待重试消息不存在')
  if (existing?.status === 'sent') return
  const key = existing?.clientMessageId || newClientMessageId()
  if (inFlight.has(key)) return inFlight.get(key)
  const text = existing?.content ?? content.trim()
  if (!file && (!text || text.length > 2000)) throw new Error('请输入不超过2000字的内容')
  let resolve, reject
  const task = new Promise((yes,no) => {resolve=yes;reject=no})
  inFlight.set(key,task)
  ;(async () => {
    let saved = false
    try {
      // 先存发件箱，再联网；崩溃后仍有原文和同一幂等 ID。
      if(file) {
        const bytes=new Uint8Array(await file.arrayBuffer())
        await operation({op:'put-upload',groupId,clientMessageId:key,bytes,message:{senderUserId:sender.userId,senderName:sender.nickName,avatarUrl:sender.avatarUrl,fileName:file.name,mimeType:file.type,messageType:file.type?.startsWith('image/')?2:3}},token)
      } else if(!existing?.uploadPending) await operation({op:'put',groupId,clientMessageId:key,message:{
        content:text,messageType:existing?.messageType || 1,senderUserId:sender.userId,senderName:sender.nickName || '我',avatarUrl:sender.avatarUrl || null,
      }},token)
      saved = true
      await operation({op:'status',groupId,clientMessageId:key,status:'sending'},token)
      if (!file && !retryId && groupChatSession(id)?.draft === content) setGroupDraft(id,'')
      let message=groupChatSession(id).messages.find(m=>m.mine && m.clientMessageId===key)
      if(message.uploadPending) {
        const stored=await operation({op:'upload-bytes',groupId,clientMessageId:key},token)
        const uploadFile=new File([stored.bytes],message.fileName,{type:message.mimeType})
        const uploaded=await api.uploadAttachment(uploadFile)
        if(token!==account || token!==getToken()) throw new Error('登录状态已变化')
        if(![2,3].includes(uploaded?.messageType) || typeof uploaded.url!=='string') throw new Error('附件上传结果不合法')
        // Cache our own bytes before sending; rendering uses the same local media directory as single chat.
        const name=uploaded.url.split('?')[0].split('/').pop()
        await saveLocalMedia(name,stored.bytes)
        await operation({op:'upload-ready',groupId,clientMessageId:key,uploaded},token)
        message=groupChatSession(id).messages.find(m=>m.mine && m.clientMessageId===key)
      }
      const data = await sendGroupChatMessage(groupId,message.content,key,message.messageType || 1)
      await operation({op:'accept',groupId,clientMessageId:key,data},token)
      resolve(data)
    } catch (e) {
      if (saved && token === getToken() && token === account) {
        try { await operation({op:'status',groupId,clientMessageId:key,status:'failed',error:e.message},token) }
        catch {
          const s = groupChatSession(id)
          if (s) put(id,{...s,messages:s.messages.map(m => m.mine && m.clientMessageId === key && m.status !== 'sent'
            ? {...m,status:'failed',error:'本地保存失败，请使用原消息重试'} : m)})
        }
      }
      reject(e)
    } finally { if (inFlight.get(key) === task) inFlight.delete(key) }
  })()
  return task
}
