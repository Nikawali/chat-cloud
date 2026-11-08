<template>
  <div class="chat-page">
    <!-- 顶部：返回 + 名字（有备注显示备注，没有显示昵称） -->
    <ChatHeader :title="peerTitle || '单聊'" :items="singleMenuItems" @back="goBack" @command="onHeaderCommand" />

    <!-- 消息区：唯一会滚动的地方。@scroll 用来做"滚到顶再往上翻一页"（见 onBodyScroll） -->
    <div ref="bodyRef" class="chat-body" @scroll.passive="onBodyScroll">
      <!--
        占位撑杆：消息少的时候把内容压到底部（贴着输入框往上排），而不是贴顶。
        这是聊天软件的通行做法 —— 贴顶会让"只聊了两句"的会话看起来像页面坏了。
        用 flex:1 的撑杆，而不是给容器 justify-content:flex-end：后者在可滚动容器里
        会把超出部分从顶部裁掉（Chromium 的老问题），撑杆没有这个副作用；
        内容撑满时它会自己缩成 0。
      -->
      <div class="body-spacer" />

      <div v-if="loading" class="state-box">
        <el-skeleton :rows="4" animated />
      </div>

      <div v-else-if="messages.length === 0" class="chat-empty">
        还没有消息{{ peerTitle ? `，和「${peerTitle}」说点什么吧` : '' }}
      </div>

      <template v-else>
        <!--
          v-for 走的是 renderItems（= 消息 + 插入的时间分隔条），不是 messages 本身。
          分隔条是"和消息同构的伪对象"（{ kind:'time', key, text }），所以下面每个
          message.xxx 的引用都不用改 —— 真正渲染消息时拿到的还是原来那个对象。
          规则见 renderItems 的注释（5 分钟 + 跨天）。
        -->
        <template v-for="message in renderItems" :key="message.key">
          <div v-if="message.kind === 'time'" class="msg-time">{{ message.text }}</div>

          <ChatMessageRow v-else :mine="message.mine" :avatar-url="avatarOf(message)" :avatar-name="avatarNameOf(message)"
            :media="message.messageType === 2 || message.messageType === 3" :status="statusOf(message)"
            :status-text="statusText(statusOf(message))" :error="message.error" @retry="onResend(message)">
            <ChatMessageContent :message="message" :media="media" />
          </ChatMessageRow>
        </template>
      </template>
    </div>

    <!-- 输入区：钉在底部 -->
    <ChatComposer v-model="draft" :sending="sending" @send="onSend" @attachment="onAttachCommand">
      <!-- 隐藏的选文件控件：图片/文件共用一个 input，打开前按类型改 accept 而已 -->
      <input
        ref="fileInputRef"
        class="file-input"
        type="file"
        @change="onAttachmentPicked"
      />
    </ChatComposer>
    <el-drawer v-model="singleInfoVisible" class="chat-details-drawer" title="会话信息" size="min(360px, 90vw)">
      <div class="single-info"><el-avatar :size="64" :src="assetUrl(peer?.avatarUrl)">{{ firstChar(peerTitle) }}</el-avatar><h3>{{ peerTitle || '单聊' }}</h3><p>好友 ID：{{ peerId }}</p><p v-if="conversationId">会话 ID：{{ conversationId }}</p><el-button type="primary" plain @click="openPeerProfile">查看好友资料</el-button></div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
// 只加图标 import，不碰任何业务逻辑（icons 是 element-plus 的硬依赖，不新增体积负担）
import { Document, Picture, VideoPlay } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import ChatHeader from '../components/ChatHeader.vue'
import ChatComposer from '../components/ChatComposer.vue'
import ChatMessageRow from '../components/ChatMessageRow.vue'
import ChatMessageContent from '../components/ChatMessageContent.vue'
import { configureAttachmentInput } from '../utils/attachmentUpload'
import { useMessageMedia } from '../utils/useMessageMedia'
import { buildMessageTimeline } from '../utils/messageTimeline'
import '../styles/chatMessages.css'
import { api, assetUrl, UNREACHABLE_TEXT } from '../api'
import { ensureMe, getFriend, me } from '../utils/accountData'
import { hasChatSocket, newClientMessageId, sendChatFrame } from '../utils/chatSocket'
import { onChatEvent } from '../utils/chatSync'
import { clearUnreadForPeer, conversations } from '../utils/conversationStore'
import { firstChar } from '../utils/display'
import { isWindowFocused } from '../utils/env'
import {
  appendLocalMessages,
  loadLocalMessages,
  markLocalRead,
  onLocalReceipt,
  raiseLocalReceipt,
  readLocalMedia,
  saveLocalMedia,
} from '../utils/messageStore'
import { useBack } from '../utils/navigation'
import { addPending, listPending, removePending } from '../utils/pendingMessages'

/**
 * 单聊页（{@code /message/chat/:userId}）。
 *
 * <h3>消息发送与接收均走 WebSocket，上传和补拉仍走 HTTP</h3>
 * <ul>
 *   <li><b>发消息走 WS send-message</b>：等待匹配 clientMessageId 的 send-result 受理结果
 *       （服务端 messageId）才能把界面从"发送中"改成"已发送"，错误也能统一处理。</li>
 *   <li><b>收消息走 WebSocket</b>：连接建在 Electron 主进程里（见 electron/chat-socket.cjs），
 *       页面只通过 IPC 收帧。</li>
 *   <li>收到消息后要回 <b>ack</b>（表示"我存好了"），服务端才会停止重推并通知发送方"已送达"。</li>
 * </ul>
 *
 * <h3>三件真实聊天软件都会做的事，这里也做了</h3>
 * <ol>
 *   <li><b>乐观更新</b>：点发送先把消息画到界面上（状态"发送中"），WS 受理结果回来再用服务端的
 *       messageId 替换它 —— 否则输入框会"卡一下"。</li>
 *   <li><b>按 messageId 去重 + 排序</b>：重推和补拉必然造成重复和乱序，
 *       按到达顺序渲染就会出现"同一句话出现两次"。</li>
 *   <li><b>落盘成功才回 ack</b>：只有收到了 ack，服务端才停止重推（每 1/2/4…秒一次，最多 3 分钟）。
 *       这一步在全局模块里做（chatSync.js），本页不碰。</li>
 * </ol>
 *
 * <h3>只读本地</h3>
 * 进会话时<b>只读本地库</b>（秒开、断网也能翻），<b>不去服务端拉历史</b>。
 * 本地库只是缓存（见 electron/message-store.cjs）：它缺的那段，等以后的"同步"功能回源
 * （{@link loadHistory} 留着就是给那个功能的，现在没有调用点）。
 *
 * <h3>"已送达 / 已读"为什么存水位而不是逐条状态</h3>
 * 服务端回执给的本来就是"到某条为止"（{@code upToMessageId}），所以本地也只记两个水位
 * （{@link deliveredUpTo} / {@link readUpTo}）：天然单调、乱序回执不会把它推回去，
 * 而且只有一份数据 —— 逐条存状态的话，同一件事就存了两份，早晚对不上。
 */
const props = defineProps({
  userId: { type: String, required: true },
})

// 本页原来自己 new 了一个 router，只给 goBack 用；现在返回逻辑统一在 utils/navigation.js
const goBack = useBack('message')
const router = useRouter()
const singleInfoVisible = ref(false)
const singleMenuItems = [{ command: 'profile', label: '查看好友资料' }, { command: 'info', label: '会话信息' }]
function openPeerProfile() { router.push({ name: 'user-profile', params: { id: String(props.userId) } }) }
function onHeaderCommand(command) {
  if (command === 'profile') openPeerProfile()
  else if (command === 'info') singleInfoVisible.value = true
}

const peerId = computed(() => Number(props.userId))

/** 对方：来自账号级缓存（好友 map）。不是好友时是 null，标题就空着（不发请求）。 */
const peer = computed(() => getFriend(peerId.value))

const messages = ref([])
const draft = ref('')
const loading = ref(true)
const sending = ref(false)
const bodyRef = ref(null)
const media=useMessageMedia(bodyRef)
const {attachmentObjectUrls,saveOwnAttachmentLocally}=media
/** 隐藏的文件选择控件（附件按钮点它） */
const fileInputRef = ref(null)

