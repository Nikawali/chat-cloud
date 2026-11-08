<template>
  <div class="add-page">
    <!--
      顶部：返回 + 标题。
      桌面端窗口没有系统的返回按钮，不给自己加一个的话，用户只能靠底部导航离开。
    -->
    <div class="page-header">
      <button class="back-btn" type="button" title="返回联系人" @click="goBack">‹</button>
      <span class="page-title">添加好友</span>
    </div>

    <div class="search-block chat-card">
      <el-input
        v-model="phone"
        maxlength="11"
        placeholder="请输入 11 位手机号"
        clearable
        @input="onPhoneInput"
        @keyup.enter="onSearch"
      >
        <template #append>
          <el-button :loading="searching" @click="onSearch">
            <el-icon><Search /></el-icon>
            搜索
          </el-button>
        </template>
      </el-input>

      <p class="tip chat-muted">
        只能按<b>完整手机号精确查找</b>：用户名允许重复（它只是显示名），没法唯一定位一个人；
        也不做模糊搜索 —— 那等于把整张用户表交出去。
      </p>
    </div>

    <div class="result">
      <!-- 搜索中 -->
      <div v-if="searching" class="state-box">
        <el-skeleton :rows="2" animated />
      </div>

      <!--
        搜过了，但后端说没这个人（404「该手机号未注册」）。
        这是【正常结果】不是错误，所以用页内空态表达，不用弹错误提示 ——
        弹窗会把"用户输错了一位数字"这种事渲染得像服务挂了。
        空态统一用 .chat-empty + 图标 + .empty-text：el-empty 自带一张灰色插图，
        和别处的空态是两套视觉（Contacts / FriendRequests 也是同一个处理）。
      -->
      <div v-else-if="notFound" class="state-box chat-empty">
        <el-icon class="chat-empty-icon"><Search /></el-icon>
        <p class="empty-text">{{ notFoundMessage }}</p>
      </div>

      <!-- 搜到了 -->
      <div v-else-if="found" class="user-card chat-card">
        <!--
          左边这块点进去看详细资料（按 userId 查）。
          它和右边的按钮是并列的两个元素，所以点按钮不会连带着跳走 —— 不用 @click.stop。
          这样"搜到 → 确认是不是这个人 → 再加"是一条自然的链路。
        -->
        <div class="user-main" @click="openProfile">
          <el-avatar :size="56" :src="assetUrl(found.avatarUrl)">
            {{ firstChar }}
          </el-avatar>

          <div class="user-text">
            <div class="user-name chat-ellipsis">{{ displayName }}</div>
            <!-- 卡片里没有 username（登录名只在详情里给），所以副标题用性别/生日；
                 它们正是"确认是不是这个人"的依据，也是卡片里为什么要带这两个字段 -->
            <div v-if="cardSubtitle" class="user-sub chat-muted">{{ cardSubtitle }}</div>
            <div v-if="isSelf" class="user-note">这是你自己</div>
          </div>

          <!-- 箭头是"能点进去"的提示，用图标而不是「›」字符：
               那个字符的字形/粗细由系统字体决定，换个机器就不一样 -->
          <el-icon class="user-arrow"><ArrowRight /></el-icon>
        </div>

        <!--
          按钮按"我和他的关系"显示（关系状态是搜到人之后单独问后端的）：
            NONE → 添加到通讯录（弹对话框填验证消息）
            PENDING_OUT → 已申请，等待验证（置灰）
            PENDING_IN → 对方申请了你，去处理
            FRIEND → 发消息
            SELF → 不显示按钮（下面只提示"这是你自己"）
        -->
        <el-button
          v-if="action"
          :type="action.kind === 'add' ? 'primary' : 'default'"
          :disabled="action.disabled"
          @click="onAction"
        >
          {{ action.text }}
        </el-button>
      </div>

      <!-- 还没搜：给一句引导，别让页面空着 -->
      <div v-else class="state-box chat-empty">
        <el-icon class="chat-empty-icon"><User /></el-icon>
        <p class="empty-text">输入手机号，找到对方后添加</p>
      </div>
    </div>

    <!-- 填验证消息的对话框（和资料页共用同一个组件） -->
    <AddFriendDialog v-model="dialogVisible" :target="found" @sent="loadStatus" />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, assetUrl } from '../api'
// 这里给 firstChar 起个别名：本页有一个同名的 computed（模板里直接用 firstChar），
// 直接 import 会和它重名（编译器会报 "Identifier 'firstChar' has already been declared"）
import { firstChar as sharedFirstChar, genderText } from '../utils/display'
// 图标只服务于视觉：搜索按钮上加个放大镜，一眼能认出这是"搜索"而不是普通确认
import { ArrowRight, Search, User } from '@element-plus/icons-vue'
import { RELATION, relationAction } from '../utils/relation'
import { useBack } from '../utils/navigation'
import AddFriendDialog from './AddFriendDialog.vue'

