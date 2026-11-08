<template>
  <div class="profile-page">
    <!-- 顶栏：返回 + 标题 -->
    <div class="page-header">
      <button class="back-btn" type="button" title="返回" @click="goBack">‹</button>
      <span class="page-title">详细资料</span>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="state-box">
      <el-skeleton :rows="5" animated />
    </div>

    <!-- 查不到 / 参数不对：用页内空态，不弹窗（404 是正常结果，不是"出错了"）。
         空态统一用 .chat-empty + 图标 + .empty-text：el-empty 自带一张灰色插图，
         和别处的空态是两套视觉（Contacts / FriendRequests / ContactsAdd 同处理）。 -->
    <div v-else-if="errorMessage" class="state-box chat-empty">
      <el-icon class="chat-empty-icon"><Warning /></el-icon>
      <p class="empty-text">{{ errorMessage }}</p>
    </div>

    <template v-else-if="profile">
      <!-- 头部：头像 + 昵称。
           名字只显示 nickName，不编造兜底文案（以前 nickName 为空时会显示"用户3"，
           那种假名字看着像 bug）；用户名在下面的资料行里。

           方向 A 里【没有头图】：一块纯装饰的色块不承载信息，只是"想显得热闹"。
           这里改成"头像 + 名字 + 一条发丝线"，安静、也更省首屏高度。 -->
      <div class="hero">
        <el-avatar class="hero-avatar" :size="60" :src="assetUrl(profile.avatarUrl)">
          {{ firstChar }}
        </el-avatar>
        <div class="hero-name chat-ellipsis">{{ profile.nickName }}</div>
      </div>

      <!-- 基本资料：左边标签、右边值，没有值的显示"未设置" -->
      <div class="chat-card info-card">
        <div class="card-title chat-muted">基本资料</div>
        <div v-for="row in infoRows" :key="row.label" class="info-row">
          <span class="info-label">{{ row.label }}</span>
          <span class="info-value" :class="{ 'info-value--empty': !row.value }">
            {{ row.value || '未设置' }}
          </span>
          <button
            v-if="row.copyable && row.value"
            class="copy-btn"
            type="button"
            @click="copy(row.value, row.label)"
          >
            <el-icon><CopyDocument /></el-icon>
            复制
          </button>
        </div>
      </div>

      <!--
        操作区：<b>一个</b>主按钮，显示什么由"我和他的关系"决定
        （陌生人 → 添加到通讯录 / 已申请 → 置灰等待 / 对方申请我 → 去处理 /
         已是好友 → 发消息 / 自己 → 不显示）。

        为什么不像以前那样并排两个按钮：真实聊天软件里"加好友"和"发消息"
        是互斥的两种状态，同时摆出来反而让人不知道该点哪个。
      -->
      <div v-if="action" class="chat-card action-card">
        <button
          class="action-btn"
          :class="{ 'action-btn--primary': action.kind === 'add' || action.kind === 'message' }"
          type="button"
          :disabled="action.disabled"
          @click="onAction"
        >
          {{ action.text }}
        </button>
      </div>

      <p class="page-note chat-muted">「发消息」要等消息模块（还没做）；「添加到通讯录」现在是真的能用了。</p>
    </template>

    <!-- 填验证消息的对话框（和加好友页共用同一个组件） -->
    <AddFriendDialog v-model="dialogVisible" :target="profile" @sent="loadStatus" />
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
// 图标只服务于视觉：复制按钮光有"复制"两个字时，和普通按钮长得没区别
import { CopyDocument, Warning } from '@element-plus/icons-vue'
import { api, assetUrl } from '../api'
import { genderText } from '../utils/display'
import { RELATION, relationAction } from '../utils/relation'
import { useBack } from '../utils/navigation'
import AddFriendDialog from './AddFriendDialog.vue'

/**
 * 用户资料页（{@code /contacts/user/:id}）—— 按 userId 查详情。
 *
 * <p>用的是 {@code GET /api/user/{id}}，返回<b>详情形状</b>
 * （userId / username / nickName / avatarUrl / gender / birthDate），
 * 比搜索卡片多一个 {@code username}。所以这一页能看到"登录名"，
 * 而列表卡片上只有昵称。
 *
 * <p>这个页面是"点某个人"的统一落点：联系人列表点好友、搜索结果点卡片，
 * 都跳到这里。
 */
const props = defineProps({
  id: { type: String, required: true },
})

const router = useRouter()

const loading = ref(false)
const profile = ref(null)
const errorMessage = ref('')

/** 我和这个人的关系（NONE / PENDING_OUT / PENDING_IN / FRIEND / SELF），决定操作按钮 */
const relationStatus = ref('')
const action = computed(() => relationAction(relationStatus.value))