/**
 * 正在下载的附件（消息 key 的集合）。
 *
 * <p>为什么需要它：附件是"点了才下载"，一份 25MB 的视频可能要等几秒。
 * 没有这个集合，用户会以为没点中、于是连点好几下 —— 每一下都是一次下载请求。
 */

/**
 * 已经"探过本地有没有副本"的消息 key（{@link probeLocalMedia} 也只探一次）。
 *
 * <p>用它区分占位块上的两种文案：还没探 → "正在加载"；探过且没有 → "点击加载"。
 */

/** 附件可见性观察器（滚到附近才探测本地副本，见 {@link vLocalMedia}） */

/** 观察器只能拿到元素，用它反查是哪条消息 */

/**
 * "这个附件打不开"的消息 key 集合（下载成功但内容坏了，或者下载失败）。
 *
 * <p>为什么需要它：本地副本可能被删、网络也可能断，素材取不到时界面上只有一块空白 ——
 * 看起来和"界面坏了"一模一样。记住失败的 key 之后，占位块上会写"加载失败，点击重试"。
 */

/**
 * 还没上传成功的附件本体（clientMessageId → File）。
 *
 * <p>为什么要在内存里留一份：图片/文件消息的"点击重发"需要原始字节 —— 只留 URL 是没用的
 * （那时候还没上传成功，压根没有 URL）。这也是为什么附件<b>不进 pendingMessages</b>：
 * 那个暂存放在 localStorage 里，存不下文件字节，重启后恢复出来也没法重发。
 */
const attachmentFiles = new Map()

/** 本地预览用的 blob URL：页面卸载时要统一 revoke，否则这些字节会一直被浏览器扣着 */

/**
 * 附件本体（图片/文件字节）解析出来的 blob URL：文件名 → objectURL。
 *
 * <p>为什么要缓存：同一个文件可能被多条消息引用（转发），也可能在列表里反复进出视口，
 * 每次都 IPC 读盘 + 建 blob 太浪费；而且 blob 一旦建出来，渲染就是同步的。
 *
 * <p><b>⚠️ 必须用 reactive(Map)，不能用普通 Map</b>：
 * 附件是"点了才下载"，下载完成的信号就是往这里写一个 blob URL。
 * 普通 Map 的写入**不会触发重新渲染**，于是"字节已经到了、界面还挂着占位块"，
 * 看起来就是"点了没反应"。
 */

/**
 * 本地附件文件名的白名单（和服务端签发规则一致）。
 *
 * <p>它会被拼进路径，所以这里也挡一道 —— 主进程那边还会再校验一次
 * （外部传进来的字符串进路径前都要净化，这是同一条纪律）。
 */

/** 单个附件上限：和服务端 spring.servlet.multipart / max-attachment-bytes 保持一致 */
const MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024

/**
 * 当前会话 id（服务端定的）。
 *
 * <p>它只有一个用途：<b>挡住别的会话的回执</b>。回执帧带 conversationId，
 * 而"已送达/已读"是一次性的推送，以后有了会话列表，这条通道会同时送来别的会话的回执 ——
 * 拿别人的 upTo 来推本会话的水位，界面上的勾就全乱了。
 * 来源有三处：本地快照、history 里带的、自己发消息的受理结果。
 */
const conversationId = ref(null)

/**
 * 已送达 / 已读【水位】：我发的消息里，messageId <= 这个值的都送达/已读了。
 *
 * <p>为什么用水位而不是给每条消息存时间戳：
 *   · 服务端回执给的就是"到某条为止"（upToMessageId），存水位是原样保留语义；
 *   · 水位<b>天然单调</b>，乱序到达的回执不会把状态推回去（不需要"只升不降"那套比较）；
 *   · 本地只有这一份状态，不会出现"逐条状态和水位打架"。
 *
 * <p>初值来自本地库（<b>离线打开也能显示正确的勾</b>），之后由回执帧和 history 推进。
 * 只有服务端确认过的消息才看它；本地还没发出去的那些（messageId 为 null）看对象上的 status。
 */
const deliveredUpTo = ref(0)
const readUpTo = ref(0)

/**
 * 待上报的已读水位 + 合并用的定时器（见 {@link scheduleReadReport}）。
 *
 * <p>为什么要合并：补拉一页最多 50 条，推送也可能连着来。如果收到一条就报一次已读，
 * 就是几十个 POST（每个请求 3 次 UPDATE）。而读游标是单调递增的 —— 只报最大的那一条就够了。
 */
let readReportTimer = null
let readReportUpTo = null

/** 合并窗口：这么短时间内到达的消息合成一次上报 */
const READ_REPORT_MERGE_MS = 300

/** 取消对全局聊天事件的订阅（页面卸载时调）。
 *  注意【只取消订阅，不做别的】—— 收消息、回 ack、补拉都由全局同步模块负责，
 *  页面关闭不该影响它们（否则"没打开会话就永远不确认送达"这个洞又回来了）。 */
let unsubscribeChat = () => {}

/** 取消对"本地回执水位被推进"的订阅（页面卸载时调） */
let unsubscribeReceipt = () => {}

/** 标题：备注优先，没有才显示昵称 —— 备注是我自己起的，我更认得它 */
const peerTitle = computed(() => peer.value?.remark || peer.value?.nickName || '')

/* 实时通道异常的提示统一改成页面中间的红色 toast（见 src/utils/toast.js），
   由 MainLayout.vue 全局监听通道状态后弹出；聊天页不再自己画横条 ——
   同一件事只有一个提示出口，位置和样式才不会各页各样。 */

onMounted(async () => {
  // ① 先订阅全局同步派发的事件（新消息 / 已送达 / 已读）——【必须在第一个 await 之前】。
  //    下面读本地要等一次 IPC，而 WS 的帧随时可能到：
  //    订阅晚一步，那个窗口里到达的消息就被丢掉了。
  //    本页只负责"显示"和"上报已读"；ack 和补拉在 chatSync.js 里做。
  unsubscribeChat = onChatEvent(handleChatEvent)

  // ①b 也订阅"本地回执水位被推进"（同样要在第一个 await 之前）。
  //     为什么需要它：水位有两条来源 —— WS 回执帧（上面那类事件）、以及重连时
  //     /conversations 批量写回本地库（这条路**没有**任何 WS 事件）。
  //     少了这个订阅，"重连时正开着会话"就会出现"数据对了、勾还是旧的"。
  unsubscribeReceipt = onLocalReceipt(onReceiptRaised)

  // ② 只读本地库，而且只取最近一页：本地有多少先画多少（秒开、断网也能翻），
  //    更早的等用户往上滚再取（见 loadOlderLocal）。不去服务端拉历史。
  //    ⚠️ 这一次可能读了个空：本地库在"身份未定"时是 fail-closed 的（读一律当空）。
  //    所以拿到身份之后还要再读一次（见 ③）。有内容就先画出来，别等网络。
  await loadLocal()
  if (messages.value.length > 0) {
    loading.value = false
    scrollToBottom()
  }

  // ③ 先确保"我是谁"可用 —— 它同时会把本地库的归属定下来（见 accountData.refreshMe），
  //    然后再补读一次本地库。少了这次补读，"实时通道没连上时开 App"会一直显示空会话。
  await ensureMe()
  await loadLocal()
  loading.value = false
  scrollToBottom()
  // 一页撑不满一屏就再取一页（否则滚不动，也就永远翻不到更早的消息）
  await fillBodyIfShort()
  scrollToBottom()

  // ④ 恢复"上次没发出去的消息"（pending 按 ownerUserId 隔离，得先知道"我是谁"）
  await restorePending()

  // 窗口重新可见 / 重新聚焦时补一次已读上报：
  // 不可见时到达的消息不会被立刻标成已读（见 scheduleReadReport），等人回来了再报
  document.addEventListener('visibilitychange', onForegroundRead)
  window.addEventListener('focus', onForegroundRead)

  // 打开会话就把已读报掉（对方界面上的勾会变蓝）
  reportReadIfNeeded()
})