/**
 * 添加好友页（{@code #/contacts/add}）。
 *
 * <p>搜索用 {@code GET /api/user/search?phone=}（chat-user 那边），
 * 搜到之后再问一次 {@code GET /api/relation/friend/status}（chat-relation），
 * 按钮才知道该显示"添加到通讯录"还是"已申请，等待验证"。
 *
 * <p>发申请走 {@code POST /api/relation/friend/request}，会弹出填"验证消息"的对话框。
 *
 * <p><b>为什么这一版没有"加群"tab？</b> 群功能后端一行都没有，放一个空 tab
 * 只会让人以为坏了。位置已经留好：以后在搜索框上面加一层 tab 即可，
 * 路由（{@code /contacts/add}）不用动。
 */
const router = useRouter()

const phone = ref('')
const searching = ref(false)

/** 搜到的人。<b>卡片形状</b>：userId / nickName / avatarUrl / gender / birthDate（没有 username） */
const found = ref(null)

/** 搜过了但没这个人 */
const notFound = ref(false)
const notFoundMessage = ref('该手机号未注册')

/**
 * 我和"搜到的这个人"是什么关系（NONE / PENDING_OUT / PENDING_IN / FRIEND / SELF）。
 *
 * <p>必须问后端：前端只有"这个人的资料"，看不到关系表 ——
 * 自己猜就会出现"已经是好友了还显示添加到通讯录"。
 *
 * <p>顺便它替掉了以前那个"先查我是谁、再比对 id"的做法：
 * 加自己这件事后端会直接返回 SELF，一次请求就够了。
 */
const relationStatus = ref('')
const action = computed(() => relationAction(relationStatus.value))
const isSelf = computed(() => relationStatus.value === RELATION.SELF)

/** 填验证消息的对话框 */
const dialogVisible = ref(false)

/**
 * 显示名：就是 nickName。
 *
 * <p>这里<b>不编造兜底文案</b>：以前 nickName 为空会显示"用户3"这种东西，
 * 那是我自己拼出来的假名字，看着像 bug（真实原因通常是缓存里字段名是旧的，
 * 见 CacheKeys 的说明）。名字没有就空着，别造一个。
 */
const displayName = computed(() => found.value?.nickName || '')

/** 副标题：性别 + 生日，有哪个显示哪个（都没有就不显示这一行） */
const cardSubtitle = computed(() => {
  if (!found.value) {
    return ''
  }
  return [genderText(found.value.gender), found.value.birthDate].filter(Boolean).join(' · ')
})

/*
 * 头像首字：规则（首字大写、没有名字用 ? 占位）统一在 utils/display.js，
 * 这里只是把"这个名字从哪来"接上 —— 搜索结果是用户卡片，没有备注字段。
 */
const firstChar = computed(() => sharedFirstChar(displayName.value))

/**
 * 输入时的过滤。
 *
 * <p>{@code maxlength} 只管手工输入，<b>粘贴挡不住</b>，所以这里再主动过滤一次非数字。
 * 顺便把上一次的搜索结果清掉 —— 否则"号码改了、下面还挂着上一个人的资料"，
 * 手一快就点错了人。
 */
function onPhoneInput(value) {
  phone.value = String(value ?? '').replace(/\D/g, '')
  found.value = null
  notFound.value = false
}

async function onSearch() {
  if (searching.value) {
    return
  }
  // 格式校验放在发请求之前：明显不成立的输入没必要发出去（省一次请求，也少一次枚举机会）
  if (!/^1[3-9]\d{9}$/.test(phone.value)) {
    ElMessage.warning('请输入 11 位手机号')
    return
  }

  searching.value = true
  found.value = null
  notFound.value = false
  relationStatus.value = ''
  try {
    found.value = await api.searchUser(phone.value)
    // 搜到之后立刻问一次关系：按钮的文案/可点性全靠它
    await loadStatus()
  } catch (e) {
    if (e?.code === 404) {
      // 「该手机号未注册」—— 正常结果，页内空态表达（见模板里的说明）
      notFound.value = true
      notFoundMessage.value = e.message || '该手机号未注册'
    } else {
      // 其它都是真异常：400 参数、429 太频繁、503 服务不可用、-1 连不上后端
      ElMessage.error(e?.message || '搜索失败')
    }
  } finally {
    searching.value = false
  }
}

/**
 * 问后端"我和他是什么关系"。
 *
 * <p>拿不到就按陌生人处理（{@code NONE}）：最差的结果是按钮显示"添加到通讯录"，
 * 点了之后后端会用"你们已经是好友了"纠正过来 —— 比整个搜索失败好。
 */
async function loadStatus() {
  if (!found.value) {
    return
  }
  try {
    relationStatus.value = await api.friendStatus(found.value.userId)
  } catch {
    relationStatus.value = RELATION.NONE
  }
}

/** 点卡片左边 → 看详细资料（按 userId 查详情） */
function openProfile() {
  if (!found.value) {
    return
  }
  router.push({ name: 'user-profile', params: { id: String(found.value.userId) } })
}

/**
 * 按钮点击 —— 按关系状态分发。
 *
 * <p>四种情况各有去处：加好友弹对话框、对方申请过我则去"新的朋友"处理、
 * 已是好友就走发消息（消息模块还没做，所以只给提示）、自己则没有按钮。
 */
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
    // 已经是好友了 → 直接进单聊页（这一轮只有界面，消息接口还没做）
    router.push({ name: 'chat', params: { userId: String(found.value.userId) } })
  }
}