/** 填验证消息的对话框 */
const dialogVisible = ref(false)

// 监听 id 变化来加载，而不是只写 onMounted：
// 以后如果从资料页直接跳到另一个人的资料页，组件会被复用（不会重新 mount），
// 那时只写 onMounted 就会继续显示上一个人的数据 —— 这类 bug 很难看出来
watch(() => props.id, load, { immediate: true })

async function load() {
  // 路由参数永远是字符串。先自己判一下"是不是数字"，不是就不发请求了 ——
  // 否则后端会返回"参数格式不正确：id"，那个提示对用户没意义。
  if (!/^\d+$/.test(props.id)) {
    errorMessage.value = '用户不存在'
    return
  }

  loading.value = true
  relationStatus.value = ''
  try {
    profile.value = await api.getUser(props.id)
    if (!profile.value) {
      errorMessage.value = '用户不存在'
      return
    }
    // 资料拿到之后顺便问关系 —— 按钮要靠它才知道显示"添加"还是"发消息"
    await loadStatus()
  } catch (e) {
    // 404 的 message 就是后端原话（"用户不存在: 2"），其它失败也有自己的文案
    errorMessage.value = e?.message || '资料加载失败'
  } finally {
    loading.value = false
  }
}

/**
 * 问后端"我和他是什么关系"。
 *
 * <p>拿不到就按陌生人处理：最差是按钮显示"添加到通讯录"，点了会被后端用
 * "你们已经是好友了"纠正过来 —— 比整页加载失败好得多。
 */
async function loadStatus() {
  if (!profile.value) {
    return
  }
  try {
    relationStatus.value = await api.friendStatus(profile.value.userId)
  } catch {
    relationStatus.value = RELATION.NONE
  }
}

/** 操作按钮点击 —— 按关系状态分发 */
function onAction() {
  const current = action.value
  if (!current || current.disabled) {
    return
  }
  if (current.kind === 'add') {
    dialogVisible.value = true
  } else if (current.kind === 'requests') {
    router.push({ name: 'contacts-requests' })
  } else if (current.kind === 'message') {
    // 单聊页这一轮只做好了界面（还没有消息接口），所以进去之后能看、暂时不能发
    router.push({ name: 'chat', params: { userId: String(profile.value.userId) } })
  }
}

/**
 * 头像上显示的首字。
 *
 * <p>名字本身只认 {@code nickName}（就是顶部那一行，不编造兜底文案）；
 * 但头像总得有个字，不然是个空圈 —— 所以 nickName 为空时退回用用户名的首字，
 * 这算"头像的显示细节"，不是给用户看名字。
 *
 * <p>⚠️ 这条"退回 username"的规则是本页独有的，所以<b>没有</b>用它去替换
 * utils/display.js 的 firstChar（那个的兜底是 {@code ?}，且只认 displayName 链路）——
 * 强行统一会把这里的行为改掉。
 */
const firstChar = computed(() => {
  const source = profile.value?.nickName || profile.value?.username || ''
  return source.trim().charAt(0).toUpperCase()
})

/*
 * 性别文案（M/F → 男/女）统一在 utils/display.js —— 加好友页也有一份一模一样的。
 */

/**
 * 资料行。
 * <b>用户 ID 放第一行</b>：它是"这个人在系统里的唯一标识"，
 * 复制给别人加好友时最常用（很多聊天软件都允许复制 ID），所以排在最前面并带复制按钮。
 */
const infoRows = computed(() => {
  const p = profile.value
  if (!p) {
    return []
  }
  return [
    { label: '用户 ID', value: String(p.userId), copyable: true },
    { label: '昵称', value: p.nickName, copyable: false },
    { label: '用户名', value: p.username, copyable: true },
    { label: '性别', value: genderText(p.gender), copyable: false },
    { label: '生日', value: p.birthDate, copyable: false },
  ]
})

/**
 * 复制到剪贴板。
 *
 * <p>包 try/catch 是因为 Electron 打包后页面跑在 {@code file://} 下，
 * 可能没有剪贴板权限（Login.vue 里复制 token 也是同样的处理）。
 */