onBeforeUnmount(() => {
  unsubscribeChat()
  unsubscribeReceipt()
  // 先把这个会话"已经排好但还没报出去"的已读补上（否则会留下一个永远不消的红点），
  // 再把定时器和全局监听清掉：否则跳走之后它还会往一个已经卸载的页面写状态
  flushReadReport()
  document.removeEventListener('visibilitychange', onForegroundRead)
  window.removeEventListener('focus', onForegroundRead)
  // 释放还没上传成功的图片预览、以及本地附件解析出来的 blob URL：
  // blob URL 会让浏览器一直扣着那份文件数据，不 revoke 就回收不掉

})

// ==================== 加载 ====================

/**
 * 这条消息是不是我发的。
 *
 * <p>正常情况下就是和 {@code me.userId} 比（me 来自账号级缓存，登录时已拉好）。
 * 但缓存有可能一瞬间还没到（网络抖、后端刚重启），而<b>本地历史不能因为这个就
 * 全部画到左边去</b>。单聊只有两个人，所以兜底判断成立：不是对方发的，就是我发的。
 */
function isMine(senderUserId) {
  if (senderUserId == null) {
    return false
  }
  if (me.value?.userId != null) {
    return senderUserId === me.value.userId
  }
  return senderUserId !== peerId.value
}

/**
 * 每次从本地库取多少条。
 *
 * <p>进会话只取最近一页（而不是把整段历史一次读出来再全画出来），
 * 往上滚到顶再取更早的一页 —— 见 {@link loadOlderLocal}。
 */
const PAGE_SIZE = 30

/** 本地库里还有更早的消息（上一页取满了就为真；界面据此决定还要不要继续往上翻） */
const hasMoreLocal = ref(false)

/** 正在读上一页：滚动事件会连着来好几次，挡住重复触发（否则会同时读三页） */
let loadingOlder = false

/** 离顶部多近就预读上一页：留一点余量，滚到顶时内容已经接上了 */
const LOAD_OLDER_THRESHOLD_PX = 120

/**
 * 加载本地库里的这个会话（进入会话只读本地）。
 *
 * <p>本地库只是缓存，所以<b>读不到、读坏了都当"没有本地记录"</b>：
 * 这段缺的内容等以后的"同步"功能回源（见 loadHistory 上方那段说明），
 * 现在不联网、不报错。
 *
 * <p><b>注意这里是"合并"而不是"覆盖"</b>：本函数 await 过一次磁盘读，
 * 而事件订阅在它之前就注册好了 —— 这段时间里完全可能有实时消息已经进了 {@code messages}，
 * 直接赋值会把它们冲掉。（同一个坑在 {@link mergeServerMessages} 里也处理过。）
 *
 * @param {number} [size] 这次取多少条（默认 PAGE_SIZE；传 0/省略且要全量时见 restorePending）
 */
async function loadLocal(size = PAGE_SIZE) {
  const snapshot = await loadLocalMessages(peerId.value, size ? { size } : {})
  if (conversationId.value == null && snapshot.conversationId != null) {
    conversationId.value = snapshot.conversationId
  }
  // 取 max 而不是直接赋值：读盘期间可能有回执帧到达并推进过水位，
  // 直接赋值会把刚推进的水位退回去（勾会闪一下）
  deliveredUpTo.value = Math.max(deliveredUpTo.value, snapshot.deliveredUpTo)
  readUpTo.value = Math.max(readUpTo.value, snapshot.readUpTo)
  hasMoreLocal.value = snapshot.hasMore

  mergeLocalMessages(snapshot.messages)
}

/**
 * 把本地库读出来的一批消息并进 {@code messages}（按 messageId 去重 + 排序）。
 *
 * @param {Array<object>} list 本地库里的消息（字段和服务端 VO 同构）
 * @returns {number} 真正新增了几条
 */
function mergeLocalMessages(list) {
  if (!Array.isArray(list) || list.length === 0) {
    return 0
  }
  const known = new Set(messages.value.filter((m) => m.messageId != null).map((m) => m.messageId))
  let added = 0
  for (const vo of list) {
    if (vo.messageId == null || known.has(vo.messageId)) {
      continue
    }
    const message = toClientMessage(vo)
    // 本地库里 senderUserId 为空的，只可能是【我发的】（收到的消息一定带着发送者）——
    // 那是"发消息时 /user/me 还没回来"留下的痕迹。读回来时必须认得出来，
    // 否则自己的话会被画到左边去，看着像对方说的。
    if (message.senderUserId == null) {
      message.mine = true
    }
    messages.value.push(message)
    known.add(vo.messageId)
    added++
  }
  if (added > 0) {
    messages.value.sort(byMessageId)
  }
  return added
}

/**
 * 往上翻一页更早的消息（<b>只读本地库，不发网络请求</b>）。
 *
 * <p>两个细节缺一个都会让体验崩掉：
 * <ol>
 *   <li><b>把视觉位置钉住</b>：新内容全部插在列表顶部，浏览器会保持 scrollTop 不变，
 *       于是屏幕上的内容会突然往下跳一屏、用户以为看丢了。所以要在插入前记下
 *       scrollHeight/scrollTop，插入后把差值补回去。</li>
 *   <li><b>先记高度再 await</b>：等 IPC 回来时 DOM 可能已经被别的事改过，
 *       那时候再取 scrollHeight 就晚了。</li>
 * </ol>
 */
async function loadOlderLocal() {
  if (loadingOlder || !hasMoreLocal.value) {
    return
  }
  // 当前最旧的一条（messages 一直按 messageId 升序），翻页锚点就是它
  const oldest = messages.value.find((m) => m.messageId != null)
  if (!oldest) {
    return
  }

  loadingOlder = true
  const body = bodyRef.value
  const prevHeight = body ? body.scrollHeight : 0
  const prevTop = body ? body.scrollTop : 0
  try {
    const snapshot = await loadLocalMessages(peerId.value, {
      size: PAGE_SIZE,
      beforeMessageId: oldest.messageId,
    })
    hasMoreLocal.value = snapshot.hasMore
    const added = mergeLocalMessages(snapshot.messages)
    if (added > 0) {
      await nextTick()
      if (body) {
        body.scrollTop = prevTop + (body.scrollHeight - prevHeight)
      }
    }
  } catch (e) {
    // 读盘失败不影响已经显示的内容，下次滚到顶还会再试
    console.warn('[chat] 读取更早的本地消息失败', e)
  } finally {
    loadingOlder = false
  }
}

/** 消息区滚动：快滚到顶部就预读上一页 */
function onBodyScroll() {
  const body = bodyRef.value
  if (!body) {
    return
  }
  if (body.scrollTop <= LOAD_OLDER_THRESHOLD_PX) {
    loadOlderLocal()
  }
}

/**
 * 内容还没填满一屏时继续往上取。
 *
 * <p>为什么需要：一页 30 条如果都是很短的一行字、窗口又很高，可能压根撑不满、也就<b>滚不动</b> ——
 * 滚动事件不会触发，用户永远看不到更早的消息。所以进会话后检查一次高度，不够就再取一页。
 */
async function fillBodyIfShort() {
  const body = bodyRef.value
  if (!body) {
    return
  }
  let guard = 0
  while (hasMoreLocal.value && guard < 5 && body.scrollHeight <= body.clientHeight + 4) {
    guard++
    const before = messages.value.length
    await loadOlderLocal()
    if (messages.value.length === before) {
      break
    }
  }
}

/*
 * ══════════════════════════════════════════════════════════════════════
 *  【暂不调用】服务端历史：GET /message/history
 * ══════════════════════════════════════════════════════════════════════
 *
 * 现在的规矩是【进入会话只读本地库】，不拉历史。本地缺的那一段（换机器 / 在别处聊过 /
 * 本地库上线之前的历史）在这个阶段会显示不出来 —— 这是"只读本地"这个决定的直接代价，
 * 等以后的"同步"功能来补。
 *
 * 下面这几个函数目前【没有调用点】，保留是因为：
 *   · 后端 /history 接口还在，以后做"同步 / 往上翻页"都要用它；
 *   · 这个项目没有 git，删掉就真没了。
 *
 * 【以后做同步时的接法】
 *   · 打开会话回源一次：在 onMounted 的 loadLocal() 之后，判断本地有没有内容再调 loadHistory()；
 *   · 重连对账：在 handleChatEvent 的 'status:open' 分支里调 loadHistory(true)。
 *   mergeServerMessages 已经处理了去重 / 清本地占位 / 水位落盘，直接调就行。
 */