/**
 * 返回联系人页。
 *
 * <p>有上一页就真的返回（保留用户原来的浏览轨迹）；直接刷新本页时 history 里没有上一页，
 * {@code back()} 会什么都不做，所以兜底 push 一次。
 */
/** 返回上一页：实现统一在 utils/navigation.js（没有上一页时兜底回联系人页） */
const goBack = useBack('contacts')
</script>

<style scoped>
/*
  同样不写 height:100% + overflow-y:auto：滚动由外层 MainLayout 的 .main-body 负责，
  这里再套一层会出双滚动条。
*/

/* 颜色全部走 --chat-*：本页原来写死了 8 处色号，深色模式下这几处不会跟着变 */
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

/*
  搜索区。本页顶部有自绘返回栏，所以没直接用 .chat-page；
  这里用同一套间距变量，横向留白和页面其它块对齐。
  表面样式（描边/圆角/阴影）由 .chat-card 提供。
*/
.search-block {
  margin: var(--chat-page-pad) var(--chat-page-pad) 0;
  padding: 12px;
}

/*
  搜索按钮挂在 el-input 的 append 槽里（这样它和输入框永远在同一行右侧），
  但 append 默认是灰底描边，和"搜索"这个主操作的分量不匹配 ——
  所以把整块 append 染成主色。
  注意：不要动 append 的 padding —— EP 是拿它的 20px 内边距配按钮的 -20px 外边距
  撑出按钮宽度的，改了就错位。
*/
.search-block :deep(.el-input-group__append) {
  color: var(--chat-brand-fg);
  background: var(--chat-brand);
  /* 这圈灰边是 box-shadow 画的（不是 border），不清掉就会在按钮边上留一条灰线 */
  box-shadow: none;
}

/*
  按钮本身透明、颜色继承 append 的白。
  这几条必须带 :hover —— EP 自带的悬停规则会把字色改成主色，
  蓝底蓝字就等于看不见了。
*/
.search-block :deep(.el-input-group__append .el-button),
.search-block :deep(.el-input-group__append .el-button:hover),
.search-block :deep(.el-input-group__append .el-button:focus) {
  color: var(--chat-brand-fg);
  background: transparent;
  border-color: transparent;
}

/* 图标和文字要挨着：EP 的按钮图标间距只对具名 icon 插槽生效，
   这里图标塞在默认插槽里，只能自己给 */
.search-block :deep(.el-input-group__append .el-icon) {
  margin-right: 4px;
}

.tip {
  /* 字号/行高/颜色交给 .chat-muted，这里只管和输入框的距离 */
  margin: 10px 2px 0;
}

/*
  结果区：用 flex 列 + gap 统一"状态块 / 结果卡"之间的间距，
  横向留白也收在这里 —— 子块就不必各自记得写边距，加一种状态也不会错位。
*/
.result {
  display: flex;
  flex-direction: column;
  gap: var(--chat-gap);
  padding: var(--chat-gap) var(--chat-page-pad) var(--chat-page-pad);
}

.user-card {
  display: flex;
  align-items: center;
  gap: 14px;
  /* 上下小于左右：卡片本身有描边，再给大留白会显得很空 */
  padding: 12px 14px;
}

/* 卡片左边可点的那一块（头像 + 文字 + 箭头） */
.user-main {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: 1;
  min-width: 0;
  padding: 4px;
  margin: -4px;
  border-radius: var(--chat-radius-sm);
  cursor: pointer;
  transition: background-color var(--chat-dur);
}

.user-main:hover {
  background: var(--chat-surface-2);
}

.user-arrow {
  flex-shrink: 0;
  font-size: 18px;
  color: var(--chat-text-3);
}

.user-text {
  /* min-width:0 才能让长名字走省略号，而不是把按钮挤出屏幕 */
  flex: 1;
  min-width: 0;
}

.user-name {
  font-size: 16px;
  color: var(--chat-text);
  /* 省略号的三行交给 .chat-ellipsis，这里不再重复写 */
}

.user-sub {
  /* 字号/颜色交给 .chat-muted，这里只管和名字的距离 */
  margin-top: 2px;
}

.user-note {
  margin-top: 6px;
  font-size: 12px;
  /* 原来是警告橙 #e6a23c，但设计变量只有"主色 / 危险"两档语义色；
     "这是你自己"是提示不是警告，用主色比用危险色贴切 */
  color: var(--chat-brand);
}

/* 状态容器（搜索中 / 空态）统一成卡片表面，和结果卡是同一个"块" */
.state-box {
  padding: 32px 16px;
  background: var(--chat-surface);
  border: 1px solid var(--chat-border);
  border-radius: var(--chat-radius);
}

/*
  空态另外挂了 .chat-empty（居中 + 次要文字色）。
  上下 24px 是"再叠 el-empty 自带内边距"之后的量，
  直接照搬 .chat-empty 的 56px 会把空态撑成一整屏。
*/
.state-box.chat-empty {
  padding: 24px;
}
</style>
