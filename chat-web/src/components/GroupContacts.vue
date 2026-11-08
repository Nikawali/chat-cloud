<template>
  <section class="group-panel">
    <div class="group-toolbar chat-card">
      <div class="group-heading-icon"><el-icon><ChatDotRound /></el-icon></div>
      <div class="group-heading"><h3>一起聊，让联系更近一点</h3><p>找到你的群聊，也可以邀请好友加入。</p></div>
      <div class="toolbar-actions">
        <el-button :icon="Refresh" :loading="loading" :disabled="busy" aria-label="刷新群聊和邀请" @click="refresh(true)" />
        <el-button type="primary" :icon="Plus" :disabled="busy" @click="tab = 'create'">创建群聊</el-button>
      </div>
    </div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
    <button v-if="pendingGroupInvitationCount > 0 && tab !== 'invitations'" class="invitation-banner" type="button" @click="tab = 'invitations'">
      <el-icon><Message /></el-icon><span>你有 <strong>{{ pendingGroupInvitationCount }}</strong> 条待处理的群聊邀请</span><span class="banner-action">查看邀请 <el-icon><ArrowRight /></el-icon></span>
    </button>
    <el-tabs v-model="tab" class="group-tabs">
      <el-tab-pane label="我的群聊" name="mine">
        <div v-if="loading && !mine.length" class="loading-card chat-card"><el-skeleton :rows="3" animated /></div>
        <div v-else-if="!mine.length" class="group-empty chat-card"><span class="empty-symbol"><el-icon><ChatDotRound /></el-icon></span><h4>开启一段新的群聊</h4><p>创建一个群，或者搜索群号加入朋友们的讨论。</p><el-button type="primary" :icon="Plus" @click="tab = 'create'">创建我的第一个群</el-button></div>
        <div v-if="mine.length" class="group-list chat-card">
        <div v-for="group in mine" :key="group.groupId" class="group-row mine-row">
          <button class="group-open" type="button" @click="enterGroupChat(group)">
          <el-avatar class="group-avatar" :size="40" :src="assetUrl(group.avatarUrl)">{{ group.groupName?.slice(0, 1) }}</el-avatar>
          <div class="group-info">
            <strong>{{ group.groupName }}</strong>
            <small>群号 {{ group.groupId }} · {{ group.memberCount }} 人</small>
          </div>
          </button>
          <el-tag size="small" :type="group.memberRole >= 2 ? 'primary' : 'info'" effect="plain">{{ roleName(group.memberRole) }}</el-tag>
          <el-button text :disabled="busy" :icon="ArrowRight" aria-label="查看群聊详情" @click="openDetail(group.groupId)" />
        </div>
        </div>
        <el-button v-if="moreMine" :loading="loading" @click="loadMoreMine">加载更多群聊</el-button>
      </el-tab-pane>
      <el-tab-pane label="创建群聊" name="create">
        <div class="create-layout chat-card">
        <el-form label-position="top" class="group-form" @submit.prevent="create">
          <div class="form-heading"><h3>给大家一个相聚的地方</h3><p>先起个名字，创建后就可以邀请好友。</p></div>
          <el-form-item label="群名称">
            <el-input v-model="name" maxlength="100" show-word-limit placeholder="例如：周末出游计划" :disabled="busy" size="large" />
          </el-form-item>
          <p class="form-note"><el-icon><User /></el-icon>你将成为群主，好友同意邀请后加入。</p>
          <el-button type="primary" native-type="submit" size="large" :loading="busy" :disabled="!name.trim()">创建群聊</el-button>
        </el-form>
        <aside class="group-preview"><span class="preview-label">群聊预览</span><el-avatar class="group-avatar" :size="64">{{ name.trim().slice(0, 1) || '群' }}</el-avatar><strong>{{ name.trim() || '新的群聊' }}</strong><span>从一次邀请开始</span><div class="preview-people"><el-icon><User /></el-icon><el-icon><User /></el-icon><el-icon><Plus /></el-icon></div></aside>
        </div>
      </el-tab-pane>
      <el-tab-pane label="搜索加入" name="search">
        <div class="search-surface chat-card"><div class="form-heading"><h3>找到你想加入的群聊</h3><p>请输入完整群 ID，仅支持精确查询。</p></div>
        <el-form class="group-search" @submit.prevent="search">
          <el-input v-model="keyword" maxlength="19" inputmode="numeric" clearable :prefix-icon="Search" placeholder="输入完整群 ID" :disabled="searching" size="large" />
          <el-button native-type="submit" type="primary" :loading="searching" :disabled="!keyword.trim() || busy" size="large">搜索</el-button>
        </el-form>
        <p class="search-tip">加入后可接收新消息，入群前的聊天记录不可见。</p></div>
        <div v-if="searched && !found.length" class="group-empty chat-card"><span class="empty-symbol"><el-icon><Search /></el-icon></span><h4>没有找到这个群聊</h4><p>请检查是否输入了正确、完整的群 ID。</p></div>
        <div v-for="group in found" :key="group.groupId" class="group-row chat-card">
          <el-avatar class="group-avatar" :size="44" :src="assetUrl(group.avatarUrl)">{{ group.groupName?.slice(0, 1) }}</el-avatar>
          <div class="group-info"><strong>{{ group.groupName }}</strong><small>群号 {{ group.groupId }} · {{ group.memberCount }}/{{ group.maxMembers }} 人</small></div>
          <el-button v-if="group.memberRole" :disabled="busy" @click="enterGroupChat(group)">进入群聊</el-button>
          <el-button v-else type="primary" :disabled="busy || group.memberCount >= group.maxMembers" @click="join(group)">
            {{ group.memberCount >= group.maxMembers ? '群已满' : '加入群聊' }}
          </el-button>
        </div>
      </el-tab-pane>
      <el-tab-pane name="invitations">
        <template #label><span class="invitation-tab-label">群聊邀请<span v-if="pendingGroupInvitationCount" class="invitation-count">{{ pendingGroupInvitationCount > 99 ? '99+' : pendingGroupInvitationCount }}</span></span></template>
        <div class="invitations-heading"><h3>来自好友的邀请</h3><p>接受后加入群聊，也可以选择婉拒。</p></div>
        <div v-if="loading && !invitations.length" class="loading-card chat-card"><el-skeleton :rows="3" animated /></div>
        <div v-else-if="!invitations.length" class="group-empty chat-card"><span class="empty-symbol"><el-icon><Message /></el-icon></span><h4>邀请都处理好了</h4><p>新的群聊邀请会显示在这里。</p></div>
        <div v-for="item in invitations" :key="item.invitationId" class="group-row chat-card invitation-row">
          <el-avatar class="group-avatar invitation-avatar" :size="48">{{ item.groupName?.slice(0, 1) }}</el-avatar>
          <div class="group-info">
            <span class="invitation-eyebrow">{{ item.inviterNickName }} 邀请你加入</span><strong>{{ item.groupName }}</strong>
            <small>群号 {{ item.groupId }}<span class="meta-dot">·</span>{{ formatTime(item.expiresAt) }} 前有效</small>
          </div>
          <div class="group-actions">
            <el-button :disabled="busy" @click="handleInvitation(item, false)">婉拒</el-button>
            <el-button type="primary" :disabled="busy" @click="handleInvitation(item, true)">接受邀请</el-button>
          </div>
        </div>
        <el-button v-if="moreInvites" :loading="loading" @click="loadMoreInvitations">加载更多邀请</el-button>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="detailVisible" class="group-detail-dialog" title="群聊详情" width="min(600px, 92vw)" @closed="closeDetail">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-header"><el-avatar class="group-avatar" :size="56" :src="assetUrl(detail.avatarUrl)">{{ detail.groupName?.slice(0, 1) }}</el-avatar><div><h3>{{ detail.groupName }}</h3><p>群号 {{ detail.groupId }} · {{ detail.memberCount }}/{{ detail.maxMembers }} 人</p></div><el-tag effect="plain">{{ roleName(detail.memberRole) }}</el-tag></div>
          <template v-if="detail.memberRole >= 2">
            <div class="detail-section"><h4>邀请好友</h4>
            <el-select v-model="selectedFriends" multiple filterable :multiple-limit="50" placeholder="选择好友，最多 50 位" class="invite-select" :disabled="busy">
              <el-option v-for="friend in inviteOptions" :key="friend.userId" :value="friend.userId" :label="displayName(friend)" />
            </el-select>
            <el-button type="primary" :loading="busy" :disabled="!selectedFriends.length" @click="invite">发送邀请</el-button>
            <p class="chat-muted">邀请 7 天有效，好友同意后才会加入。</p></div>
          </template>
          <div class="detail-section"><h4>群成员 <span>{{ detail.memberCount }}</span></h4>
          <div v-for="member in memberList" :key="member.userId" class="member-row">
            <el-avatar :size="30" :src="assetUrl(member.avatarUrl)">{{ member.nickName?.slice(0, 1) }}</el-avatar>
            <span>{{ member.nickName }}</span><el-tag size="small" type="info">{{ roleName(member.memberRole) }}</el-tag>
          </div>
          <el-button v-if="moreMembers" :loading="detailLoading" @click="loadMembers">加载更多成员</el-button>
          </div><el-button class="enter-chat-button" type="primary" @click="enterGroupChat(detail)">进入群聊</el-button>
        </template>
      </div>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Refresh, Plus, Search, Message, User, ArrowRight } from '@element-plus/icons-vue'
