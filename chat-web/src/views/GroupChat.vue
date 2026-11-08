<template>
  <div class="group-chat-page">
    <ChatHeader :title="group?.groupName || '群聊'" :subtitle="group ? `${group.memberCount} 位成员 · 群号 ${group.groupId}` : ''"
      :items="menuItems" @back="goBack" @command="onMenu" />
    <div class="preview-notice"><el-icon><InfoFilled /></el-icon><span>已支持群聊文本收发与本地保存，重连或回到前台时自动同步。</span></div>
    <div v-if="loadError && messages.length" class="preview-notice"><span>{{ loadError }}，当前显示本地记录。</span><el-button text size="small" @click="loadGroup">重试</el-button></div>
    <main ref="bodyRef" class="chat-body" @scroll.passive="loadOlder">
      <div v-if="loading" class="group-loading"><el-skeleton :rows="4" animated /></div>
      <div v-else-if="loadError && !messages.length" class="group-chat-empty"><el-icon><Warning /></el-icon><h3>暂时无法打开群聊</h3><p>{{ loadError }}</p><el-button @click="loadGroup">重新加载</el-button><el-button text @click="manageGroup">返回群聊列表</el-button></div>
      <div v-else-if="!group?.memberRole && !messages.length" class="group-chat-empty"><el-icon><Lock /></el-icon><h3>加入群聊后再来聊天</h3><p>你目前不是这个群的有效成员。</p><el-button type="primary" @click="manageGroup">前往群聊列表</el-button></div>
      <div v-else-if="!messages.length" class="group-chat-empty"><span class="group-empty-avatar">{{ group.groupName?.slice(0, 1) }}</span><h3>{{ group.groupName }}</h3><p>和大家的下一段对话，从这里开始。</p><small>发送的消息会保存在这台设备上。</small></div>
      <template v-else>
        <div class="body-spacer" />
        <template v-for="message in renderItems" :key="message.key">
          <div v-if="message.kind === 'time'" class="msg-time">{{ message.text }}</div>
          <ChatMessageRow v-else :mine="message.mine" :avatar-url="senderAvatar(message)" :avatar-name="senderName(message)"
            :sender-name="message.mine ? '' : senderName(message)" :status="receiptStatus(message)" :status-text="receiptText(message)"
            :error="message.error" :retryable="!loadError && !!group?.memberRole" :media="message.messageType===2 || message.messageType===3" @retry="retryMessage(message)"><ChatMessageContent :message="message" :media="media" /></ChatMessageRow>
        </template>
      </template>
    </main>
    <ChatComposer v-model="draft" :disabled="loading || !!loadError || !group?.memberRole" :maxlength="2000"
      :sending="sending" placeholder="输入群消息…（回车发送，Shift+回车换行）" @send="sendMessage" @attachment="onAttachCommand"><input ref="fileInputRef" type="file" class="file-input" @change="onAttachmentPicked" /></ChatComposer>

    <el-drawer v-model="infoVisible" class="chat-details-drawer" title="群聊资料" size="min(380px, 92vw)">
      <div v-if="group" class="group-details">
        <div class="details-identity"><el-avatar :size="64" :src="assetUrl(group.avatarUrl)">{{ group.groupName?.slice(0, 1) }}</el-avatar><h3>{{ group.groupName }}</h3><p>群号 {{ group.groupId }}</p><el-tag effect="plain">{{ roleName(group.memberRole) }}</el-tag></div>
        <el-button v-if="group.memberRole >= 2" class="manage-button" plain type="primary" @click="manageGroup(true)">邀请好友 / 管理群聊</el-button>
        <div class="member-heading"><h4>群成员</h4><span>{{ group.memberCount }} 人</span></div>
        <div v-if="memberError" class="member-error"><p>{{ memberError }}</p><el-button size="small" @click="loadMembers(true)">重试</el-button></div>
        <el-skeleton v-if="membersLoading && !memberList.length" :rows="3" animated />
        <div v-for="member in memberList" :key="member.userId" class="group-member-row"><el-avatar :size="34" :src="assetUrl(member.avatarUrl)">{{ firstChar(member.nickName) }}</el-avatar><span>{{ member.nickName }}</span><small>{{ roleName(member.memberRole) }}</small></div>
        <el-button v-if="moreMembers" :loading="membersLoading" @click="loadMembers(false)">加载更多成员</el-button>
      </div>
    </el-drawer>
  </div>