/**
 * 从服务端拉一次最近的记录，和本地那份<b>合并</b>（不是覆盖）。
 *
 * <p>为什么不能覆盖 {@code messages}：本地有服务端这次不会返回的东西 ——
 * 还没发出去的本地消息（messageId 为 null）、以及比这 50 条更早的历史。
 * 直接赋值会把它们一起冲掉。
 *
 * <p>⚠️ {@code size} 给 50（服务端默认 20、上限 100）：本地库能把结果留住。
 * 但严格说这不是"增量" —— history 只有 beforeId（往回翻），没有 afterId（拿更新的），
 * 所以离线性超过 50 条就得靠补拉 + 翻页凑。加 afterId 是以后的事。
 *
 * @param {boolean} [silent] 后台对齐时不弹错误提示（本地那份已经够看，失败不该打断用户）
 */
async function loadHistory(silent = false) {
  try {
    const list = (await api.messageHistory(peerId.value, { size: 50 })) || []
    mergeServerMessages(list)
  } catch (e) {
    if (!silent) {
      ElMessage.error(e?.message || '聊天记录加载失败')
    }
  }
}

/**
 * 把服务端回来的一批消息并进界面，并把水位和记录落盘。
 *
 * <p>两件容易漏的事：
 * <ol>
 *   <li><b>去重按 messageId</b>（重推/补拉/历史三处都会送来同一条）；</li>
 *   <li><b>清掉同 clientMessageId 的本地那条</b> —— 服务端已经确认了，
 *       本地那个"发送中/待重发"的占位就该消失，否则同一句话两个气泡，
 *       （这种情况真的会发生：请求发出去了、受理结果丢了、然后 App 重启）。</li>
 * </ol>
 */
function mergeServerMessages(list) {
  if (!list || list.length === 0) {
    return
  }
  const known = new Set(messages.value.filter((m) => m.messageId != null).map((m) => m.messageId))
  let changed = false

  for (const vo of list) {
    if (vo.messageId == null || known.has(vo.messageId)) {
      continue
    }
    if (conversationId.value == null && vo.conversationId != null) {
      conversationId.value = vo.conversationId
    }
    if (vo.clientMessageId) {
      const index = messages.value.findIndex(
        (m) => m.messageId == null && m.clientMessageId === vo.clientMessageId,
      )
      if (index >= 0) {
        messages.value.splice(index, 1)
        removePending(vo.clientMessageId)
      }
    }
    messages.value.push(toClientMessage(vo))
    known.add(vo.messageId)
    changed = true
  }

  // 水位：history 里"我发的消息"带着 deliveredAt / readAt，那正是"我离线期间对方读了"的真相
  raiseWatermarksFromHistory(list)

  if (changed) {
    messages.value.sort(byMessageId)
  }
  // 落盘（不 await）：界面不等磁盘。写失败也没关系 —— 下次 history 还会把同一批带回来
  appendLocalMessages(peerId.value, list)
}

/**
 * 推进<b>内存里</b>的水位（只前进不后退）。
 *
 * <p>这里刻意<b>不落盘</b>：落盘由两个调用方各自负责，因为它们的处境不一样 ——
 * <ul>
 *   <li><b>回执帧</b>这条路（{@link applyReceiptWatermark}）：{@code chatSync} 收到帧时
 *       已经写过本地库了，这里再写一遍就是同一个回执写两次 index；</li>
 *   <li><b>history</b> 这条路（{@link raiseWatermarksFromHistory}）：没有别的写入者，
 *       所以它必须自己落盘。</li>
 * </ul>
 */
function raiseWatermark(upToMessageId, level) {
  const value = Number(upToMessageId)
  // 注意 Number(null) 是 0：所以先判 > 0，不能只判 isInteger
  if (!Number.isInteger(value) || value <= 0) {
    return
  }
  if (level === 'read') {
    if (value > readUpTo.value) {
      readUpTo.value = value
    }
  } else if (value > deliveredUpTo.value) {
    deliveredUpTo.value = value
  }
}

/** 用一批 VO 上的 deliveredAt / readAt 反推水位（history 和它的后台重连版本都走这里） */
function raiseWatermarksFromHistory(list) {
  let maxDelivered = 0
  let maxRead = 0
  for (const vo of list) {
    // 用 isMine 而不是直接比 me.userId：这条路上"我是谁"可能还没从缓存里拿到，
    // 而水位不该因此就丢掉（isMine 对单聊有"非对方即我"的兜底）。
    if (vo.messageId == null || !isMine(vo.senderUserId)) {
      continue
    }
    if (vo.deliveredAt && vo.messageId > maxDelivered) {
      maxDelivered = vo.messageId
    }
    if (vo.readAt && vo.messageId > maxRead) {
      maxRead = vo.messageId
    }
  }
  raiseWatermark(maxDelivered, 'delivered')
  raiseWatermark(maxRead, 'read')

  // 这条路径【必须自己落盘】：回执帧那边有 chatSync 写本地库，
  // 而 history 里的 deliveredAt / readAt 没有别的写入者 —— 不落盘的话，
  // 重启客户端之后这些勾会退回"已发送"（回执是一次性推送，服务端不会因为你重连就再发一遍）。
  if (conversationId.value != null) {
    if (maxDelivered > 0) {
      raiseLocalReceipt(conversationId.value, maxDelivered, 'delivered')
    }
    if (maxRead > 0) {
      raiseLocalReceipt(conversationId.value, maxRead, 'read')
    }
  }
}

// ==================== 发送 ====================

async function onSend() {
  const content = draft.value.trim()
  if (!content || sending.value) {
    return
  }

  const clientMessageId = newClientMessageId()
  // ① 乐观插入：先在界面上画出来（状态"发送中"）
  messages.value.push({
    key: `local-${clientMessageId}`,
    messageId: null,
    conversationId: null,
    clientMessageId,
    content,
    mine: true,
    senderUserId: me.value?.userId ?? null,
    createdAt: new Date().toISOString(),
    status: 'sending',
    error: '',
  })
  draft.value = ''
  scrollToBottom()

  await submit(clientMessageId)
}

/**
 * 把一条本地消息提交给服务端。首次发送和"失败后点重发"走的是同一段代码。
 *
 * <p><b>重发用的还是同一个 clientMessageId</b>，这是关键：服务端把它当成重复提交，
 * 直接返回已经落库的那条（{@code duplicated=true}）。
 * 于是"请求其实已经到了服务端、只是受理结果在回程丢了"这种情况能被纠正过来 ——
 * 那种情况下界面显示"发送失败"，可对方可能早就收到、甚至已经读了。
 *
 * <p>注意这里是用 clientMessageId 从<b>响应式数组里重新取一次</b>对象再改的，
 * 而不是改外面那个原始对象：直接改原始对象绕过响应式代理，界面不会刷新。
 */