async function copy(value, label) {
  try {
    await navigator.clipboard.writeText(String(value))
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

/** 返回上一页：实现统一在 utils/navigation.js（没有上一页时兜底回联系人页） */
const goBack = useBack('contacts')
</script>

<style scoped>
/* 不写 height/overflow：滚动由外层 MainLayout 的 .main-body 负责 */

/*
  颜色全部换成 --chat-*：这一页原来自己写死了 10 处色号（#fff / #303133 / #409eff…），
  后果就是深色模式下只有这一页是白的、字还发灰 —— 变量都是转发 EP 的，
  换主题/换主色时这里不用跟着改。
*/
.page-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: var(--chat-surface);
  border-bottom: 1px solid var(--chat-border);
}

.back-btn {
  width: 28px;
  height: 28px;
  padding: 0;
  font-size: 20px;
  line-height: 1;
  color: var(--chat-text-2);
  background: transparent;
  border: none;
  border-radius: var(--chat-radius-sm);
  cursor: pointer;
  transition: background-color var(--chat-dur);
}

.back-btn:hover {
  background: var(--chat-surface-2);
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--chat-text);
}

/* ── 头部：头像 + 名字 + 一条发丝线 ── */
.hero {
  display: flex;
  align-items: center;
  gap: 12px;
  /* 本页顶部有自绘返回栏，所以没直接用 .chat-page；
     这里用同一套间距变量，保证和别的页面是同一个节奏 */
  margin: var(--chat-page-pad) var(--chat-page-pad) 0;
  padding-bottom: 14px;
  /* 分层用线，不用色块/阴影 —— 方向 A 的规则 */
  border-bottom: 1px solid var(--chat-border);
}

.hero-avatar {
  flex-shrink: 0;
  font-size: 22px;
  font-weight: 500;
  /* 头像兜底色用主色的浅色调（深浅两套主题里都成立），不用彩虹色 */
  color: var(--chat-brand);
  background: var(--el-color-primary-light-9);
}

.hero-name {
  /* flex:1 + min-width:0 才能让长名字走省略号，而不是把布局撑破 */
  flex: 1;
  min-width: 0;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--chat-text);
}

/* 卡片表面交给 .chat-card（描边 + 圆角 + 阴影只有一份），这里只管内部留白 */
.info-card {
  margin: var(--chat-gap) var(--chat-page-pad) 0;
  padding: 4px 0 0;
  /* 让第一行/最后一行的分割线不越过圆角 */
  overflow: hidden;
}

.card-title {
  /* 颜色/字号交给 .chat-muted（次要说明文字），这里只补间距 */
  padding: 12px 16px 8px;
}

/* 行高 48px：资料行要能一眼扫过去，太挤会串行、太高又显得空 */
.info-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 48px;
  padding: 0 16px;
  border-top: 1px solid var(--chat-border);
}

.info-label {
  flex-shrink: 0;
  width: 68px;
  font-size: 14px;
  color: var(--chat-text-3);
}

.info-value {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: var(--chat-text);
  word-break: break-all;
}

/* 没有值时降一级灰，和"真的填了值"区分开 */
.info-value--empty {
  color: var(--chat-text-3);
  opacity: 0.75;
}

.copy-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
  padding: 2px 8px;
  font-size: 12px;
  color: var(--chat-brand);
  background: transparent;
  /* 描边按钮：复制是次要操作，实心会和下面的主按钮抢注意力 */
  border: 1px solid var(--chat-brand);
  border-radius: var(--chat-radius-sm);
  cursor: pointer;
  transition: background-color var(--chat-dur);
}

.copy-btn:hover {
  background: var(--chat-surface-2);
}

.action-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: var(--chat-gap) var(--chat-page-pad) 0;
  padding: 12px;
}

.action-btn {
  padding: 10px 0;
  font-size: 15px;
  /* 非主按钮走描边：一张卡里全是实心按钮就分不出主次了 */
  color: var(--chat-brand);
  background: transparent;
  border: 1px solid var(--chat-brand);
  border-radius: var(--chat-radius-sm);
  cursor: pointer;
  transition: filter var(--chat-dur);
}

.action-btn:hover:not(:disabled) {
  filter: brightness(0.96);
}

.action-btn:disabled {
  color: var(--chat-text-3);
  background: var(--chat-surface-2);
  border-color: var(--chat-border);
  cursor: not-allowed;
}

/* 主按钮实心主色。用 EP 的 --el-color-white 而不是写 #fff：
   EP 自己的 primary 按钮取的就是它，深色模式下两边才是同一个"按钮上的白" */
.action-btn--primary {
  color: var(--chat-brand-fg);
  background: var(--chat-brand);
  border-color: var(--chat-brand);
}

.action-btn--primary:hover {
  filter: brightness(1.08);
}

.page-note {
  margin: var(--chat-gap) var(--chat-page-pad) var(--chat-page-pad);
}

.state-box {
  margin: var(--chat-page-pad);
  padding: 32px 16px;
  background: var(--chat-surface);
  border: 1px solid var(--chat-border);
  border-radius: var(--chat-radius);
}
</style>
