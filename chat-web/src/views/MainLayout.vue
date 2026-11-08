<template>
  <div class="main-layout">
    <!-- 页面出口：三个 tab 各自的页面渲染在这里。
         子页面的内容以后各写各的，但"底部导航栏"只在这一处维护 -->
    <main class="main-body">
      <router-view />
    </main>

    <!--
      底部导航：三个按钮等宽平分，当前所在的页面高亮。

      路由上的 meta.hideTabBar 为真时整条藏掉（目前只有聊天页用）。
      藏起来之后靠页面自己的返回按钮离开 —— 这也是真实聊天软件的做法。

      ⚠️ 但"藏"这件事现在交给 CSS 判断（下面的媒体查询），不是写死 v-if：
      宽窗口下消息页是【双栏】，聊天时左边的会话轨道还在屏幕上，
      把底部导航整条藏掉会让应用看起来缺了一块。窄窗口才需要藏（见 <style>）。
    -->
    <!--
      实时通道不正常时的提示：不再是一条常驻的横条，而是"页面正中、红底白字"的统一提示
      （见 src/utils/toast.js）。横条会把整个页面往下挤、文案还长；提示只在状态**变化**时弹一次。
      逻辑在下面的 refreshChannelHealth 里。
    -->

    <nav class="tab-bar" :class="{ 'tab-bar--hidden': route.meta.hideTabBar }">
      <router-link
        v-for="tab in tabs"
        :key="tab.name"
        class="tab-item"
        active-class="tab-item--active"
        :to="{ name: tab.name }"
      >
        <!--
          未读角标：所有会话的未读总和。
          为什么画在【这一层】而不是消息页自己画：消息页之外也得看得见。
          以前只有消息页在监听聊天事件 —— 你在设置页/联系人页收到消息时，
          本地库和 ack 都对，但界面上一点反馈都没有，看起来就像"没收到"。
        -->
        <span v-if="tab.name === 'message' && unreadTotal > 0" class="tab-badge">
          {{ unreadTotal > 99 ? '99+' : unreadTotal }}
        </span>
        <span v-if="tab.name === 'contacts' && contactRequestCount > 0" class="tab-badge" :aria-label="`${contactRequestCount} 条待处理请求`">
          {{ contactRequestCount > 99 ? '99+' : contactRequestCount }}
        </span>
        <el-icon class="tab-icon">
          <component :is="tab.icon" />
        </el-icon>
        <span class="tab-label">{{ tab.label }}</span>
      </router-link>
    </nav>
  </div>
</template>

<script setup>
/**
 * 主页面布局：上面是页面出口，下面是底部导航栏。
 *
 * <p><b>为什么用"布局组件 + 嵌套路由"，而不是"一个页面里用变量切三个视图"？</b>
 * <ol>
 *   <li>每个 tab 都有<b>真实地址</b>（{@code #/message}、{@code #/contacts}、{@code #/settings}）：
 *       刷新、前进后退都不会丢，以后做"打开某个会话"也能直接给一个 URL。</li>
 *   <li>底部导航栏只写一份 —— 三个页面各写一遍的话，改个样式要改三处。</li>
 *   <li>切 tab 只换 router-view 里的内容，布局组件本身会被复用，不会整页重建。</li>
 * </ol>
 *
 * <p>tab 的 <b>name 必须和路由的 name 一致</b>（见 router/index.js）：
 * 这里用 {@code :to="{ name: tab.name }"} 跳转，比手拼 {@code '/message'} 更安全 ——
 * 以后改路径（比如加一层 {@code /main/message}）只需要改路由，这里一行都不用动。
 *
 * <p>用 {@code <router-link>} 而不是 {@code <div @click="router.push">}：
 * 它自带"当前路由高亮"（active-class）和键盘可访问性（是个 &lt;a&gt;，能 Tab 聚焦、回车进入）。
 *
 * <p><b>图标为什么写在数据里</b>：tab 的"图标 + 文案 + 路由名"是三件必须一致的东西，
 * 分成三个地方写迟早会错位（加了 tab 却忘了图标）。放一个数组里，加 tab 只加一行。
 */
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Setting, User } from '@element-plus/icons-vue'
import { UNREACHABLE_TEXT } from '../api'
import { getChannelHealth, onChatEvent } from '../utils/chatSync'
import { startConversationStore, stopConversationStore, unreadTotal } from '../utils/conversationStore'
import { notify } from '../electron'
import { pendingFriendRequestCount, pendingGroupInvitationCount, startRequestReminders, stopRequestReminders } from '../utils/requestReminderStore'