async function submit(clientMessageId) {
  const message = messages.value.find((m) => m.clientMessageId === clientMessageId)
  if (!message || sending.value) {
    return
  }

  // ⚠️ 先抢锁、再 await：下面那次 ensureMe() 会跨一次 await，
  //    如果锁在 await 之后才拿，"连按两次回车"的两个调用都能通过上面的守卫
  //    （服务端幂等不会产生重复消息，但会白发一个请求、按钮也会提前解禁）。
  sending.value = true

  // 落盘时要用"我是谁"填 senderUserId（本地记录里存的是服务端字段，自己发的消息也得填对人）。
  // 正常情况下 me 早就在账号缓存里了，这里只是堵住"刚进页面立刻点发送"那个窄窗口。
  if (me.value?.userId == null) {
    await ensureMe()
  }

  Object.assign(message, { status: 'sending', error: '' })

  // 【发送前先落到本地】：万一请求发出去了、受理结果没回来（断网、进程被杀、用户刷新），
  // 这条消息连同它的 clientMessageId 还在本地 —— 下次进这个会话会被恢复出来，
  // 点重发用的是【同一个 id】，服务端因此认得"这是同一条"，不会落两条。
  // 这是发送幂等闭环的最后一块：光有服务端的唯一索引还不够，客户端得记住那个 id。
  addPending({
    clientMessageId,
    peerUserId: peerId.value,
    ownerUserId: me.value?.userId ?? null,
    content: message.content,
    messageType: message.messageType ?? 1,
  })

  try {
    const result = await api.sendMessage(peerId.value, message.content, clientMessageId, message.messageType ?? 1)
    // ② 换成真正的 messageId（之后去重、排序、已读回执都靠它）。
    //    这里刻意【不设 status】：服务端确认过的消息，状态由本地水位现算（见 statusOf）。
    //    设了也是个会被忽略的冗余字段 —— 而 duplicated=true 那种"已存在的那条"甚至可能早就被读过了。
    Object.assign(message, {
      key: `m-${result.messageId}`,
      messageId: result.messageId,
      conversationId: result.conversationId,
      createdAt: result.createdAt,
    })
    if (conversationId.value == null) {
      conversationId.value = result.conversationId
    }
    // 服务端已经确认过了（哪怕是 duplicated=true 那种"已存在的那条"），本地这份不用留了
    removePending(clientMessageId)
    // ③ 落盘：自己发的消息也进本地库 —— 下次进这个会话，不用等 history 就能看到它（离线也看得到）
    appendLocalMessages(peerId.value, [{
      messageId: result.messageId,
      clientMessageId,
      conversationId: result.conversationId,
      senderUserId: me.value?.userId ?? null,
      content: message.content,
      // 文本消息固定 1。附件消息走 submitAttachment，那边的类型由服务端判定
      messageType: message.messageType ?? 1,
      createdAt: result.createdAt,
    }])
  } catch (e) {
    // ③ 失败不删掉那条，本地暂存也留着：标成失败让用户看得见，点一下就能用同一个 id 重发
    Object.assign(message, { status: 'failed', error: e?.message || '' })
    ElMessage.error(e?.message || '发送失败')
  } finally {
    sending.value = false
  }
}

/** 点失败的消息重发（模板里那个状态文字的可点击行为） */
function onResend(message) {
  if (statusOf(message) !== 'failed' || sending.value) {
    return
  }
  // 附件已经上传成功时复用原 URL；只有上传本身失败才重新上传。
  // 文本失败直接用同一个 clientMessageId 再提交一次即可（服务端幂等）。
  const file = attachmentFiles.get(message.clientMessageId)
  if (file) {
    submitAttachment(message.clientMessageId, file)
    return
  }
  submit(message.clientMessageId)
}

// ==================== 附件（图片 / 文件）====================

/**
 * 加号菜单选了哪一类：{@code image} 或 {@code file}。
 *
 * <p>两类共用同一个隐藏的 {@code <input type="file">}，只是打开之前改一下 accept：
 * 选图片时只让选图片（少一次"选错了才发现不支持"），选文件时放开。
 * 真正的类型判定仍然在服务端（按文件头 + 扩展名白名单）—— accept 只是给人看的。
 */
function onAttachCommand(kind) { configureAttachmentInput(fileInputRef.value,kind) }

/**
 * 选完文件之后。
 *
 * <p><b>立刻把 {@code input.value} 清空</b>：不清的话，连着选<b>同一个文件</b>第二次
 * 不会触发 change（值没变），用户会以为"点了没反应"。
 */
function onAttachmentPicked(event) {
  const input = event?.target
  const file = input?.files?.[0] || null
  if (input) {
    input.value = ''
  }
  if (!file) {
    return
  }
  // 前端先拦一道：不是为了省请求，而是"选完立刻知道自己选错了"比等上传失败体验好
  if (file.size > MAX_ATTACHMENT_BYTES) {
    ElMessage.error(`文件不能超过 ${Math.round(MAX_ATTACHMENT_BYTES / 1024 / 1024)}MB`)
    return
  }
  sendAttachment(file)
}

/**
 * 发一个附件：先乐观插入气泡（图片立刻用本地 blob 预览），再"上传 → 发消息"。
 *
 * <p><b>为什么是两步</b>：发消息那条链路的语义是"一条内容 + 幂等 id + 受理结果"，
 * 它不该为附件做特例。把附件变成"内容是一段 URL 的普通消息"之后，
 * 幂等重发、服务端重推、上线补拉、未读数统计全都不用改。
 */
function sendAttachment(file) {
  const clientMessageId = newClientMessageId()
  const isImage = (file.type || '').startsWith('image/')
  const isVideo = (file.type || '').startsWith('video/')
  /*
    上传可能要几秒，不给即时预览就像"点了没反应"。
    图片和视频都能直接用本地 blob 渲染（视频浏览器原生就能播 blob），所以两类都给预览；
    其它文件没有"预览"这回事，气泡显示成文件卡片（内容先放文件名）。
  */
  const localPreview = (isImage || isVideo) ? URL.createObjectURL(file) : null
  if (localPreview) {
    attachmentObjectUrls.add(localPreview)
  }

  messages.value.push({
    key: `local-${clientMessageId}`,
    messageId: null,
    conversationId: null,
    clientMessageId,
    // 上传成功前先放本地预览地址（图片/视频）或文件名（其它文件），成功后换成服务端 URL
    content: localPreview || file.name,
    // 这里只是"乐观猜测"，真正的类型以服务端判定为准（见 submitAttachment）。
    // 视频也算 3（服务端就是这么分的），由 mimeType / 扩展名决定渲染成 <video> 还是文件卡片
    messageType: isImage ? 2 : 3,
    mine: true,
    senderUserId: me.value?.userId ?? null,
    createdAt: new Date().toISOString(),
    status: 'sending',
    error: '',
    localPreview,
    fileName: file.name,
    fileSize: file.size,
    // 乐观插入时 content 还是 blob/文件名，判断不出"是不是视频"，所以把 MIME 也带上
    mimeType: file.type || '',
  })
  scrollToBottom()

  // 留下原始文件：上传失败后"点击重发"要靠它（localStorage 存不下文件字节，
  // 所以附件不进 pendingMessages —— 进程重启后就没法重发了，这是有意的取舍）
  attachmentFiles.set(clientMessageId, file)
  submitAttachment(clientMessageId, file)
}

/**
 * 上传 + 发送一个附件（首次发送和失败重发走同一段）。
 *
 * <p>顺序不能反：<b>先上传拿到 URL，再带着 URL 发消息</b>。反过来的话消息里会出现一个
 * "还没有文件"的地址，对方打开就是 404。
 */
async function submitAttachment(clientMessageId, file) {
  const message = messages.value.find((m) => m.clientMessageId === clientMessageId)
  if (!message || sending.value) {
    return
  }
  sending.value = true
  if (me.value?.userId == null) {
    await ensureMe()
  }
  Object.assign(message, { status: 'sending', error: '' })

  try {
    // ① 上传：类型、大小、能不能收都由服务端判（不听客户端自称）
    const uploaded = message.uploadedAttachment ?? await api.uploadAttachment(file)
    message.uploadedAttachment = uploaded
    addPending({ clientMessageId, peerUserId: peerId.value, ownerUserId: me.value?.userId ?? null,
      content: uploaded.url, messageType: uploaded.messageType })
    // ② 发消息：内容用服务端签发的 URL，类型也用服务端判定的那个
    const result = await api.sendMessage(
      peerId.value, uploaded.url, clientMessageId, uploaded.messageType,
    )

    const previewToRevoke = message.localPreview
    Object.assign(message, {
      key: `m-${result.messageId}`,
      messageId: result.messageId,
      conversationId: result.conversationId,
      content: uploaded.url,
      messageType: uploaded.messageType,
      createdAt: result.createdAt,
      localPreview: null,
    })
    if (conversationId.value == null) {
      conversationId.value = result.conversationId
    }
    // 本地预览已经换成服务端那份了，blob 可以释放（不释放就一直占着那几个字节）
    if (previewToRevoke) {
      URL.revokeObjectURL(previewToRevoke)
      attachmentObjectUrls.delete(previewToRevoke)
    }
    attachmentFiles.delete(clientMessageId)
    removePending(clientMessageId)

    /*
      ④ 【自己发的附件也存一份到本地】——字节现在就在手上，这一步零网络成本。
      不存的话会出现一个很别扭的现象：**自己刚发出去的图片/视频，断网后自己也看不了**
      （本地没有副本、服务端又连不上），而那份文件本来就是自己选的。
      服务端那份是"随时可取"的那份，本地这份是"断网也能看"的那份。
      不 await：存盘失败不该影响"已发送"，下次显示时还会再补。
    */
    saveOwnAttachmentLocally(uploaded.url, file).catch(() => {})

    // ⑤ 落盘：本地库只存 URL —— 二进制不进那个 JSON（见 message-store.cjs 的说明）
    appendLocalMessages(peerId.value, [{
      messageId: result.messageId,
      clientMessageId,
      conversationId: result.conversationId,
      senderUserId: me.value?.userId ?? null,
      content: uploaded.url,
      messageType: uploaded.messageType,
      createdAt: result.createdAt,
    }])
  } catch (e) {
    // 失败保留气泡和原始文件：点一下状态文字就能用同一个 clientMessageId 重发
    Object.assign(message, { status: 'failed', error: e?.message || '' })
    ElMessage.error(e?.message || '发送失败')
  } finally {
    sending.value = false
  }
}