import { api, assetUrl } from '../api'
import { friends } from '../utils/accountData'
import { displayName } from '../utils/display'
import { newClientMessageId } from '../utils/chatSocket'
import { onChatEvent } from '../utils/chatSync'
import { pendingGroupInvitationCount, refreshRequestReminders } from '../utils/requestReminderStore'
import { openGroupConversation } from '../utils/groupChatStore'

const route = useRoute(), router = useRouter()
const tab = computed({
  get: () => ['mine', 'create', 'search', 'invitations'].includes(route.query.groupTab) ? route.query.groupTab : 'mine',
  set: groupTab => router.replace({ name: 'contacts', query: { section: 'groups', groupTab } }),
})
const mine = ref([]), invitations = ref([]), loading = ref(false), loadError = ref('')
const moreMine = ref(false), moreInvites = ref(false), busy = ref(false)
const name = ref(''), keyword = ref(''), found = ref([]), searched = ref(false), searching = ref(false)
const detailVisible = ref(false), detailLoading = ref(false), detail = ref(null), memberList = ref([])
const selectedFriends = ref([]), moreMembers = ref(false)
let alive = true, refreshVersion = 0, detailVersion = 0, createRequest = null, unsubscribe = null
const inviteOptions = computed(() => {
  const memberIds = new Set(memberList.value.map(m => m.userId))
  return [...friends.value.values()].filter(f => !memberIds.has(f.userId))
})
const roleName = role => ({ 1: '成员', 2: '管理员', 3: '群主' }[role] || '未加入')
const formatTime = value => value ? value.replace('T', ' ').slice(0, 16) : ''
const error = e => ElMessage.error(e?.message || '操作失败，请稍后重试')
function enterGroupChat(group) {
  if (!openGroupConversation(group)) return
  router.push({ name: 'group-chat', params: { groupId: String(group.groupId) } })
}