const contactRequestCount = computed(() => pendingFriendRequestCount.value + pendingGroupInvitationCount.value)

const route = useRoute()

const tabs = [
  { name: 'message', label: '消息', icon: ChatDotRound },
  { name: 'contacts', label: '联系人', icon: User },
  { name: 'settings', label: '设置', icon: Setting },
]

// ==================== 实时通道健康度 ====================

/**
 * 上一次已经提示过的"那句话"（避免同一个问题每 30 秒弹一次）。
 *
 * <p>为什么按文案去重、而不是按状态：半死、断线超时、HTTP 请求失败都说
 * 「无法连接至服务器」，对用户来说是同一件事，不该弹三次。
 * 空串表示"当前没有提示过任何问题"，连接恢复时会复位 ——
 * 这样下次真的又坏了还能再弹一次。
 */
let lastWarnedState = ''

/**
 * 把通道状态翻译成一句**短**提示（空串 = 不值得打扰）。
 *
 * <p>长短是刻意的：提示只负责"告诉你出事了"，具体原因看 console。
 * {@code closed} / {@code reconnecting} 这类"正在重连"的状态不在这里出文案 ——
 * 网络抖一下就红一句反而更烦人，而且它一般会在几百毫秒内自己好。
 * 但如果<b>一直</b>连不上，用户必须知道，那条判断在 {@link refreshChannelHealth} 里
 * （等 {@link DISCONNECT_TIP_DELAY_MS} 还没好就报"无法连接至服务器"）。
 */
function channelTip(state) {
  switch (state) {
    case 'kicked':
    case 'kicked-waiting':
      return '账号在别处登录，收不到推送'
    case 'no-module':
      return '没有实时通道，收不到推送'
    case 'no-token':
      return '尚未登录，实时通道未建立'
    case 'open':
      // 状态是 open 却判为不健康 → 只可能是"半死"（很久没收到任何帧，连 pong 都没有）
      return '实时通道无响应，已改用定时拉取'
    default:
      return ''
  }
}

/**
 * 断线多久之后才提示"连不上"。
 *
 * <p>为什么要等：切网络、合盖唤醒、网关重启都会让 WS 闪断一下，通常一两秒自己就好了。
 * 立刻弹红块会让人以为出了大问题。等一会儿还没好，那就真的是连不上。
 */
const DISCONNECT_TIP_DELAY_MS = 8_000

/** 从什么时刻开始一直断着（0 = 当前并没有处于断线） */
let disconnectedSince = 0

/** 延迟提示用的定时器（连上就取消） */
let disconnectTimer = null

/** 半死状态不会有事件通知，只能自己定期看一眼 */
const HEALTH_CHECK_MS = 30_000

/** 健康度轮询定时器 */
let healthTimer = null

/** 取消"等会儿再提示"的计时（连上 / 卸载时都要调） */
function clearDisconnectTimer() {
  if (disconnectTimer) {
    clearTimeout(disconnectTimer)
    disconnectTimer = null
  }
}

/**
 * 同一句话只提示一次（连接恢复后由调用方复位）。
 *
 * <p>为什么按"文案"而不是按"状态"去重：半死、断线超时、HTTP 请求失败都会说
 * {@code 无法连接至服务器}，对用户来说是同一件事，不该弹三次。
 */
function tipOnce(text) {
  if (!text || text === lastWarnedState) {
    return
  }
  lastWarnedState = text
  ElMessage.error(text)
}