/** 附件 URL 去掉查询串（{@code ?n=&s=} 只是显示用的元信息） */
/**
 * 恢复"上次没发出去的消息"（本地暂存里那些还没被服务端确认的）。
 *
 * <p>它们在服务端可能是三种状态之一：压根没收到、收到了但受理结果丢了、
 * 其实已经落库了。本地无从判断，所以统一显示成"发送失败，点击重发" ——
 * 点一下会用<b>同一个 clientMessageId</b> 重发，服务端要么新建、要么把已存在那条还回来，
 * 两种结果都对（这正是那个 id 存在的意义）。不点也无害：不会重复，也不会丢。
 */
async function restorePending() {
  const ownerUserId = me.value?.userId ?? null
  const pending = listPending(peerId.value, ownerUserId)
  if (pending.length === 0) {
    return
  }

  // 按待发消息 ID 查询是否已受理，避免为恢复发件箱读取全部历史。
  const knownClientIds = new Set(
    messages.value.map((m) => m.clientMessageId).filter(Boolean),
  )
  if (hasMoreLocal.value) {
    const full = await loadLocalMessages(peerId.value, {clientMessageIds:pending.map(m=>m.clientMessageId)})
    for (const vo of full.messages) {
      if (vo.clientMessageId) {
        knownClientIds.add(vo.clientMessageId)
      }
    }
  }

  let restored = 0
  for (const item of pending) {
    if (knownClientIds.has(item.clientMessageId)) {
      // 服务端那条已经回来了（历史里有同一个 clientMessageId）→ 本地这就不用恢复了，
      // 顺手把暂存删掉，免得它每次进会话都被检查一遍、直到过期才被清
      removePending(item.clientMessageId)
      continue
    }
    messages.value.push({
      key: `pending-${item.clientMessageId}`,
      messageId: null,
      conversationId: null,
      clientMessageId: item.clientMessageId,
      content: item.content,
      mine: true,
      messageType: item.messageType ?? 1,
      senderUserId: ownerUserId,
      createdAt: new Date(item.createdAt).toISOString(),
      status: 'failed',
      error: '上次发送未完成，点击重发',
    })
    restored++
  }
  if (restored > 0) {
    // 按 messageId 排序会自动把"还没有 id 的"放到最后 —— 未发出的消息本来就该在末尾
    messages.value.sort(byMessageId)
  }
}

// ==================== 接收全局同步派发的事件 ====================

/**
 * 全局同步模块（chatSync.js）负责"收 + ack + 补拉"，它会把结果派发到这里：
 *
 * - {@code message}   —— 收到一条发给我的消息（可能是推送来的，也可能是补拉来的）
 * - {@code delivered} —— 我发的消息，对方收到了（单勾变双勾）
 * - {@code read}      —— 我发的消息，对方读了（双勾变蓝）
 *
 * <b>本页不做 ack</b>：ack 表示"客户端收到了"，跟"你在看哪个页面"无关，
 * 所以它由全局模块负责（否则你不打开这个会话，发送方永远看不到"已送达"）。
 */
function handleChatEvent(event) {
  if (event.kind === 'accepted' && event.peerUserId === peerId.value) {
    const local = messages.value.find((m) => m.clientMessageId === event.message.clientMessageId)
    if (local) {
      if (local.localPreview) {
        URL.revokeObjectURL(local.localPreview)
        attachmentObjectUrls.delete(local.localPreview)
      }
      attachmentFiles.delete(event.message.clientMessageId)
      Object.assign(local, event.message, { key: `m-${event.message.messageId}`, status: 'sent', error: '' })
      local.localPreview = null
      conversationId.value = event.message.conversationId
    }
  } else if (event.kind === 'message') {
    onMessageFromSync(event.message)
  } else if (event.kind === 'delivered') {
    applyReceiptWatermark(event.conversationId, event.upToMessageId, 'delivered')
  } else if (event.kind === 'read') {
    applyReceiptWatermark(event.conversationId, event.upToMessageId, 'read')
  } else if (event.kind === 'status') {
    // 实时通道状态变化后的用户提示由 MainLayout.vue 统一弹 toast，本页不画横条、也不用记这个状态。
    // 这里刻意【不】在 'open' 时拉一次历史做"对账"（进入会话只读本地，见 loadHistory 上方说明）。
    //
    // ⚠️ 但"断线期间漏掉的消息"【不会因此丢】：那由 chatSync 的补拉（/undelivered）负责，
    // 而补拉是在我们订阅之后才派发 'message' 事件的，所以本页照样收得到、显示得出来。
    if (event.state === 'open') {
      // 重连成功后补报一次已读。为什么需要：
      //   已读上报现在走 WS，而 WS"半死"期间发出去的那次会假报成功、实际进黑洞 ——
      //   服务端读游标没推进，重连后的 /conversations 对账就会把旧未读又盖回来（红点复现）。
      //   这里重报一次，游标才追得上。上报本身是幂等的水位（只前进不后退），多报无害。
      reportReadIfNeeded()
      // 注意：这里【不】调 loadLocal() 重读水位 —— 重连时 /conversations 还没回来，
      // 那一刻读到的仍是旧值。本会话的勾由 onLocalReceipt 订阅"水位真的变了"来更新
      // （见 onReceiptRaised），订阅在 onMounted 就挂好了。
    }
  }
}

/** 显示一条同步过来的消息（推送和补拉都走这里） */
function onMessageFromSync(data) {
  if (!data || data.messageId == null) {
    return
  }
  // 去重：重推（服务端 1/2/4…秒一次）和补拉会让同一条到达多次，
  // 不去重的话界面上同一句话会出现好几遍
  if (messages.value.some((m) => m.messageId === data.messageId)) {
    return
  }
  // 这个页面只展示和"当前这个人"的会话；别的会话的消息先不管（会话列表/未读是下一步的事）
  if (data.senderUserId !== peerId.value) {
    return
  }
  if (conversationId.value == null && data.conversationId != null) {
    conversationId.value = data.conversationId
  }

  messages.value.push(toClientMessage(data))
  // 排一次序：补拉可能带回一条"很早就发出、但 ack 丢了"的旧消息，
  // 直接追加到末尾会让它显示在时间线的错误位置（按 messageId 排才是真顺序）
  messages.value.sort(byMessageId)
  // 只有用户本来就在底部才自动滚 —— 他正在往上翻历史时不能被一条新消息拽走
  if (isNearBottom()) {
    scrollToBottom()
  }

  // 用户正看着这个会话 → 报已读（会合并成一次，并且窗口不可见时不报）
  scheduleReadReport(data.messageId)
}

/**
 * 应用"已送达 / 已读"回执帧 —— 推进水位。
 *
 * <p>服务端发过来的是"在这个会话里，你发的消息到某条为止都送达/已读了"（upToMessageId），
 * 所以这里只做一件事：把水位推到它。要不要给某条消息画双勾，是渲染时现算的（见 statusOf）。
 *
 * <p><b>只认本会话的回执</b>：这条通道会同时送来别的会话的已读，
 * 拿别人的 upTo 推本会话的水位，界面上的勾就全乱了。
 * （别的会话的水位由 chatSync 顺手写进本地库，本页不关心。）
 */
