<template>
  <div class="chat-page">
    <!--
      顶部返回按钮。
      以前标题和信息一起塞在一条“通栏横带”里（白底 + 下边框）；现在页面外壳统一用
      .chat-page，横带再留就会和下面的卡片各画一次边、看起来像两道框，
      所以这里只保留返回按钮本身，它仍然落在原来的位置（左上角）。
    -->
    <button class="back-btn" type="button" title="返回" @click="goBack">
      <el-icon><ArrowLeft /></el-icon>
    </button>

    <h2 class="chat-title">新的朋友</h2>

    <div v-if="loading" class="state-box">
      <el-skeleton :rows="4" animated />
    </div>

    <!--
      空态文案照原样保留。
      这里【不套 el-empty】：它自带一张灰色插图，和上面的 .chat-empty-icon 是两套视觉，
      叠在一起会出现"两个图标"（Contacts.vue 里是同一个处理）。
    -->
    <div v-else-if="requests.length === 0" class="chat-empty state-box">
      <el-icon class="chat-empty-icon"><Bell /></el-icon>
      <p class="empty-text">没有待处理的好友申请</p>
    </div>

    <!-- 每条申请一张卡片（不是整块一张大卡片里排行）：
         这一页每一条都带“同意/拒绝”两个按钮，"条"和"条"之间必须能明确分开，
         否则很容易点错行、把同意点到别人头上。 -->
    <div v-else class="request-list">
      <div v-for="request in requests" :key="request.friendRequestId" class="request-item chat-card">
        <!-- 点名字/头像看对方资料；右边的同意/拒绝是独立的，不会误触 -->
        <div class="request-main" @click="openProfile(request.fromUserId)">
          <el-avatar class="request-avatar" :size="44" :src="assetUrl(request.avatarUrl)">
            {{ firstCharOf(request) }}
          </el-avatar>
          <div class="request-text">
            <div class="request-name chat-ellipsis">{{ request.nickName || '' }}</div>
            <div class="request-message chat-ellipsis chat-muted">{{ request.message || '请求添加你为好友' }}</div>
          </div>
        </div>

        <div class="request-side">
          <!-- 状态标签：待处理用主色描边、已同意用成功色、已拒绝用危险色。
               用 el-tag 的 type 而不是自己调颜色 —— 它自带深浅色两套取色，
               自己写色号会在深色模式下穿帮。
               用 small 尺寸而不是 tiny：tiny 的字在这个行高里几乎看不清。 -->
          <el-tag
            class="request-status"
            size="small"
            effect="light"
            round
            :type="request.status === 'ACCEPTED' ? 'success' : request.status === 'REJECTED' ? 'danger' : 'primary'"
          >
            {{ request.status || 'PENDING' }}
          </el-tag>

          <div class="request-actions">
            <el-button
              type="primary"
              size="small"
              :loading="handlingRequestId === request.friendRequestId"
              @click="handle(request, true)"
            >
              同意
            </el-button>
            <!-- 拒绝用默认描边按钮：同意是主操作（实心主色），拒绝是次操作，
                 两个都做成主色按钮的话，用户扫一眼分不出哪个是"往哪走" -->
            <el-button
              size="small"
              :disabled="handlingRequestId === request.friendRequestId"
              @click="handle(request, false)"
            >
              拒绝
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
// 返回箭头以前是「‹」字符：字符的大小和基线跟着字体走，和页面里别的图标对不齐，
// 换成 ArrowLeft 之后和标题、图标共用同一套字号规则。
import { ArrowLeft, Bell } from '@element-plus/icons-vue'
import { api, assetUrl } from '../api'
import { friendRequests, identityReady, refreshFriendRequests, reloadSocial } from '../utils/accountData'
import { firstCharOf } from '../utils/display'
import { useBack } from '../utils/navigation'
import { pendingFriendRequestCount } from '../utils/requestReminderStore'

/**
 * 「新的朋友」页（{@code /contacts/requests}）—— 别人申请加我的，待我处理。
 *
 * <p>全局只维护请求数量；进入本页加载详情，数量变化或处理后重新对齐。
 *
 * <p>同意/拒绝传的是<b>申请记录的 id</b>（{@code request.friendRequestId}），
 * 不是申请人的 userId —— 这两个很容易搞混，后端也是按前者查的。
 */
const router = useRouter()