function refreshChannelHealth() {
  const health = getChannelHealth()
  /*
    状态还是 null 表示"chatSync 还没报过任何状态"（比如页面刚挂载、或者压根没启动同步）。
    这时候不该提示 —— 否则每次进主页都会先闪一下"未连接"，是假警报。
  */
  if (health.state == null) {
    lastWarnedState = ''
    return
  }

  if (health.reliable) {
    // 恢复正常：把"提示过的状态"和断线计时一起清掉，下次再坏还能再提醒一次
    clearDisconnectTimer()
    disconnectedSince = 0
    lastWarnedState = ''
    return
  }

  /*
    正在重连 / 刚断开：先不打扰，但开始计时。
    超过 DISCONNECT_TIP_DELAY_MS 还是这个状态，就说明不是"抖一下"，而是真的连不上后端
    （网关没起、网断了、端口不对……），必须明确告诉用户 —— 否则界面会一直"看起来很安静"，
    而消息其实一条都收不进来。
  */
  if (health.state === 'closed' || health.state === 'reconnecting') {
    if (!disconnectedSince) {
      disconnectedSince = Date.now()
      clearDisconnectTimer()
      disconnectTimer = setTimeout(() => {
        disconnectTimer = null
        refreshChannelHealth()
      }, DISCONNECT_TIP_DELAY_MS)
      return
    }
    if (Date.now() - disconnectedSince >= DISCONNECT_TIP_DELAY_MS) {
      tipOnce(UNREACHABLE_TEXT)
    }
    return
  }

  // 其余状态（被顶号 / 没有主进程 / 没有 token / 半死）语义明确，立刻提示。
  // 顺手把"断线计时"清掉：这些状态不再是"正在重连"，留着旧时间点会让下次真断线时
  // 一上来就超过阈值、立刻弹提示（我们要的是"抖一下不打扰"）
  clearDisconnectTimer()
  disconnectedSince = 0
  tipOnce(channelTip(health.state))
}

// ==================== 全局未读 ====================
//
// 未读总和（底部导航上的角标）现在来自 utils/conversationStore.js 的共享状态 ——
// 消息页的列表用的是同一份，所以"什么时候该重算"只有那一处说了算。
// 以前这里是各读各的，结果漏过两次（读完消息没人通知角标、补拉结束没人更新角标）。

/**
 * 收到新消息时弹桌面通知 —— 但只在"用户没在看"的时候。
 *
 * <p>三个不该打扰的情况：
 * <ol>
 *   <li>窗口没有焦点（用户正在别的应用里）→ <b>这正是该提醒的时候</b>，反而要弹；</li>
 *   <li>用户正开着一模一样那个会话（那就是眼前的对话，再弹通知是噪音）；</li>
 *   <li>纯浏览器里跑（没有 Electron）→ notify 静默返回 false。</li>
 * </ol>
 *
 * <p>通知正文只放消息内容（截断）：推送来的 VO 里没有发送者昵称
 * （服务端为了省一次 Feign 调用刻意不带），要显示名字就得再查一次，
 * 不值得 —— Windows 的通知里本来也只够看一句话。
 */
function maybeNotify(message) {
  if (!message || typeof message.content !== 'string') {
    return
  }
  // 用户正盯着这个会话：不打扰
  const viewingThisChat = (message.groupId != null
    ? route.name === 'group-chat' && Number(route.params.groupId) === message.groupId
    : route.name === 'chat' && Number(route.params.userId) === message.senderUserId)
    && typeof document !== 'undefined' && document.hasFocus()
  if (viewingThisChat) {
    return
  }
  const text = message.content.replace(/\s+/g, ' ').slice(0, 60)
  notify('新消息', text).catch(() => {})
}

/** 取消对全局聊天事件的订阅 */
let unsubscribeChat = () => {}

onMounted(() => {
  startRequestReminders()
  // 让共享的会话列表开始维护（引用计数：消息页那边也在用同一份）。
  // 它内部订阅 message / synced / status:open / localRead 四种事件，
  // 所以角标（unreadTotal）和列表永远是同一次刷新算出来的。
  startConversationStore()

  // 通道健康度：先拿一次初始值，之后靠 status 事件 + 30 秒兜底轮询
  //（"半死"这种状态不会有事件，只能自己看）
  refreshChannelHealth()
  healthTimer = setInterval(refreshChannelHealth, HEALTH_CHECK_MS)

  /*
    这里只留两个本层才关心的事：
      · 新消息 → 弹桌面通知（任何页面都该有）；
      · 通道状态 → 重新判断要不要弹一句红色 toast。
    未读重算不在这里做了 —— 它在 conversationStore 里，和列表一起。
  */
  unsubscribeChat = onChatEvent((event) => {
    if (event.kind === 'message' || event.kind === 'group-message') {
      maybeNotify(event.message)
    } else if (event.kind === 'status') {
      refreshChannelHealth()
    }
  })
})

