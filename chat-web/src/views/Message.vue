<template>
  <div class="message-page chat-page">
    <!-- 页面标题：三个 tab 页都有一行同样字号/位置的标题，切换时视线不用找 -->
    <h2 class="chat-title">消息</h2>

    <!-- 加载中：只在"本地还没有任何会话可渲染"时显示骨架，避免有本地记录时闪一下 -->
    <div v-if="loading && rows.length === 0" class="chat-card state-box">
      <el-skeleton :rows="5" animated />
    </div>

    <!-- 一条会话都没有：给一句"怎么开始"的引导（真实聊天软件这里也有空状态） -->
    <div v-else-if="rows.length === 0" class="chat-card chat-empty">
      <el-icon class="chat-empty-icon"><ChatDotRound /></el-icon>
      <p class="empty-title">还没有会话</p>
      <p class="chat-muted">
        去「联系人」点一个好友 → 资料页点「发消息」，聊过之后会话就会出现在这里。
      </p>
    </div>

    <div v-else class="chat-card conv-list">
      <div
        v-for="row in rows"
        :key="row.key"
        class="conv-item"
        :class="{ 'conv-item--active': row.kind === 'group' ? route.name === 'group-chat' && String(row.groupId) === String(route.params.groupId) : row.peerUserId === activePeerId }"
        @click="openChat(row)"
      >
        <el-avatar class="conv-avatar" :size="46" :src="assetUrl(row.avatarUrl)">
          {{ firstChar(row.title) }}
        </el-avatar>

        <div class="conv-text">
          <div class="conv-name">{{ row.title }}<span v-if="row.kind === 'group'" class="group-conversation-tag">群</span></div>
          <div class="conv-preview">{{ row.preview }}</div>
        </div>

        <!-- 右边一列：时间在上、未读红点在下。没有未读时红点整个不渲染，不留空位 -->
        <div class="conv-right">
          <span class="conv-time">{{ formatTime(row.lastMessageAt) }}</span>
          <span v-if="row.unreadCount > 0" class="badge">{{ unreadText(row.unreadCount) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound } from '@element-plus/icons-vue'
import { assetUrl } from '../api'
import { getFriend, me } from '../utils/accountData'
import {
  conversations,
  refreshConversations,
  startConversationStore,
  stopConversationStore,
} from '../utils/conversationStore'
import { firstChar } from '../utils/display'
import { groupConversations } from '../utils/groupChatStore'

/**
 * 消息页（主页面默认落在这一页）—— <b>会话列表</b>。
 *
 * <h3>数据从哪来（本页自己不发任何请求）</h3>
 * <ol>
 *   <li><b>本地消息库的 index</b>（{@code electron/message-store.cjs} 写的 {@code messages/index.json}）——
 *       每个会话一行摘要（对方 id、最后一条的内容/时间/谁发的）。<b>只读这一个文件</b>，
 *       不碰任何消息正文。它负责"秒开"：挂载时 {@link refreshConversations} 读一次画出来。</li>
 *   <li><b>服务端权威列表</b>（{@code GET /message/conversations}）—— 不在本页拉，
 *       由 {@code utils/conversationStore.js} 在【连上/重连】时拉一次合并进来。</li>
 *   <li><b>名字 / 头像</b> —— 来自 {@code utils/accountData.js} 的账号级缓存
 *       （好友 map + 我是谁），登录时拉一次，本页只读。</li>
 * </ol>
 *
 * 所以"切到这一页"= 0 请求，只做一次本地盘读取；服务端那份在连上时已经由共享层拿好了。
 *
 * <h3>点进去就是已有的单聊页</h3>
 * 直接 {@code router.push} 到 {@code /message/chat/:userId}，复用 {@code Chat.vue}。
 *
 * <h3>未读红点是怎么算的</h3>
 * 本地库里每个会话记着一个"我读到哪了"的游标（{@code myReadUpTo}），未读数就是
 * <b>"对方发的、messageId 大于这个游标"的条数</b> —— 落盘（append）时顺手算好，
 * 显示红点不需要额外读任何文件。权威值由服务端那份在连上时覆盖（本地只留 500 条，会偏小）。
 */
const router = useRouter()
const route = useRoute()

/**
 * 当前正打开的那个会话的对方 userId（没打开会话时是 null）。
 *
 * <p>用途只有一个：宽窗口的双栏布局里，把左边轨道中"正在聊的那一行"高亮出来。
 *
 * <p>为什么从路由读而不是从别处同步一份状态：路由本来就是"当前打开哪个会话"的
 * 唯一真相（刷新、前进后退都以它为准）。再存一份就是第二个真相来源。
 */
const activePeerId = computed(() => {
  if (route.name !== 'chat') {
    return null
  }
  // 路由参数是字符串，这里统一成数字再和 peerUserId 比
  const id = Number(route.params.userId)
  return Number.isFinite(id) ? id : null
})

/**
 * 待渲染的会话摘要 —— 来自 utils/conversationStore.js 的**共享状态**。
 *
 * <p>本页只消费这份数据 + accountData 的好友/我，自己【不发请求】：
 * "什么时候刷新"（连上拉服务端、收到消息重读本地）都由共享层负责。
 */
const loading = ref(true)

onMounted(async () => {
  // 让共享的会话列表开始维护（引用计数：主布局那边也在用同一份）。
  // 它内部订阅了 message / synced / status:open / localRead，并在连上/首次开张时
  // 拉一次服务端权威列表 —— 本页只负责"读本地 index 画出来"。
  startConversationStore()

  // 本地优先：读磁盘上的摘要（秒开、断网也有东西看）。名字/头像稍后由 accountData 响应式补上。
  await refreshConversations()
  loading.value = false
})

onBeforeUnmount(() => {
  stopConversationStore()
})

// ==================== 渲染 ====================

/**
 * 真正渲染的行。
 *
 * <p>排序在这里又做了一次（本地库返回的本来就已经按时间倒序）：重新读盘有 100ms 的合并窗口，
 * 这里再排一次能保证"刚来消息那一行立刻在最上面"。
 */
const rows = computed(() => [...conversations.value
  .map((summary) => {
    const friend = getFriend(summary.peerUserId)
    return {
      key: `single-${summary.peerUserId}`,
      kind: 'single',
      peerUserId: summary.peerUserId,
      // 显示名：备注 > 昵称。两个都没有就【空着】，不编"用户123"这种假名字 ——
      // 和 Contacts.vue 同一个规矩（编出来的名字看着像 bug，而且十有八九是数据真缺了）
      title: friend?.remark || friend?.nickName || '',
      avatarUrl: friend?.avatarUrl || null,
      lastMessageAt: summary.lastMessageAt,
      preview: renderPreview(summary),
      // 未读数在存储层就算好了（落盘时顺手算，不用为了显示红点再读消息文件）
      unreadCount: Number(summary.unreadCount) || 0,
    }
  }), ...groupConversations.value.map(session => ({
    key: `group-${session.group.groupId}`,
    kind: 'group', groupId: session.group.groupId,
    title: session.group.groupName, avatarUrl: session.group.avatarUrl,
    lastMessageAt: session.messages.at(-1)?.createdAt || session.openedAt,
    preview: session.messages.length ? `${session.messages.at(-1).status === 'failed' ? '[未确认] ' : session.messages.at(-1).status === 'sending' ? '[发送中] ' : ''}${session.messages.at(-1).messageType===2 ? '[图片]' : session.messages.at(-1).messageType===3 ? '[文件] '+(session.messages.at(-1).fileName || '') : session.messages.at(-1).content}` : '暂无消息',
    unreadCount: Number(session.unreadCount) || 0,
  }))]
  .sort((a, b) => String(b.lastMessageAt || '').localeCompare(String(a.lastMessageAt || ''))))

/**
 * 那一行摘要。
 *
 * <p>非文本消息显示成 {@code [图片]} 这类 —— {@code lastMessagePreview} 里存的可能是 URL，
 * 直接显示会很丑。
 *
 * <p>视频在服务端属于"文件"（{@code messageType=3}），所以这里靠扩展名把它认出来，
 * 显示成 {@code [视频]} 而不是 {@code [文件]} —— 列表上这一行是用户判断"要不要点进去"的唯一线索。
 */
function renderPreview(summary) {
  const type = summary.lastMessageType
  let text
  if (type === 2) {
    text = '[图片]'
  } else if (type === 3) {
    text = isVideoPreview(summary.lastMessagePreview) ? '[视频]' : '[文件]'
  } else if (type === 4) {
    text = '[语音]'
  } else {
    text = summary.lastMessagePreview || ''
  }
  if (!text) {
    return ''
  }
  // 最后一句是自己发的就加"你: "前缀 —— 一眼能看出"这话是我说的、还没人回"
  const mine = me.value?.userId != null && summary.lastMessageSenderUserId === me.value.userId
  return mine ? `你: ${text}` : text
}

/** 摘要里那句"文件地址"是不是视频（{@code ?n=&s=} 之后不算） */
function isVideoPreview(preview) {
  if (typeof preview !== 'string') {
    return false
  }
  const url = preview.split('?')[0].toLowerCase()
  return /\.(mp4|mov)$/.test(url)
}

/**
 * 列表里的时间：今天只显示 {@code 16:02}，昨天显示"昨天"，更早显示 {@code 10-07}。
 *
 * <p>越近的时间越精确，这是聊天软件列表的通行做法（"昨天"比"10-06 16:02"更好扫）。
 * 服务端给的是本地时间字符串（如 {@code 2026-10-07T16:02:27.2877832}），
 * 小数位有 6 位和 7 位两种写法，{@code new Date()} 都能正确解析（实测过）。
 */
function formatTime(value) {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const sameDay = (a, b) => a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate()

  if (sameDay(date, now)) {
    return `${pad(date.getHours())}:${pad(date.getMinutes())}`
  }
  // 用"今天的 0 点减一天"算昨天，跨月/跨年都不会错
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  if (sameDay(date, yesterday)) {
    return '昨天'
  }
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/**
 * 未读红点上的字。
 *
 * <p>超过 99 就显示 {@code 99+}：位数一多会把名字那一列挤扁，而且"99+"和"137"对用户没区别。
 */
function unreadText(count) {
  return count > 99 ? '99+' : String(count)
}

/*
 * 头像首字走 utils/display.js 的 firstChar（收的是一个字符串）。
 *
 * ⚠️ 这里踩过一次：“firstCharOf(row)”是错的 —— 那个函数收的是【用户对象】
 * （要找 row.nickName / row.remark / row.username），而这里的 row 是【会话摘要】，
 * 上面三个字段一个都没有，于是它只能退化成兜底字符，界面上所有头像都显示成 "?"。
 * 名字已经在 row.title 里了，直接拿它取首字即可。
 */

/**
 * 点一行 → 进单聊页。
 *
 * <p>直接把对方 userId 传给已有的 {@code Chat.vue}，聊天界面一行都不用改。
 */
function openChat(row) {
  if (row.kind === 'group') {
    router.push({name:'group-chat',params:{groupId:String(row.groupId)}})
    return
  }
  // 路由参数是字符串，显式转一下，免得以后有人以为是 number
  router.push({ name: 'chat', params: { userId: String(row.peerUserId) } })
}
</script>

<style scoped>
.group-conversation-tag { display:inline-block; margin-left:7px; padding:1px 4px; border:1px solid var(--chat-border); color:var(--chat-text-3); border-radius:4px; font-size:10px; font-weight:400; vertical-align:middle; }
/*
  注意：这里【不要】写 height:100% + overflow-y:auto。
  滚动由外层 MainLayout 的 .main-body 负责（和 Contacts.vue 同一个规矩），
  这里再套一层会出现"两层滚动条"，底部导航栏附近的表现也会很怪。
*/

.conv-list {
  /* 卡片圆角要能裁掉行的悬停背景：不裁的话第一行/最后一行的方块会顶破圆角 */
  overflow: hidden;
}

.conv-item {
  display: flex;
  align-items: center;
  gap: 12px;
  /*
    行高固定成 --chat-row-h（60px），而不是靠 padding 撑：
    固定行高让整个列表成为一条整齐的网格，扫一列名字时不会忽高忽低。
  */
  min-height: var(--chat-row-h);
  padding: 0 14px;
  cursor: pointer;
  transition: background-color var(--chat-dur);
}

/*
  分隔线画在"后一行"的上边（相邻兄弟选择器），而不是每行都加 border-bottom ——
  后者会在最后一行下面多出一条悬空的线。
*/
.conv-item + .conv-item {
  border-top: 1px solid var(--chat-border);
}

.conv-item:hover {
  background: var(--chat-surface-2);
}

/* 按下时再深一档：鼠标点下去和悬停要能区分开，这是"点到了"的即时反馈 */
.conv-item:active {
  background: var(--chat-page-bg);
}

/*
  正在聊的那一行。
  只在宽窗口的双栏布局里看得出来（窄窗口下列表会被整块换掉），
  用最浅一档的主色底 + 主色名字：要能一眼扫到，但不能比"未读红点"更抢眼。
  规则写在 :hover 之后 —— 同优先级下后写的赢，否则鼠标一放上去高亮就没了。
*/
.conv-item--active {
  background: var(--el-color-primary-light-9);
}

.conv-item--active .conv-name {
  color: var(--chat-brand);
}

.conv-avatar {
  /* 别让长名字把头像压扁 */
  flex-shrink: 0;
  font-size: 16px;
  font-weight: 500;
  /* 头像兜底色用主色的浅色调，比原来的灰蓝更"是自家产品"；
     深色模式下 EP 会把这几个 light-N 变量一起换掉，不用单独处理 */
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-8);
}

.conv-text {
  /* flex:1 占满剩余宽度；
     min-width:0 是让下面两个省略号真正生效的关键：
     flex 子项默认 min-width:auto，长名字会把容器撑开而不是省略 */
  flex: 1;
  min-width: 0;
}

.conv-name {
  font-size: var(--chat-fs-name);
  font-weight: 500;
  color: var(--chat-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 右边一列（时间 + 未读红点）：固定宽度不参与压缩，且靠右对齐 */
.conv-right {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 5px;
}

.conv-time {
  font-size: var(--chat-fs-meta);
  color: var(--chat-text-3);
}

/* 未读红点。和「新的朋友」那个红点同一套数值，保持一致 */
.badge {
  min-width: 18px;
  padding: 0 5px;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
  /* 白字用 --el-color-white：深色模式下它仍然是白，而 --chat-surface 会变成深灰 */
  color: var(--el-color-white);
  background: var(--chat-danger);
  border-radius: 9px;
}

.conv-preview {
  margin-top: 3px;
  font-size: 12.5px;
  color: var(--chat-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.state-box {
  padding: 20px;
}

/* 空状态里那行"还没有会话"：比说明文字重一档，但没有标题大 */
.empty-title {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--chat-text-2);
}
</style>