</template>
<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { InfoFilled, Warning, Lock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import ChatHeader from '../components/ChatHeader.vue'
import ChatComposer from '../components/ChatComposer.vue'
import ChatMessageRow from '../components/ChatMessageRow.vue'
import ChatMessageContent from '../components/ChatMessageContent.vue'
import { useMessageMedia } from '../utils/useMessageMedia'
import { assertAttachmentFile,configureAttachmentInput } from '../utils/attachmentUpload'
import { buildMessageTimeline } from '../utils/messageTimeline'
import '../styles/chatMessages.css'
import { api, assetUrl } from '../api'
import { me, ensureMe } from '../utils/accountData'
import { useLocalAccount } from '../utils/messageStore'
import { getToken } from '../utils/auth'
import { firstChar } from '../utils/display'
import { groupChatSession, loadGroupConversation, loadCachedGroupConversation, loadOlderGroupMessages, setGroupDraft, sendGroupText, sendGroupAttachment, markGroupMessagesRead, queryGroupReceipt } from '../utils/groupChatStore'
import { createForegroundRefresh } from '../utils/foregroundRefresh'
import { isWindowFocused } from '../utils/env'
import { useBack } from '../utils/navigation'

const props = defineProps({ groupId: {type:String,required:true} })
const router = useRouter(), goBack = useBack('message')
const group = ref(groupChatSession(props.groupId)?.group || null), loading = ref(true), loadError = ref('')
const infoVisible = ref(false), memberList = ref([]), membersLoading = ref(false), memberError = ref(''), moreMembers = ref(false), bodyRef = ref(null)
const sending = ref(false),fileInputRef=ref(null)
const media=useMessageMedia(bodyRef)
const statusName = status => ({sending:'发送中…',sent:'已发送',failed:'发送失败或结果未确认'}[status] || '未确认')
const senderName = message => message.senderName || (message.mine ? me.value?.nickName || '我' : memberList.value.find(m => m.userId === message.senderUserId)?.nickName || `用户 ${message.senderUserId}`)
function receiptCounts(message) {
  const members = groupChatSession(props.groupId)?.receiptState?.members || []
  const peers = members.filter(m => m.userId !== message.senderUserId && m.visibleFromSeq <= message.seq)
  return { delivered:peers.filter(m => m.receivedSeq >= message.seq).length, read:peers.filter(m => m.readSeq >= message.seq).length }
}
function receiptStatus(message) {
  if (message.status !== 'sent') return message.status
  const counts = receiptCounts(message)
  return counts.read ? 'read' : counts.delivered ? 'delivered' : 'sent'
}
function receiptText(message) {
  if(message.uploadPending && message.status==='sending') return '上传中…'
  if (message.status !== 'sent') return statusName(message.status)
  const counts = receiptCounts(message)
  return counts.delivered || counts.read ? counts.delivered + ' 人已送达 · ' + counts.read + ' 人已读' : '已发送'
}
const messages = computed(() => groupChatSession(props.groupId)?.messages || [])
const renderItems = computed(() => buildMessageTimeline(messages.value))
const senderAvatar = message => assetUrl(message.avatarUrl || (message.mine ? me.value?.avatarUrl : memberList.value.find(m => m.userId === message.senderUserId)?.avatarUrl))
const draft = computed({ get: () => groupChatSession(props.groupId)?.draft || '', set: value => setGroupDraft(props.groupId, value) })
const menuItems = [{command:'info',label:'群聊资料与成员'}, {command:'groups',label:'返回群聊列表'}]
const account = getToken()
let loadingOlder = false
async function loadOlder() {
  const body=bodyRef.value
  if(loading.value || loadingOlder || !body || body.scrollTop>80 || !groupChatSession(props.groupId)?.hasMore) return
  loadingOlder=true
  const height=body.scrollHeight, top=body.scrollTop
  try {
    await loadOlderGroupMessages(props.groupId)
    await nextTick()
    if(valid()) body.scrollTop=top+body.scrollHeight-height
  } catch(e) {if(valid()) ElMessage.error(e?.message || '历史消息加载失败')}
  finally {loadingOlder=false}
}
let alive = true
const valid = () => alive && account === getToken()
const receiptRefresh=createForegroundRefresh({refresh:()=>queryGroupReceipt(props.groupId),
  active:()=>valid() && !loading.value && !!group.value?.memberRole && isWindowFocused(),
  onError:e=>console.warn('[group-chat] 当前群水位查询失败',e)})
const roleName = role => ({1:'成员',2:'管理员',3:'群主'}[role] || '未加入')

async function loadGroup() {
  if (!valid()) return
  loading.value = true; loadError.value = ''
  try {
    const id = Number(props.groupId)
    if (!Number.isSafeInteger(id) || id <= 0) throw new Error('群号不合法')
    await ensureMe()
    if (!valid()) return
    if (me.value?.userId) await useLocalAccount(me.value.userId)
    if (!valid()) return
    await loadCachedGroupConversation(props.groupId)
    if (!valid()) return
    group.value = groupChatSession(props.groupId)?.group || group.value
    const result = await api.groupDetail(props.groupId)
    if (!valid()) return
    group.value = result
    if (result.memberRole) {
      await loadGroupConversation(result)
      if (infoVisible.value && !memberList.value.length) loadMembers(true)
    }
  } catch (e) { if (valid()) loadError.value = e?.message || '加载群资料失败' }
  finally { if (valid()) { loading.value = false;receiptRefresh.start();receiptRefresh.refresh() } }
}
async function loadMembers(reset = false) {
  if (membersLoading.value || !group.value?.memberRole) return
  membersLoading.value = true; memberError.value = ''
  try {
    const rows = await api.groupMembers(props.groupId, reset ? 0 : memberList.value.at(-1)?.userId || 0)
    if (!valid()) return
    memberList.value = reset ? rows : [...memberList.value, ...rows]
    moreMembers.value = rows.length === 100
  } catch (e) { if (valid()) memberError.value = e?.message || '加载成员失败' }
  finally { if (valid()) membersLoading.value = false }
}
function onMenu(command) {
  if (command === 'groups') manageGroup()
  else if (command === 'info') { infoVisible.value = true; if (!memberList.value.length) loadMembers(true) }
}
function manageGroup(openDetails = false) {
  router.push({name:'contacts',query:{section:'groups',groupTab:'mine', ...(openDetails === true && group.value?.memberRole >= 2 ? {groupId:props.groupId} : {})}})
}
function onAttachCommand(kind) {configureAttachmentInput(fileInputRef.value,kind)}
async function onAttachmentPicked(event) {
  const file=event.target?.files?.[0]
  event.target.value=''
  if(!file || !valid() || sending.value || loading.value || loadError.value || !group.value?.memberRole) return
  try {
    assertAttachmentFile(file);sending.value=true
    await sendGroupAttachment(props.groupId,file,me.value)
  } catch(e) {if(valid()) ElMessage.error(e?.message || '附件发送失败')}
  finally {if(valid()) sending.value=false}
}
async function sendMessage() {
  if (!valid() || sending.value || loading.value || loadError.value || !group.value?.memberRole) return
  sending.value = true
  try { await sendGroupText(props.groupId, draft.value, me.value) }
  catch (e) { if (valid()) ElMessage.error(e?.message || '发送失败，请重试') }
  finally { if (valid()) sending.value = false }
}
async function retryMessage(message) {
  if (!valid() || loadError.value || !group.value?.memberRole) return
  try { await sendGroupText(props.groupId, message.content, me.value, message.clientMessageId) }
  catch (e) { if (valid()) ElMessage.error(e?.message || '发送结果未确认') }
}
async function readVisibleMessages() {
  if (!valid() || loading.value || !isWindowFocused()) return
  try { await markGroupMessagesRead(props.groupId) }
  catch (e) { console.warn('[group-chat] 本地未读更新失败',e) }
}
watch(() => groupChatSession(props.groupId)?.unreadCount, readVisibleMessages)
watch(loading, readVisibleMessages)
// 加载时消息被骨架屏替代；加载完成后，即使缓存消息没有变化，也要在渲染后定位。
watch([loading, () => messages.value.map(m => m.key).join('|')], () => {
  if (valid() && !loading.value && !loadingOlder && bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
}, { flush: 'post' })
onMounted(() => {
  window.addEventListener('focus',readVisibleMessages)
  document.addEventListener('visibilitychange',readVisibleMessages)
  loadGroup()
})
onUnmounted(() => {
  alive = false
  receiptRefresh.stop()
  window.removeEventListener('focus',readVisibleMessages)
  document.removeEventListener('visibilitychange',readVisibleMessages)
})
</script>
<style scoped>
.group-chat-page { height:100%; min-height:0; display:flex; flex-direction:column; overflow:hidden; background:var(--chat-page-bg); }
.preview-notice { display:flex; align-items:flex-start; justify-content:center; gap:7px; flex-shrink:0; padding:9px 16px; border-bottom:1px solid var(--chat-border); background:var(--chat-surface); color:var(--chat-text-3); font-size:11px; line-height:1.7; }
.preview-notice .el-icon { flex-shrink:0; margin-top:3px; color:var(--chat-brand); }
.group-loading { padding:20px; }
.group-chat-empty { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; text-align:center; gap:12px; color:var(--chat-text-3); padding:24px; }
.group-chat-empty > .el-icon { font-size:36px; }
.group-chat-empty h3 { color:var(--chat-text); font-size:16px; font-weight:600; margin:6px 0 0; overflow-wrap:anywhere; }
.group-chat-empty p { font-size:13px; line-height:1.7; margin:0; }
.group-chat-empty small { font-size:11px; line-height:1.7; }
.group-empty-avatar { display:grid; place-items:center; width:68px; height:68px; border-radius:18px; border:1px solid var(--chat-border); color:var(--chat-brand); background:var(--chat-surface); font-size:27px; }
.details-identity { display:flex; align-items:center; flex-direction:column; gap:12px; padding:8px 0 24px; }
.details-identity h3 { margin:0; font-size:17px; color:var(--chat-text); overflow-wrap:anywhere; }
.details-identity p { margin:0; color:var(--chat-text-3); font-size:12px; }
.manage-button { width:100%; margin-bottom:22px; }
.member-heading { display:flex; align-items:center; justify-content:space-between; border-top:1px solid var(--chat-border); padding-top:20px; margin-bottom:12px; }
.member-heading h4 { margin:0; font-size:13px; color:var(--chat-text); }
.member-heading > span, .member-error { font-size:12px; color:var(--chat-text-3); }
.group-member-row { display:flex; align-items:center; gap:10px; padding:10px 0; }
.group-member-row > span:not(.el-avatar) { flex:1; min-width:0; overflow-wrap:anywhere; font-size:13px; color:var(--chat-text); }
.group-member-row .el-avatar { flex:0 0 auto; }
.group-member-row > small { font-size:11px; color:var(--chat-text-3); }
</style>