onBeforeUnmount(() => {
  stopRequestReminders()
  unsubscribeChat()
  stopConversationStore()
  // 取消"断线多久之后提示"的计时器：页面都卸载了，再弹就是往一个不存在的界面写东西
  clearDisconnectTimer()
  if (healthTimer) {
    clearInterval(healthTimer)
    healthTimer = null
  }
})
</script>

<style scoped>
/*
  竖向 flex：内容占满剩余高度，底部导航固定占自己那一行。

  为什么不用 position: fixed 钉底部？fixed 会让导航栏脱离文档流，
  内容会被它盖住，还得给内容手动加 padding-bottom 去躲 —— flex 没这个问题。
  （能这么写的前提是 #app 有 height: 100%，见 src/style.css）
*/
.main-layout {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.main-body {
  /* flex:1 = 把除导航栏外的空间全占了 */
  flex: 1;
  /* 内容超长时只在内容区滚动，导航栏始终留在屏幕上 */
  overflow-y: auto;
  /*
    横向明确禁止。只写 overflow-y 时 CSS 会把另一轴算成 auto，
    内容横向溢出 1px 就会在窗口底部多出一条水平滚动条（实测就是那条"莫名其妙的细条"）。
  */
  overflow-x: hidden;
}

.tab-bar {
  /* 不参与压缩：内容再长也压不掉导航栏 */
  flex-shrink: 0;
  display: flex;
  /* 颜色一律走变量：深色模式要跟着变（变量的定义和切换见 src/style.css） */
  border-top: 1px solid var(--chat-border);
  background: var(--chat-surface);
}

/* 聊天页要求藏掉底部导航（窄窗口下输入框要贴底，压一排 tab 会很挤） */
.tab-bar--hidden {
  display: none;
}

/*
  但宽窗口下要重新显示：那时消息页是双栏，左边的会话轨道还在，
  底部导航整条消失会让应用看起来缺了一块（而且用户切不回"联系人/设置"）。
  断点和 MessageLayout 的双栏断点保持一致 —— 两个断点不一致会出现
  "已经是双栏了、导航却还藏着"的中间状态。
*/
@media (min-width: 1024px) {
  .tab-bar--hidden {
    display: flex;
  }
}

.tab-item {
  /* flex:1 让三个按钮等宽平分整行 —— 这就是"均匀排开" */
  flex: 1;
  /*
    图标在上、文字在下：只有文字时"选中"很难一眼看出来（尤其是中文），
    加了图标之后颜色一变就能扫到，这也是主流 App 的底部导航做法。
  */
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: 7px 0 8px;
  font-size: 11px;
  /* 未读角标要绝对定位到图标右上角，所以这里得有定位上下文 */
  position: relative;
  /* 未选中用最弱的一档灰，选中才用主色 —— 对比拉得开，视线才有落点 */
  color: var(--chat-text-3);
  /* router-link 渲染成 <a>，默认带下划线和蓝色，这里去掉 */
  text-decoration: none;
  cursor: pointer;
  /* 点按不要出现移动端那种灰色高亮块 */
  -webkit-tap-highlight-color: transparent;
  transition: color var(--chat-dur), background-color var(--chat-dur);
}

/*
  未读角标。
  定位在图标右上角：tab 是 flex 居中布局，直接放在流里会把图标和文字挤歪；
  用绝对定位它就不参与布局，有没有角标都不影响另外两个 tab 的对齐。
*/
.tab-badge {
  position: absolute;
  top: 4px;
  left: 50%;
  /* 50% 定位到中间后再向右推一点，压在图标右上角 */
  transform: translateX(10px);
  min-width: 16px;
  padding: 0 4px;
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  text-align: center;
  color: var(--el-color-white);
  background: var(--chat-danger);
  border-radius: 8px;
  /* 描一圈和导航栏同色的边，压在图标上也看得清数字 */
  box-shadow: 0 0 0 2px var(--chat-surface);
}

.tab-icon {
  font-size: 20px;
}

.tab-item:hover {
  /* 用"页面底色"当悬停色：浅色下是浅灰、深色下是更深的黑，两边都成立 */
  background: var(--chat-page-bg);
  color: var(--chat-text-2);
}

/* 当前所在的 tab */
.tab-item--active {
  color: var(--chat-brand);
}

/*
  选中时图标轻轻上移 1px：比再加一条顶部高亮条更含蓄，
  而且不会像高亮条那样在图标+文字的布局里显得压住内容。
*/
.tab-item--active .tab-icon {
  transform: translateY(-1px);
}
</style>