/** 待处理申请（账号缓存的响应式数组） */
const requests = friendRequests
/** 骨架屏：缓存还没加载过时显示 */
const fetching = ref(false)
const loading = computed(() => !identityReady.value || fetching.value)
/** 正在处理的那条申请的 id：只让被点的那条转圈，别的按钮别跟着转（也顺便防连点） */
const handlingRequestId = ref(null)
async function loadRequests(refreshSummary = true) {
  if (fetching.value) return
  fetching.value = true
  try { await refreshFriendRequests(refreshSummary) } finally { fetching.value = false }
}
onMounted(() => loadRequests())
watch(pendingFriendRequestCount, () => {
  if (handlingRequestId.value == null) loadRequests(false)
})

/*
 * 头像首字以前在这里写过一份，现在统一用 utils/display.js 的 firstCharOf
 * （模板里的调用也跟着改了）。申请记录里没有备注字段，所以结果和以前一致。
 */

async function handle(request, accept) {
  if (handlingRequestId.value !== null) {
    return
  }
  handlingRequestId.value = request.friendRequestId
  try {
    if (accept) {
      await api.acceptFriendRequest(request.friendRequestId)
      ElMessage.success('已同意')
      // 同意 = 好友 +1、待处理 -1：两处缓存一起刷
      reloadSocial()
    } else {
      await api.rejectFriendRequest(request.friendRequestId)
      ElMessage.success('已拒绝')
      refreshFriendRequests()
    }
  } catch (e) {
    // 常见：这条申请已经被处理过了（409）—— 后端文案直接显示，同时刷新列表纠正界面
    ElMessage.error(e?.message || '操作失败')
    refreshFriendRequests()
  } finally {
    handlingRequestId.value = null
  }
}

function openProfile(userId) {
  router.push({ name: 'user-profile', params: { id: String(userId) } })
}

/** 返回上一页：实现统一在 utils/navigation.js（没有上一页时兜底回联系人页） */
const goBack = useBack('contacts')
</script>

<style scoped>
/* 滚动由外层 MainLayout 的 .main-body 负责，这里不写 height/overflow */

.back-btn {
  /* align-self 让它在竖排的页面壳里不拉伸，保持一个方正的小按钮 */
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  font-size: 16px;
  color: var(--chat-text-2);
  background: transparent;
  border: none;
  border-radius: var(--chat-radius-sm);
  cursor: pointer;
  /* 反馈用 --chat-dur：和好友列表的悬停手感保持一致 */
  transition: background-color var(--chat-dur), color var(--chat-dur);
}

.back-btn:hover {
  background: var(--chat-surface-2);
  color: var(--chat-text);
}

.request-list {
  /* 每张卡片之间的间距和"整块列表内的行距"是两回事，所以单独给一个值：
     用 --chat-gap（12px）会让卡片看起来还是一整块，再大一点又像互不相干的条目 */
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.request-item {
  display: flex;
  align-items: center;
  gap: 12px;
  /* 内边距按任务给的下限 12px 起（上 13 / 左右 14 让卡片显得不那么挤） */
  padding: 13px 14px;
  /* 卡片自己的悬停反馈：让用户在"同意/拒绝"之前先确认自己指到了哪一条 */
  transition: background-color var(--chat-dur), box-shadow var(--chat-dur);
}

.request-item:hover {
  background: var(--chat-surface-2);
  box-shadow: var(--chat-shadow-2);
}

.request-main {
  /* 点了它才跳资料页，所以它自己单独有一块悬停底色 —— 和整卡片的悬停要能区分开，
     否则用户会以为"点哪里都会跳走" */
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
  padding: 4px;
  margin: -4px;
  border-radius: var(--chat-radius-sm);
  cursor: pointer;
  transition: background-color var(--chat-dur);
}

.request-main:hover {
  background: var(--chat-border);
}

.request-avatar {
  /* 别让长名字把头像压扁 */
  flex-shrink: 0;
  font-size: 16px;
}

.request-text {
  flex: 1;
  min-width: 0;
}

.request-name {
  font-size: 15px;
  color: var(--chat-text);
}

.request-message {
  margin-top: 2px;
  color: var(--chat-text-3);
}

.request-side {
  /* 状态标签在上、按钮在下：竖着排之后标签不用和按钮抢同一行的宽度，
     窄屏上"同意/拒绝"也不会被挤到换行 */
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

.request-status {
  /* 字母状态码本身是次要信息，缩小字距让它看起来更像"标记"而不是正文 */
  letter-spacing: 0.4px;
}

.request-actions {
  display: flex;
  gap: 8px;
}

.state-box {
  /* 空态/骨架屏已经放在共享的 .chat-empty 和页面壳里，这里不再叠白底边框 */
  background: transparent;
}
</style>