async function refresh(force = false) {
  const version = ++refreshVersion
  loading.value = true
  try {
    const [groups, pending] = await Promise.all([api.myGroups(), api.groupInvitations(), refreshRequestReminders(force === true)])
    if (!alive || version !== refreshVersion) return
    mine.value = groups || []; invitations.value = pending || []
    moreMine.value = mine.value.length === 50; moreInvites.value = invitations.value.length === 50
    loadError.value = ''
  } catch (e) {
    if (alive && version === refreshVersion) loadError.value = e?.message || '加载失败，请点击刷新'
  } finally { if (alive && version === refreshVersion) loading.value = false }
}
async function loadMoreMine() {
  if (loading.value) return
  const version = refreshVersion
  loading.value = true
  try {
    const rows = await api.myGroups(mine.value.at(-1)?.groupId || 0)
    if (alive && version === refreshVersion) { mine.value.push(...rows); moreMine.value = rows.length === 50 }
  } catch (e) { if (alive) error(e) }
  finally { if (alive && version === refreshVersion) loading.value = false }
}
async function loadMoreInvitations() {
  if (loading.value) return
  const version = refreshVersion
  loading.value = true
  try {
    const rows = await api.groupInvitations(invitations.value.at(-1)?.invitationId || 0)
    if (alive && version === refreshVersion) { invitations.value.push(...rows); moreInvites.value = rows.length === 50 }
  } catch (e) { if (alive) error(e) }
  finally { if (alive && version === refreshVersion) loading.value = false }
}
async function create() {
  if (busy.value || !name.value.trim()) return
  const groupName = name.value.trim()
  if (!createRequest || createRequest.name !== groupName) createRequest = { name: groupName, id: newClientMessageId() }
  busy.value = true
  try {
    const group = await api.createGroup(groupName, createRequest.id)
    if (!alive) return
    name.value = ''; createRequest = null; tab.value = 'mine'
    ElMessage.success('群聊已创建'); await refresh(true); await openDetail(group.groupId)
  } catch (e) { if (alive) error(e) }
  finally { if (alive) busy.value = false }
}
async function search() {
  if (searching.value || !keyword.value.trim()) return
  if (!/^[1-9][0-9]{0,18}$/.test(keyword.value.trim()) || BigInt(keyword.value.trim()) > 9223372036854775807n) {
    found.value = []; searched.value = false
    ElMessage.warning('请输入正确、完整的群 ID，仅支持数字精确查询')
    return
  }
  searching.value = true
  found.value = []; searched.value = false
  try {
    const rows = await api.searchGroups(keyword.value.trim())
    if (alive) { found.value = rows || []; searched.value = true }
  } catch (e) { if (alive) error(e) }
  finally { if (alive) searching.value = false }
}
async function join(group) {
  if (busy.value) return
  busy.value = true
  try {
    const joined = await api.joinGroup(group.groupId)
    if (!alive) return
    found.value = found.value.map(g => g.groupId === joined.groupId ? joined : g)
    ElMessage.success('已加入群聊'); await refresh(true); await openDetail(joined.groupId)
  } catch (e) { if (alive) error(e) }
  finally { if (alive) busy.value = false }
}
async function handleInvitation(item, accept) {
  if (busy.value) return
  busy.value = true
  try {
    if (accept) await api.acceptGroupInvitation(item.invitationId)
    else await api.rejectGroupInvitation(item.invitationId)
    if (!alive) return
    ElMessage.success(accept ? '已加入群聊' : '已拒绝邀请')
    await refresh(true)
    if (accept) { tab.value = 'mine'; await openDetail(item.groupId) }
  } catch (e) { if (alive) { error(e); await refresh(true) } }
  finally { if (alive) busy.value = false }
}
async function openDetail(groupId) {
  const version = ++detailVersion
  detailVisible.value = true; detailLoading.value = true; detail.value = null
  memberList.value = []; selectedFriends.value = []; moreMembers.value = false
  try {
    const [group, rows] = await Promise.all([api.groupDetail(groupId), api.groupMembers(groupId)])
    if (!alive || version !== detailVersion || !detailVisible.value) return
    detail.value = group; memberList.value = rows || []; moreMembers.value = memberList.value.length === 100
  } catch (e) { if (alive && version === detailVersion) { error(e); detailVisible.value = false } }
  finally { if (alive && version === detailVersion) detailLoading.value = false }
}
async function loadMembers() {
  if (!detail.value || detailLoading.value) return
  const groupId = detail.value.groupId, version = detailVersion
  detailLoading.value = true
  try {
    const rows = await api.groupMembers(groupId, memberList.value.at(-1)?.userId || 0)
    if (alive && version === detailVersion) { memberList.value.push(...rows); moreMembers.value = rows.length === 100 }
  } catch (e) { if (alive) error(e) }
  finally { if (alive && version === detailVersion) detailLoading.value = false }
}
async function invite() {
  if (busy.value || !detail.value || !selectedFriends.value.length) return
  busy.value = true
  try {
    const count = await api.inviteToGroup(detail.value.groupId, [...selectedFriends.value])
    if (alive) { selectedFriends.value = []; ElMessage.success(count ? `已发送 ${count} 条邀请` : '好友已在群内或已有待处理邀请') }
  } catch (e) { if (alive) error(e) }
  finally { if (alive) busy.value = false }
}
function closeDetail() { detailVersion++; detail.value = null; detailLoading.value = false }
function onFocus() { if (!document.hidden && !busy.value) refresh() }
watch([pendingGroupInvitationCount, () => tab.value], () => {
  if (alive && tab.value === 'invitations' && !loading.value && !busy.value) refresh()
})
onMounted(() => {
  refresh(); window.addEventListener('focus', onFocus)
  unsubscribe = onChatEvent(event => { if (event.kind === 'status' && event.state === 'open' && !busy.value) refresh() })
  const groupId = Number(route.query.groupId)
  if (Number.isSafeInteger(groupId) && groupId > 0) openDetail(groupId)
})
onUnmounted(() => { alive = false; refreshVersion++; detailVersion++; window.removeEventListener('focus', onFocus); unsubscribe?.() })
</script>