function applyReceiptWatermark(receiptConversationId, upToMessageId, level) {
  if (receiptConversationId != null && receiptConversationId === conversationId.value) {
    raiseWatermark(upToMessageId, level)
    return
  }

  /*
    ⚠️ 走到这里说明"还不能确认这条回执属于本会话"，有两种可能：
      ① conversationId.value 还是 null —— 本地库还没有这个会话的 id，
         或者 onMounted 里的 loadLocal() 还没跑完（它是异步的）；
      ② 回执是别的会话的。

    这里原来是直接 return 把帧丢掉的，bug 就出在 ①：
    那一瞬间到达的"已送达/已读"被永久丢弃，于是发送方看到的一直是单勾，
    直到关掉会话重新进来（重新 loadLocal 从本地库读到水位）才会变对。
    表现很容易被误读成"对方没打开聊天界面所以收不到"。

    现在改成【回本地库对账】：chatSync 收到回执帧时，一定已经按
    "conversationId → 对方"的映射把水位写进本地库了（那条路不依赖本页的任何状态），
    所以重读一次快照就能把勾修好；而如果是别的会话的回执，
    这次重读拿到的也只是本会话自己的水位，不会串。
  */
  loadLocal()
}

/**
 * 本地库里的回执水位被推进了（订阅 {@code messageStore} 的 onLocalReceipt）—— 就地更新内存水位。
 *
 * <p>和 {@link applyReceiptWatermark} 的唯一区别是"消息从哪来"：那个处理 WS 回执帧，
 * 这个处理"本地库里的水位变了"，最常见的来源是<b>重连时 {@code /conversations} 批量写回</b>。
 * 两条路最后都走同一个 {@link raiseWatermark}（只升不降，重复推进无害）。
 *
 * <p><b>为什么不在 {@code status:open} 时直接 loadLocal()</b>：
 * {@code /conversations} 是异步的，`status:open` 那一刻它还没回来，
 * 这时候读本地库拿到的仍是旧水位 —— 白读一次、而且勾还是不变。
 * 订阅"水位真的变了"这个事件，时机才是对的。
 *
 * <p>只处理本会话：别的会话的水位由列表/角标自己用，聊天页不需要为它们重读磁盘。
 */
function onReceiptRaised(receiptConversationId, upToMessageId, level) {
  if (receiptConversationId != null && receiptConversationId === conversationId.value) {
    raiseWatermark(upToMessageId, level)
  }
}

// ==================== 已读上报 ====================

/**
 * 排一次"已读上报"，短时间内的多条会合并成一次。
 *
 * <p><b>为什么必须合并</b>：补拉一页最多 50 条，如果收到一条报一次，就是 50 个 POST
 * （服务端每个请求要跑 3 条 UPDATE）。读游标是单调递增的，只报最大的那一条就够了。
 *
 * <p><b>窗口没聚焦时干脆不报</b>：人没看，不该让对方的勾变蓝。
 * 注意判据是 {@link isWindowFocused} 而不是 isWindowVisible —— 主进程关掉了 Chromium 的
 * 遮挡检测（为了让被盖住的窗口也能收推送），所以"被别的程序盖住 / 用户在别的程序里"时
 * {@code visibilityState} 仍然是 visible。只看可见性就会出现"软件最小化/没聚焦也显示已读"。
 *
 * <p>等用户点回这个窗口时 {@link onForegroundRead} 会补一次。
 */
function scheduleReadReport(messageId) {
  if (messageId == null || !isWindowFocused()) {
    return
  }
  readReportUpTo = readReportUpTo == null ? messageId : Math.max(readReportUpTo, messageId)
  if (readReportTimer) {
    return   // 已经排好了，等它跑完一起报
  }
  readReportTimer = setTimeout(() => {
    readReportTimer = null
    const upTo = readReportUpTo
    /*
      真正要发之前【再判一次】窗口状态。
      为什么：从排队到发出有 300 毫秒，这期间用户完全可能最小化或者切走 ——
      只在排队那一刻判断的话，人已经走了还是会把"已读"发出去。
      没聚焦就先把待报的值留着（不清空），等 focus 事件触发 onForegroundRead 时一起报。
    */
    if (upTo == null || !isWindowFocused()) {
      return
    }
    readReportUpTo = null
    reportRead(upTo)
  }, READ_REPORT_MERGE_MS)
}

/**
 * 把"我读到哪条了"真正报出去。<b>两处都要，缺一不可</b>：
 * <ul>
 *   <li><b>服务端</b> —— 权威：它推进读游标、把已读写进收据（对方界面上的勾变蓝），
 *       未读数也该由它算。通道<b>优先走 WS</b>（{@code {type:'read-report', …}}），
 *       发不出去再退回 HTTP（{@code POST /message/read}），见 {@link sendReadToServer}；</li>
 *   <li><b>本地库</b> —— 让消息页的未读红点不用联网也是对的
 *       （红点 = "对方发的、我还没读到"的条数，本地游标就是它的依据）。</li>
 * </ul>
 * 两边都是"只前进不后退"，所以重复上报无害。
 *
 * <p>顺手把列表/角标上这个会话的红点清掉 —— 见 {@link clearUnreadForPeer} 的说明：
 * 不能等"下一次同步"，因为读一下未必会推进本地读游标（空操作就不会广播），
 * 而 /conversations 只在连上/重连时才拉，结果就是"读过了、红点还挂着"。
 */
function reportRead(upTo) {
  markLocalRead(peerId.value, upTo)
  clearUnreadForPeer(peerId.value)
  sendReadToServer(upTo)
}

/**
 * 把已读上报发给服务端：<b>WS 优先，HTTP 兜底</b>。
 *
 * <p>为什么它适合走 WS：这是纯通知（不需要返回值、失败可以重发、服务端取 max 所以幂等），
 * 和"送达 ack"是同一类东西 —— ack 走 WS，它没理由单独占一条 HTTP 请求。
 *
 * <p>为什么 HTTP 那条必须留着：纯浏览器里没有 WS（收推送的通道建在 Electron 主进程里），
 * 而"把自己的已读推上去"在浏览器里也得能用。另外 WS 没连上、或者发帧失败时也走它。
 *
 * <p>⚠️ 已知的一个窄窗口：WS"半死"（连接看着在、其实发不出去）时，sendFrame 会假报成功，
 * 这次上报就进了黑洞。兜底靠两件事：① 看门狗 75 秒内会主动重连；
 * ② 重连成功（`status:open`）时会再报一次（见 handleChatEvent）。
 */
async function sendReadToServer(upTo) {
  if (hasChatSocket) {
    const result = await sendChatFrame({
      type: 'read-report',
      peerUserId: peerId.value,
      upToMessageId: upTo,
    }).catch(() => null)
    if (result && result.ok) {
      return
    }
  }
  // 没有实时通道 / 通道没连上 / 发帧失败 → HTTP 兜底
  api.markMessageRead(peerId.value, upTo).catch(() => {})
}

/**
 * 把还在合并窗口里的已读<b>立刻</b>报出去（离开会话时调）。
 *
 * <p>为什么不直接丢掉：用户"打开看一眼马上返回"是很常见的动作，
 * 丢掉那次上报会留下一个<b>永远不消的红点</b>（对方那边也一直显示未读）。
 */
function flushReadReport() {
  if (readReportTimer) {
    clearTimeout(readReportTimer)
    readReportTimer = null
  }
  const upTo = readReportUpTo
  readReportUpTo = null
  if (upTo != null) {
    reportRead(upTo)
  }
}

/*
 * "是不是真的有人在看"统一用 utils/env.js 的 isWindowFocused（可见 + 有焦点）。
 * 不能用 isWindowVisible：主进程关掉了 Chromium 的遮挡检测（为了让被盖住的窗口也能收推送），
 * 所以窗口被盖住 / 用户切到别的程序时，visibilityState 仍然是 visible。
 */

/** 窗口重新聚焦 → 把"刚才没报的已读"补上 */
function onForegroundRead() {
  if (isWindowFocused()) {
    reportReadIfNeeded()
  }
}

