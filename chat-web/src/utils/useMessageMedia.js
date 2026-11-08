import { reactive,ref,onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { assetUrl,UNREACHABLE_TEXT } from '../api'
import { readLocalMedia,saveLocalMedia } from './messageStore'
import { getToken } from './auth'
// Single/group pages share lazy local reads, on-demand downloads and account-scoped object URLs.
export function useMessageMedia(bodyRef) {
const mediaLoading=reactive(new Set()),mediaProbed=reactive(new Set()),failedMedia=ref(new Set()),mediaUrls=reactive(new Map())
const attachmentObjectUrls=new Set(),mediaProbeTargets=new WeakMap()
let mediaObserver=null,disposed=false
const token=getToken(),valid=()=>!disposed && token===getToken()
const MEDIA_NAME_RE=/^[0-9a-f]{32}\.(png|jpg|gif|webp|pdf|zip|rar|7z|doc|docx|xls|xlsx|ppt|pptx|txt|md|csv|mp3|wav|m4a|mp4|mov)$/

function attachmentUrlOf(content) {
  if (typeof content !== 'string') {
    return ''
  }
  const q = content.indexOf('?')
  return q < 0 ? content : content.slice(0, q)
}

/** 解析附件 URL 上的元信息：原始文件名 + 字节数（服务端上传时挂上去的） */
function attachmentMetaOf(content) {
  const text = typeof content === 'string' ? content : ''
  const q = text.indexOf('?')
  if (q < 0) {
    return { name: '', size: 0 }
  }
  const params = new URLSearchParams(text.slice(q + 1))
  const size = Number(params.get('s'))
  return {
    name: params.get('n') || '',
    size: Number.isFinite(size) && size > 0 ? size : 0,
  }
}

/**
 * 取附件字节：<b>永远走网络，不碰浏览器缓存</b>。
 *
 * <p>{@code cache: 'no-store'} 是这里唯一重要的一行。默认的 {@code fetch} 会用
 * HTTP 缓存里的副本，于是"本地已经删了、服务端也够不着"的时候还能静悄悄拿到旧数据 ——
 * 用户看到的是"文件还在"，实际上没有任何一份可信的来源。
 *
 * @throws {Error} 网络不通（message 是 {@link UNREACHABLE_TEXT}）或服务端说没有这个文件
 */
async function fetchAttachmentBytes(url) {
  let res
  try {
    res = await fetch(url, { cache: 'no-store' })
  } catch {
    // 连不上：文案直接用 api 层那句话，全站保持一致（见 api/index.js 的 UNREACHABLE_TEXT）
    throw new Error(UNREACHABLE_TEXT)
  }
  if (!res.ok) {
    throw new Error(res.status === 404 ? '附件已不存在' : `附件读取失败（${res.status}）`)
  }
  const bytes = new Uint8Array(await res.arrayBuffer())
  if (bytes.byteLength === 0) {
    throw new Error('附件内容是空的')
  }
  return bytes
}

/**
 * 图片的 src。
 *
 * <p>只有两档，而且<b>都不会自己去请求后端</b>：
 * <ol>
 *   <li>还没上传成功：本地 blob（秒出预览）；</li>
 *   <li>本地消息库里有副本（或本次会话已经下载过）：blob。</li>
 * </ol>
 * 都没有就返回空串 —— 模板据此显示"点击加载图片"的占位块，由 {@link loadMedia}
 * 在用户点击时去取。<b>进会话一个请求都不发</b>，这是刻意定的规则（见模板里的说明）。
 */
function imageSrcOf(message) {
  if (message.localPreview) {
    return message.localPreview
  }
  const name = mediaNameOf(message.content)
  if (!name) {
    return ''
  }
  return mediaUrls.get(name) || ''
}

/** 文件卡片显示的名字：优先原始文件名，其次 URL 上的名字，最后退回磁盘名 */
function fileNameOf(message) {
  if (message.fileName) {
    return message.fileName
  }
  const meta = attachmentMetaOf(message.content)
  return meta.name || attachmentUrlOf(message.content).split('/').pop() || '文件'
}

/** 文件大小文案（B / KB / MB）：卡片上必须有它，否则用户不知道要下多久 */
function fileSizeTextOf(message) {
  const size = message.fileSize || attachmentMetaOf(message.content).size
  if (!size) {
    return ''
  }
  if (size < 1024) {
    return `${size} B`
  }
  if (size < 1024 * 1024) {
    return `${Math.round(size / 1024)} KB`
  }
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

/**
 * 占位块上的那句话（图片 / 视频共用）。
 *
 * <p>四种状态说四件事：正在探本地 / 正在下载 / 本地确实没有（要点）/ 下失败了。
 * 用户永远知道"现在能不能点、点了会怎样"。
 */
function mediaPlaceholder(message, label) {
  if (mediaLoading.has(message.key)) {
    return `正在加载${label}…`
  }
  if (failedMedia.value.has(message.key)) {
    return `${label}加载失败，点击重试`
  }
  // 还没探过本地：说"正在加载"才是实话（马上就会去读本地，见 vLocalMedia）。
  // 说"点击加载"会让人以为本地没有，白点一下。
  if (!mediaProbed.has(message.key)) {
    return `正在加载${label}…`
  }
  return `点击加载${label}`
}

/** 把一条消息标记成"附件打不开"，占位块上会显示"点击重试" */
function markMediaFailed(message) {
  const next = new Set(failedMedia.value)
  next.add(message.key)
  failedMedia.value = next
}

/**
 * 【本地有就自动显示】—— 只读本地磁盘，<b>一个网络请求都不发</b>。
 *
 * <p>为什么不是"一进会话就把所有附件都读一遍"：一个会话可能有几十个附件，
 * 其中还有几十 MB 的视频，全读进内存既慢又占地方，而用户可能根本没往下滚。
 * 所以交给 {@link vLocalMedia}（IntersectionObserver）：滚到它附近才探一次。
 *
 * <p>为什么每个消息只探一次（{@code mediaProbed}）：没探到就说明本地确实没有，
 * 来回滚动不该反复读盘。
 */
async function probeLocalMedia(message) {
  const key = message.key
  if (mediaProbed.has(key)) {
    return
  }
  mediaProbed.add(key)

  const name = mediaNameOf(message.content)
  if (!name || mediaUrls.has(name)) {
    return
  }
  try {
    const bytes = await readLocalMedia(name)
    if (bytes && bytes.byteLength > 0) {
      // 写进 reactive Map 就会触发重渲染：占位块直接换成真正的图片/播放器。
      // 注意这里【不】存盘（本来就在盘上），也不发网络请求。
      cacheMediaUrl(name, bytes)
    }
  } catch (e) {
    // 读盘失败不致命：占位块会变成"点击加载"，点了走网络那一遍
    console.warn('[chat] 读取本地附件失败（可点击重新获取）', e)
  }
}

/** 观察器的回调：元素进入视口附近 → 探一次本地 */
function onMediaVisible(entries) {
  for (const entry of entries) {
    if (!entry.isIntersecting) {
      continue
    }
    const message = mediaProbeTargets.get(entry.target)
    // 探过就不再观察：这个占位块要么变成真内容（会被卸载），要么一直等用户点
    mediaObserver?.unobserve(entry.target)
    if (message) {
      probeLocalMedia(message)
    }
  }
}

/**
 * 建观察器（懒建）。
 *
 * <p>为什么不在 onMounted 里直接建：指令的 mounted 钩子先于组件的 onMounted 执行，
 * 那时候还没有观察器可 observe。懒建就不存在这个先后问题。
 */
function ensureMediaObserver() {
  if (mediaObserver) {
    return mediaObserver
  }
  if (typeof IntersectionObserver === 'undefined') {
    return null
  }
  mediaObserver = new IntersectionObserver(onMediaVisible, {
    // 以消息区为参照（它才是真正滚动的那个元素）；还没拿到就退回视口
    root: bodyRef.value || null,
    // 提前 200px 就开始探：滚到跟前时内容已经就位，不会"先白一下再出现"
    rootMargin: '200px 0px',
    threshold: 0,
  })
  return mediaObserver
}

/**
 * 附件占位块的自定义指令：元素进入视口附近时去探一次本地副本。
 *
 * <p>用指令而不是函数式 ref，是因为 v-for 里的箭头函数 ref 每次渲染都会变身份，
 * Vue 会"先解绑再绑定"，观察器跟着反复 observe/unobserve —— 行为上不容易看出来，
 * 但白白多了一堆调用。指令的 mounted/unmounted 和元素生命周期严格一一对应。
 */
const vLocalMedia = {
  mounted(el, binding) {
    const message = binding.value
    if (!message) {
      return
    }
    mediaProbeTargets.set(el, message)
    ensureMediaObserver()?.observe(el)
  },
  unmounted(el) {
    mediaObserver?.unobserve(el)
    mediaProbeTargets.delete(el)
  },
}

/**
 * 点占位块：去把这份附件的字节弄到本地，然后显示/播放。
 *
 * <p>这是附件<b>唯一</b>会主动向后端要数据的地方（另一处是我们自己发出去的附件在发送成功那一刻
 * 顺手存盘）。顺序在 {@link ensureLocalMedia} 里：先读本地库，没有再走网络；
 * 取回来会顺手存进 {@code messages/u<我>/media/}，所以同一个附件只会下载一次，
 * 之后断网也能看。
 *
 * <p><b>为什么要挡一下重复点击</b>：视频几十 MB，等的时候用户很容易连点，
 * 每一下都是一次完整下载。所以用 {@code mediaLoading} 上锁，并且按钮会 disabled。
 */
async function loadMedia(message) {
  const key = message.key
  if (mediaLoading.has(key)) {
    return
  }
  mediaLoading.add(key)
  // 重试时先把"失败"标记清掉，否则下拉的时候用户看到的还是上一次的失败文案
  if (failedMedia.value.has(key)) {
    const next = new Set(failedMedia.value)
    next.delete(key)
    failedMedia.value = next
  }
  try {
    await ensureLocalMedia(message)
  } catch (e) {
    markMediaFailed(message)
    // 断网时这里就是"无法连接至服务器"；文件被删了就是"附件已不存在"
    ElMessage.error(e?.message || '附件加载失败')
  } finally {
    mediaLoading.delete(key)
  }
}

/**
 * 内容已经取回来了，但浏览器还是渲染不出来（编码不支持、字节损坏……）。
 *
 * <p>注意这跟"下载失败"是两件事：那一种在 {@link loadMedia} 里处理。
 * 到这里的只可能是"手上这份真的打不开"，所以换了本地副本也没用，直接落成失败占位。
 */
function onMediaBroken(message, label) {
  markMediaFailed(message)
  ElMessage.error(`${label}无法打开`)
}

/** 从附件 URL 里取出我们自己的文件名（不符合命名规则的返回 null） */
function mediaNameOf(content) {
  const name = attachmentUrlOf(content).split('/').pop() || ''
  return MEDIA_NAME_RE.test(name) ? name : null
}

/** 能内嵌播放的视频扩展名（服务端文件白名单里目前只有这两个） */
const VIDEO_EXTS = new Set(['mp4', 'mov'])

/** 取附件 URL 的扩展名（小写；没有扩展名返回空串） */
function extensionOf(content) {
  const name = attachmentUrlOf(content).split('/').pop() || ''
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

/**
 * 这条消息是不是"可以内嵌播放的视频"。
 *
 * <p>视频在服务端仍然算 {@code messageType=3}（文件），这里靠 MIME 或扩展名认出来 ——
 * 为什么不新加一个消息类型：那要同时改服务端校验白名单、列表预览文案、未读统计等等，
 * 而"是不是视频"从文件名就能判断，不值得动已经在跑的那套契约。
 *
 * <p>MIME 这一半是给"自己刚选完、还没上传成功"的那条消息用的：
 * 那时 content 还是 blob URL，看不出扩展名。
 */
function isVideoMessage(message) {
  if (message.messageType !== 3) {
    return false
  }
  if (typeof message.mimeType === 'string' && message.mimeType.startsWith('video/')) {
    return true
  }
  return VIDEO_EXTS.has(extensionOf(message.content))
}

/**
 * 视频的 src：和图片同一套规则 —— 只有本地有副本才给 blob，否则空串（显示"点击加载视频"）。
 *
 * <p>为什么不改成"直连服务端地址 + preload=metadata"：那样一进会话，浏览器就会为每个视频
 * 发一次请求去读元数据。用户只是扫一眼这个会话，却可能已经触发了几个 25MB 的下载。
 * 点击之后走 {@link loadMedia}：整份取下来存进本地库，然后再用 blob 播 ——
 * 只下一遍、而且当下就能离线重看。
 *
 * <p>代价是"点了要等下载完才能播"（没有边下边播）。这是刻意的：附件不像图片那样小到
 * 可以随便自动拉，让用户明确地为它点一次更合适。
 */
function videoSrcOf(message) {
  if (message.localPreview) {
    return message.localPreview
  }
  const name = mediaNameOf(message.content)
  if (!name) {
    return ''
  }
  return mediaUrls.get(name) || ''
}

/**
 * 把「自己刚发出去的附件」存一份到本地。
 *
 * <p><b>为什么这一步必须有</b>：不做的话会出现一个很别扭的现象 ——
 * <b>自己刚发出去的图片/视频，断网后自己也看不了</b>：本地没有副本、服务端又连不上，
 * 而这份文件本来就是自己选的。
 *
 * <p>发送成功的这一刻字节还在手上（{@code File} 对象），存下来<b>零网络成本</b>。
 * 不 await：存盘失败不该影响"已发送"，下次显示时还会再补一次。
 */
async function saveOwnAttachmentLocally(content, file) {
  const name = mediaNameOf(content)
  if (!name) {
    return
  }
  const bytes = new Uint8Array(await file.arrayBuffer())
  if(!valid()) return
  await saveLocalMedia(name, bytes)
}

/** 文件名 → MIME：blob 需要一个类型，否则 Chromium 可能不肯按图片渲染 */
function mimeOfName(name) {
  const ext = name.split('.').pop()
  const map = {
    png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp',
    pdf: 'application/pdf', txt: 'text/plain', md: 'text/markdown', csv: 'text/csv',
    mp3: 'audio/mpeg', wav: 'audio/wav', m4a: 'audio/mp4', mp4: 'video/mp4', mov: 'video/quicktime',
  }
  return map[ext] || 'application/octet-stream'
}

/** 把字节包成 blob URL 并记进缓存（同一个文件名只建一次） */
function cacheMediaUrl(name, bytes) {
  const existing = mediaUrls.get(name)
  if (existing) {
    return existing
  }
  const url = URL.createObjectURL(new Blob([bytes], { type: mimeOfName(name) }))
  if(!valid()) {URL.revokeObjectURL(url);return ''}
  mediaUrls.set(name, url)
  attachmentObjectUrls.add(url)   // 卸载时统一 revoke
  return url
}

/**
 * 确保这个附件有一份"本地内容"，返回可以直接给 &lt;img&gt;/&lt;a&gt; 用的 blob URL。
 *
 * <p>顺序：<b>先看本地消息库</b>（断网也能用）→ 没有再问服务端（附件永久保存）→
 * 取到就顺手存一份到本地。这就是"本地内容被删了，点一下还能从服务器取回来，
 * 消息位置不变"的实现：消息本身一个字都没动，只是重新把字节填回去。
 *
 * <p>服务端这一跳<b>一定走网络</b>（见 {@link fetchAttachmentBytes}）：本地删掉之后
 * 唯一的可信来源就是服务器，连不上就必须失败，不能拿浏览器缓存蒙混过去。
 *
 * @throws {Error} 本地没有、服务端也取不到（离线时 message 是"无法连接至服务器"）
 */
async function ensureLocalMedia(message) {
  const name = mediaNameOf(message.content)
  if (!name) {
    throw new Error('附件地址不合法')
  }
  const cached = mediaUrls.get(name)
  if (cached) {
    return cached
  }

  // ① 本地消息库里有没有
  const bytes = await readLocalMedia(name)
  if (bytes && bytes.byteLength > 0) {
    return cacheMediaUrl(name, bytes)
  }

  // ② 本地没有 → 从服务端取（这条路径不需要 token：附件是公开可读的）。
  //    必须 no-store：否则浏览器会拿磁盘缓存里的旧副本顶上，本地那份删了也"看得回来"
  const buffer = await fetchAttachmentBytes(assetUrl(attachmentUrlOf(message.content)))
  // ③ 取到就存本地：下次断网也能看
  if(!valid()) throw new Error('登录状态已变化')
  saveLocalMedia(name, buffer).catch(() => {})
  return cacheMediaUrl(name, buffer)
}

/**
 * 点文件卡片：确保本地有内容，然后触发一次"另存为"。
 *
 * <p>为什么不直接用 {@code <a href>} 指服务端地址：那样每次点击都从网络拉一遍，
 * 而且服务端那份拿不到的时候点了就是 404。走这条路径的话，本地有副本就直接用本地的。
 */
async function openAttachment(message) {
  try {
    const url = await ensureLocalMedia(message)
    const link = document.createElement('a')
    link.href = url
    link.download = fileNameOf(message)
    link.rel = 'noreferrer'
    document.body.appendChild(link)
    link.click()
    link.remove()
  } catch (e) {
    ElMessage.error(e?.message || '文件加载失败')
  }
}

/** 点图片在新窗口/新标签打开原图（优先用本地那份） */
async function openMedia(message) {
  try {
    const url = await ensureLocalMedia(message)
    if (url) {
      window.open(url, '_blank')
    }
  } catch {
    // 打开失败就什么都不做：图片本身已经在界面上了，没必要弹错
  }
}


function disposeMedia() {
 disposed=true;mediaObserver?.disconnect();mediaObserver=null
 for(const url of attachmentObjectUrls) URL.revokeObjectURL(url)
 attachmentObjectUrls.clear();mediaUrls.clear();mediaLoading.clear();mediaProbed.clear()
}
onBeforeUnmount(disposeMedia)
return {attachmentUrlOf,attachmentMetaOf,fetchAttachmentBytes,imageSrcOf,fileNameOf,fileSizeTextOf,mediaPlaceholder,markMediaFailed,probeLocalMedia,onMediaVisible,ensureMediaObserver,loadMedia,onMediaBroken,mediaNameOf,extensionOf,isVideoMessage,videoSrcOf,saveOwnAttachmentLocally,mimeOfName,cacheMediaUrl,ensureLocalMedia,openAttachment,openMedia,vLocalMedia,mediaLoading,attachmentObjectUrls,disposeMedia}
}