<style scoped>
.group-panel { min-width:0; --el-fill-color-blank:var(--chat-surface); --el-text-color-primary:var(--chat-text); --el-text-color-regular:var(--chat-text-2); --el-text-color-secondary:var(--chat-text-3); --el-border-color:var(--chat-border-strong); --el-border-color-light:var(--chat-border); }
.group-toolbar, .group-row, .group-search, .member-row, .group-actions, .toolbar-actions { display:flex; align-items:center; gap:12px; }
.group-toolbar { padding:22px; margin-bottom:16px; border-radius:14px; }
.group-heading-icon { display:grid; place-items:center; flex-shrink:0; width:48px; height:48px; border-radius:13px; background:var(--chat-surface-2); color:var(--chat-brand); font-size:24px; }
.group-heading { flex:1; min-width:0; }
.group-heading h3, .form-heading h3, .invitations-heading h3 { margin:0; color:var(--chat-text); font-size:16px; font-weight:600; line-height:1.5; }
.group-heading p, .form-heading p, .invitations-heading p { margin:6px 0 0; color:var(--chat-text-3); font-size:12px; line-height:1.7; }
.toolbar-actions { gap:8px; flex-shrink:0; }
.toolbar-actions :deep(.el-button + .el-button) { margin-left:0; }
.invitation-banner { width:100%; display:flex; align-items:center; gap:10px; padding:14px 16px; margin:0 0 14px; border:1px solid var(--chat-border); border-left:3px solid var(--chat-brand); border-radius:9px; background:var(--chat-surface); color:var(--chat-text-2); font:inherit; font-size:12px; text-align:left; cursor:pointer; }
.invitation-banner > .el-icon { color:var(--chat-brand); font-size:17px; }
.banner-action { display:flex; align-items:center; gap:4px; margin-left:auto; color:var(--chat-brand); white-space:nowrap; font-weight:500; }
.group-tabs :deep(.el-tabs__header) { margin-bottom:20px; }
.group-tabs :deep(.el-tabs__item) { font-size:13px; height:46px; color:var(--chat-text-2); }
.group-tabs :deep(.el-tabs__item.is-active) { color:var(--chat-brand); }
.group-tabs :deep(.el-tabs__nav-wrap::after) { height:1px; background:var(--chat-border); }
.group-list { margin-bottom:14px; overflow:hidden; }
.mine-row { margin:0; padding:12px 16px; gap:10px; }
.mine-row + .mine-row { border-top:1px solid var(--chat-border); }
.mine-row:hover { background:var(--chat-surface-2); }
.group-open { display:flex; align-items:center; gap:12px; flex:1; min-width:0; border:0; padding:0; background:transparent; color:inherit; font:inherit; text-align:left; cursor:pointer; }
.group-open:focus-visible { outline:2px solid var(--chat-brand); outline-offset:4px; border-radius:4px; }
.group-open .group-info strong { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.group-row { padding:18px; margin-bottom:12px; transition:border-color .16s, background .16s; }
.group-row:hover { border-color:var(--chat-border-strong); }
.group-avatar { flex-shrink:0; background:var(--chat-surface-2); color:var(--chat-brand); font-weight:600; font-size:20px; border:1px solid var(--chat-border); border-radius:12px; }
.group-info { flex:1; min-width:0; overflow-wrap:anywhere; }
.group-info strong, .group-info small { display:block; }
.group-info strong { color:var(--chat-text); font-size:14px; font-weight:600; line-height:1.6; }
.group-info small { color:var(--chat-text-3); margin-top:5px; font-size:11px; line-height:1.7; }
.create-layout { display:grid; grid-template-columns:minmax(0,1fr) 230px; overflow:hidden; border-radius:14px; }
.group-form { padding:30px; min-width:0; }
.form-heading { margin-bottom:25px; }
.form-note { display:flex; align-items:center; gap:6px; font-size:12px; color:var(--chat-text-3); line-height:1.8; margin:18px 0 24px; }
.group-preview { display:flex; flex-direction:column; align-items:center; justify-content:center; gap:12px; padding:28px; background:var(--chat-surface-2); border-left:1px solid var(--chat-border); min-width:0; }
.group-preview .group-avatar { background:var(--chat-surface); color:var(--chat-brand); margin-top:10px; font-size:27px; }
.group-preview strong { color:var(--chat-text); font-size:14px; max-width:100%; overflow-wrap:anywhere; text-align:center; }
.group-preview > span { font-size:11px; color:var(--chat-text-3); }
.preview-label { letter-spacing:2px; }
.preview-people { display:flex; gap:5px; margin-top:5px; }
.preview-people .el-icon { width:30px; height:30px; font-size:14px; background:var(--chat-surface); padding:7px; border-radius:50%; color:var(--chat-text-3); }
.search-surface { padding:26px; margin-bottom:18px; border-radius:14px; }
.search-surface .form-heading { margin-bottom:18px; }
.group-search { margin:12px 0; max-width:720px; }
.search-tip { font-size:11px; color:var(--chat-text-3); margin:10px 0 0; line-height:1.7; }
.invitation-tab-label { display:flex; align-items:center; gap:7px; }
.invitation-count { display:inline-flex; justify-content:center; align-items:center; min-width:18px; height:18px; padding:0 5px; box-sizing:border-box; border-radius:10px; color:white; background:var(--chat-danger); font-size:10px; line-height:1; }
.invitations-heading { margin:2px 0 20px; }
.invitation-row { padding:21px; gap:16px; border-radius:12px; }
.invitation-eyebrow { display:block; color:var(--chat-text-2); font-size:11px; margin-bottom:4px; }
.meta-dot { margin:0 7px; }
.group-actions { gap:8px; flex-shrink:0; }
.group-actions :deep(.el-button + .el-button) { margin-left:0; }
.group-empty { display:flex; flex-direction:column; align-items:center; text-align:center; padding:48px 20px; margin-bottom:15px; border-radius:14px; }
.empty-symbol { display:grid; place-items:center; width:62px; height:62px; background:var(--chat-surface-2); color:var(--chat-text-3); border-radius:18px; font-size:27px; }
.group-empty h4 { font-size:15px; font-weight:600; color:var(--chat-text); margin:20px 0 8px; }
.group-empty p { font-size:12px; line-height:1.8; color:var(--chat-text-3); margin:0 0 22px; }
.loading-card { padding:28px; margin-bottom:14px; }
.detail-header { display:flex; align-items:center; gap:14px; padding:0 0 23px; }
.detail-header > div { flex:1; min-width:0; overflow-wrap:anywhere; }
.detail-header h3 { font-size:18px; font-weight:600; margin:0 0 7px; color:var(--chat-text); }
.detail-header p { font-size:12px; margin:0; color:var(--chat-text-3); }
.detail-section { border-top:1px solid var(--chat-border); padding:20px 0 6px; }
.detail-section h4 { margin:0 0 16px; font-size:13px; font-weight:600; color:var(--chat-text); }
.detail-section h4 span { color:var(--chat-text-3); margin-left:6px; font-weight:400; }
.detail-section p { font-size:11px; line-height:1.8; }
.member-row { padding:10px 0; }
.member-row > span:not(.el-avatar) { flex:1; font-size:13px; color:var(--chat-text); }
.member-row .el-avatar { flex:0 0 auto; }
.invite-select { width:100%; margin:0 0 12px; }
.detail-note { text-align:center; color:var(--chat-text-3); font-size:11px; margin:20px 0 0; }
.enter-chat-button { width:100%; margin-top:20px; }
@media(max-width:680px) {
  .group-toolbar { flex-wrap:wrap; padding:17px; gap:12px; }
  .group-heading h3 { font-size:14px; }
  .toolbar-actions { width:100%; justify-content:flex-end; padding-top:3px; }
  .create-layout { grid-template-columns:1fr; }
  .group-preview { display:none; }
  .group-form, .search-surface { padding:20px; }
  .invitation-row { flex-wrap:wrap; padding:17px; gap:12px; }
  .group-actions { width:100%; justify-content:flex-end; padding-top:12px; border-top:1px solid var(--chat-border); }
  .invitation-banner { flex-wrap:wrap; font-size:11px; }
  .group-tabs :deep(.el-tabs__item) { font-size:12px; padding:0 13px; }
  .detail-header { flex-wrap:wrap; }
}
</style>