/**
 * 打开会话（或切回前台）时上报：我读到最新那条了。
 *
 * <p>报给服务端的"最新那条"取两个来源里更大的那个：
 * <ul>
 *   <li><b>本地库最后一条"对方发的"</b> —— 传统做法，本地有一部分消息时靠它；</li>
 *   <li><b>会话列表里服务端给的最后一条 id</b> —— 打开一个会话就算读过（这是聊天软件的
 *       通行语义），而且本地可能比服务端少几条（换机器 / 在别处聊过），
 *       只报本地那条的话服务端的未读数永远清不掉，红点就一直"读不掉"。</li>
 * </ul>
 * 两个都拿不到（没消息、也没这个会话）才不报。
 */
function reportReadIfNeeded() {
  const last = [...messages.value].reverse().find((m) => m.messageId != null && !m.mine)
  const localTarget = last ? last.messageId : 0
  const row = conversations.value.find((item) => Number(item?.peerUserId) === peerId.value)
  const serverTarget = Number(row?.lastMessageId) || 0
  const target = Math.max(localTarget, serverTarget)
  if (target <= 0) {
    return
  }
  scheduleReadReport(target)
}

// ==================== 回执对齐 ====================

/*
 * 这个位置前后被删过两次，留个线索免得后来人再找一遍：
 *   1. 最早是 refreshReceipts()：重连时拉 history、逐条升级"我发的消息"的状态；
 *   2. 后来改成用水位（raiseWatermarksFromHistory，由 loadHistory 顺带调用）；
 *   3. 现在"进入会话只读本地"，history 整条路【暂不调用】（见 loadHistory 上方说明），
 *      所以"回执对账"这条路径现在是停着的 —— 勾的水位靠两处兜底：
 *        · 在线的回执靠 WS 帧实时推进；
 *        · 离线期间对方读了的，靠 /conversations 接口顺带带回
 *          （conversationStore 连上/重连时拉，见它的 syncConversationsFromServer）。
 */

// ==================== 小工具 ====================

/**
 * 服务端消息（或者本地库里存的那份，两者字段同构）→ 界面上的消息。
 *
 * <p>注意这里【<b>不带 status</b>】：服务端确认过的消息，状态是渲染时用水位现算的（见 statusOf）。
 * 界面上的 status 只有一种消息需要 —— 本地还没被确认的那些（messageId 为 null）。
 */
function toClientMessage(vo) {
  return {
    key: `m-${vo.messageId}`,
    messageId: vo.messageId,
    conversationId: vo.conversationId ?? null,
    clientMessageId: vo.clientMessageId ?? null,
    content: vo.content,
    messageType: vo.messageType ?? 1,
    mine: isMine(vo.senderUserId),
    senderUserId: vo.senderUserId,
    createdAt: vo.createdAt,
  }
}

/** 排序：按 messageId 升序，还没拿到 messageId 的（本地发送中）排最后 */
function byMessageId(a, b) {
  if (a.messageId == null) return 1
  if (b.messageId == null) return -1
  return a.messageId - b.messageId
}

// ==================== 时间分隔条 ====================

/**
 * 消息间隔超过多久才另起一条时间（微信也是 5 分钟）。
 *
 * <p>比的是"和上一条<b>显示出来的</b>时间"，不是"和上一条消息"。
 * 后者会一路都不断开：10:00 / 10:04 / 10:08 / 10:12…… 每条都只差 4 分钟，
 * 结果用户看到的第一个时间可能是二十多分钟前的，越看越糊涂。
 */

/** 星期几文案（getDay() 的 0 是周日） */

/** 两个时间是不是同一个"本地日"（不能用 UTC，否则凌晨 8 点前会算错一天） */
const renderItems = computed(() => buildMessageTimeline(messages.value))

/**
 * 一条消息现在该显示什么状态。
 *
 * <p>两类消息的状态来源不同，这是刻意的：
 * <ul>
 *   <li><b>本地还没被服务端确认的</b>（messageId 为 null）：状态写在对象上（sending / failed）；</li>
 *   <li><b>服务端确认过的</b>：拿 messageId 和两个水位比一下，<b>现算</b> —— 不存。
 *       这样"已送达/已读"在客户端只有一份数据（水位），不会出现"逐条状态和水位打架"。</li>
 * </ul>
 *
 * <p>顺带一个好处：回执乱序到达时不用做任何"只升不降"的比较 —— 水位本来就只会往上走。
 */
function statusOf(message) {
  if (message.messageId == null) {
    return message.status || 'sending'
  }
  if (!message.mine) {
    return ''   // 对方发的消息不画状态（模板里 v-if="message.mine" 也不会渲染到）
  }
  if (readUpTo.value >= message.messageId) {
    return 'read'
  }
  if (deliveredUpTo.value >= message.messageId) {
    return 'delivered'
  }
  return 'sent'
}

/** 状态 → 界面上那几个字 */
function statusText(status) {
  switch (status) {
    case 'sending':
      return '发送中…'
    case 'delivered':
      return '已送达'
    case 'read':
      return '已读'
    case 'failed':
      return '发送失败，点击重发'
    default:
      return '已发送'
  }
}

function senderOf(message) {
  return message.mine ? me.value : peer.value
}

function avatarOf(message) {
  // 走 assetUrl 而不是直接给地址：头像上传后库里存的是相对路径（/api/user/uploads/...），
  // 直接塞给 <img> 会去页面自身的来源（file:// 或 5173）找图片，必然裂图。
  return assetUrl(senderOf(message)?.avatarUrl)
}

function avatarNameOf(message) {
  return senderOf(message)?.nickName
}

/*
 * 头像首字以前在这里写过一份，现在统一用 utils/display.js 的 firstChar
 * （Contacts / ContactsAdd / AddFriendDialog / FriendRequests / Message 各有一份）。
 */

/** 滚到底部：消息区是唯一滚动容器，新消息进来要能看见 */
function scrollToBottom() {
  nextTick(() => {
    const el = bodyRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

/**
 * 用户现在是不是"贴着底部"在看。
 *
 * <p><b>为什么需要它</b>：新消息到达时如果无脑滚到底，那么"正在往上翻历史"的人
 * 会被一条新消息直接拽回底部 —— 翻页等于白翻、还看不完刚才那句。
 * 微信也是这个规矩：只有你本来就在底部（或差得不远），新消息才自动滚出来。
 *
 * @param {number} [tolerance] 离底部多少像素以内算"还在底部"
 */
function isNearBottom(tolerance = 80) {
  const el = bodyRef.value
  if (!el) {
    return true   // 还没渲染出来：当成"在底部"，行为和老逻辑一致
  }
  return el.scrollHeight - el.scrollTop - el.clientHeight <= tolerance
}

/*
 * 返回上一页的实现已经在上面（useBack），这里不再单独定义 ——
 * 原来那四个页面各写一份一模一样的 goBack，唯一差别就是兜底路由名。
 */
</script>

<style scoped>
.single-info { display:flex; flex-direction:column; align-items:center; gap:14px; color:var(--chat-text); }
.single-info h3, .single-info p { margin:0; }
.single-info p { color:var(--chat-text-3); font-size:12px; }
/*
  这一页和别的页面不一样：它【需要】height:100% + 自己的滚动区。
  顶部标题 + 中间消息列表 + 底部输入框，输入框要永远钉在最下面，
  所以整页高度固定、只让中间那块滚动（配合路由上的 meta.hideTabBar 把底部导航藏掉）。
*/
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  /*
    为什么把共享类给的 padding / gap 清成 0：
    聊天页是"顶栏—消息—输入栏"三段各自贴边的三明治结构。
    留着页面外壳的内边距，顶栏和输入栏就会离开窗口边缘、悬在半空，
    看起来像一块浮在中间的卡片而不是一个聊天窗口；块间距改由各段自己的 padding 承担。
  */
  padding: 0;
  gap: 0;
  background: var(--chat-page-bg);
}


/* 空状态撑满消息区：居中要相对"整块空白区域"，而不是相对第一屏 */
.chat-empty {
  flex: 1;
  padding: 40px 0;
  font-size: 13px;
}

/* 消息状态：发送中 / 已发送 / 已送达 / 已读 / 失败。它是辅助信息，不能抢正文 */
/* 隐藏的文件选择控件：点加号菜单时由 JS 触发它（display:none 在某些平台点不动，用裁剪藏） */
.file-input {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

.state-box {
  padding: 8px 0;
}

</style>
