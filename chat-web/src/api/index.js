// 统一的后端请求封装。
//
// 后端所有接口的返回体都是同一个壳（chat-common 的 Result）：
//
//   { "code": 200, "message": "success", "data": ... }
//
// 所以这一层只做【一件事】：拆壳。业务代码拿到的永远是 data 本身，
// 不用再写 `if (res.code === 200)` —— 那判断只在这一层出现一次。
//
// 开发环境走 Vite 代理（/api -> http://localhost:8080），生产环境可设 VITE_API_BASE 为线上域名。
import { ElMessage } from 'element-plus'
import { getToken, clearLogin } from '../utils/auth'
import { sendChatMessage } from '../utils/chatSocket'

const BASE_URL = import.meta.env.VITE_API_BASE || ''

/**
 * "连不上服务器"的统一文案。
 *
 * <p>为什么定成一个导出常量：这句话会出现在<b>发消息、取头像、拉列表、加载附件</b>……
 * 很多条路径里。各写各的迟早变成"无法连接服务器 / 网络异常 / 连接超时"三种说法，
 * 用户会以为是三种不同的毛病。全站只有这一句。
 */
export const UNREACHABLE_TEXT = '无法连接至服务器'

/**
 * 普通请求的超时时间（毫秒）。
 *
 * <p>为什么要设：后端进程挂了但端口还连着（或者网络半通不通）时，{@code fetch} 会一直
 * 挂着不返回 —— 界面表现就是"点了没反应"，比报错更难懂。超时之后统一按"连不上"处理。
 * 上传大文件不设超时（50MB 慢网可能要几分钟），复用同一个值只会误杀正常上传。
 */
const REQUEST_TIMEOUT_MS = 15_000

/**
 * 把后端返回的<b>相对</b>资源地址拼成能直接用的完整 URL。
 *
 * <p>为什么需要它：头像上传后，数据库里存的是 {@code /api/user/uploads/avatars/xxx.png}
 * 这样的<b>相对路径</b>（不写域名 —— 否则换机器/换端口，库里所有头像就全坏了）。
 * 而 {@code <img src>} 需要一个完整地址：页面本身在 {@code file://}（Electron 打包后）
 * 或 {@code http://localhost:5173}（开发）下，直接用相对路径会去这些地方找图片，必然是裂图。
 *
 * <p>外链（用户自己填的 {@code https://...}）原样返回，不做任何加工。
 *
 * @param {string|null|undefined} url 数据库里存的地址
 * @returns {string|undefined} 能直接塞给 img/el-avatar 的地址；空值返回 undefined（组件会走兜底）
 */
export function assetUrl(url) {
  if (!url) {
    return undefined
  }
  // 已经是完整地址（http/https/data/blob）就原样用
  if (/^(https?:)?\/\//i.test(url) || url.startsWith('data:') || url.startsWith('blob:')) {
    return url
  }
  // 相对路径：补上 API 基址（BASE_URL 为空时就是同源，正好是开发环境用 Vite 代理的情况）
  return `${BASE_URL}${url.startsWith('/') ? '' : '/'}${url}`
}

/** 业务成功码。后端 Result.success() 用的就是 200，不是 0 */
const CODE_OK = 200

/**
 * 统一的请求错误。三种失败用同一套结构表达，界面能据 code 做精细处理：
 *
 *   code = -1   网络层不通（后端没起 / Vite 代理指向的地址没人监听）
 *   code = 401  token 不存在、已过期，或被新登录顶掉了
 *   code = 其他 后端业务码：400 参数错、409 冲突、429 锁定、503 服务不可用……
 *
 * 区分出 code 之后，界面才能写出"401 就回登录页、429 就提示倒计时"这种逻辑，
 * 而不是所有错误都弹一句"操作失败"。
 */
export class ApiError extends Error {
  constructor(message, code, httpStatus = 0) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
  }
}

/**
 * 状态码 → 一句人话（只在"响应里没有 message"时兜底用）。
 *
 * <p>为什么不全靠后端：有些失败<b>根本到不了后端</b> —— 网关没起来时，
 * Vite 代理会直接回一个 500 加一段 HTML 错误页；网关自己挂了也会回 502/503。
 * 这些响应里没有我们那套 {@code {code,message}}，界面上总得说点什么，
 * 而"后端返回 HTTP 500"对用户毫无意义。
 */
function httpStatusText(status) {
  if (status === 401) return '登录已过期，请重新登录'
  if (status === 403) return '没有权限访问'
  if (status === 404) return '请求的内容不存在'
  if (status === 408) return '请求超时，请重试'
  if (status === 413) return '内容太大，请选择小一点的文件'
  if (status === 429) return '操作太频繁，请稍后再试'
  // 5xx 且没有后端文案 = 后端不可达（网关没起 / 服务挂了 / 进程异常）
  if (status >= 500) return UNREACHABLE_TEXT
  return '请求失败，请稍后重试'
}

/**
 * 发请求并拆掉 Result 外壳。
 *
 * @param {string} path 接口路径
 * @param {object} [options] method / body / headers / silent，都可省略
 * @param {boolean} [options.silent] 失败时<b>不弹提示</b>。给"后台任务"用：
 *        重连补拉、定时拉会话列表、登录后台刷身份缓存 —— 这些没有用户动作，
 *        断网时每 60 秒弹一次红块只会把界面变成噪音（真的连不上时，
 *        实时通道那一路会等 8 秒后说一次就够了）。
 *        用户主动点的操作（发消息、加好友、保存资料……）一律<b>不</b>静默。
 * @returns {Promise<any>} 后端 Result 里的 data；data 为空时返回 null
 * @throws {ApiError} 网络不通、HTTP 非 2xx、code 非 200，全部归一成 ApiError
 */
async function request(path, { method = 'GET', body, headers, silent = false } = {}) {
  const token = getToken()

  /*
    文件上传（FormData）必须和 JSON 请求分开处理，两处都不能合并：
      ① Content-Type：multipart 的 boundary 是浏览器生成并写进头里的，
         我们手写一个 "multipart/form-data" 会把 boundary 弄丢，后端解析直接失败；
      ② body：FormData 不能 JSON.stringify —— 那会变成字符串 "{}"，
         文件整个丢掉，而且请求看着"成功"，排查起来极其费劲。
    这两条合起来就是"上传文件不生效"最常见的原因。
  */
  const isFormData = typeof FormData !== 'undefined' && body instanceof FormData

  let res
  try {
    res = await fetch(`${BASE_URL}${path}`, {
      method,
      headers: {
        ...(body && !isFormData ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(headers || {}),
      },
      body: body ? (isFormData ? body : JSON.stringify(body)) : undefined,
      // 上传不设超时（见 REQUEST_TIMEOUT_MS 的说明）；其余请求到点自动放弃，
      // 免得"端口还在、进程已经不动了"时界面一直转圈。
      // 先判 AbortSignal 存不存在：老一点的运行时不认识它，直接写会抛 ReferenceError
      signal: isFormData || typeof AbortSignal === 'undefined' || typeof AbortSignal.timeout !== 'function'
        ? undefined
        : AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    })
  } catch (e) {
    // 提示要短：界面上只说明"连不上"，具体是哪个接口、后端该起在哪个端口属于排查信息，
    // 放 console 给开发看就够了（用户看到的是页面正中那一块红提示）。
    console.warn(`[api] 请求失败：${path}（后端是否已启动？chat-gateway 应在 8080）`, e)
    /*
      提示在这一层就弹掉，而不是等调用方去 catch。
      因为有些请求的调用方是"后台任务"（启动时拉身份缓存、重连后拉会话列表），
      它们会把异常吞掉不显示 —— 那时用户看到的是"点了没反应"，
      比一句"无法连接至服务器"糟糕得多。
      （重复提示不用管：同一个文案会被 toast 的 grouping 合并成一条。）
      silent 的那批请求是例外，原因见 request 的注释。
    */
    if (!silent) {
      ElMessage.error(UNREACHABLE_TEXT)
    }
    throw new ApiError(UNREACHABLE_TEXT, -1)
  }

  // 先按文本读，再尝试当 JSON 解析 —— 后端出错时返回的可能是 HTML 错误页，直接 res.json() 会炸。
  // 注意这里的"JSON 解析失败"只影响能不能读到 message，不影响后续判断：
  // 是不是合法响应，一律以 HTTP 状态码和 Result.code 为准。
  const text = await res.text()
  let data = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    // 不是 JSON，保持 null
  }

  // ---- ① HTTP 层：非 2xx ----
  // 两种可能，必须分开看：
  //   a) 后端自己回的错误（我们已在各服务里保证：任何异常都带 {code,message}）→ 直接用它的 message；
  //   b) 响应根本不是我们的 Result —— 最常见的是网关没起时 Vite 代理回的 500 HTML 错误页、
  //      或者请求在 controller 之外失败（Spring 默认错误页）。
  // 以前 b 会把状态码原样念出来（"后端返回 HTTP 500"），用户看不懂、也没法处理。
  // 现在 b 一律换成一句人话，真实的状态码和响应片段只写进 console（排查用）。
  if (!res.ok) {
    if (res.status === 401) {
      // 网关/后端说这个 token 不认了。本地留着只会让后续每个请求都失败，
      // 直接清掉，界面就可以顺势把用户送回登录页。
      clearLogin()
    }
    const serverMessage = typeof data?.message === 'string' && data.message.trim() ? data.message : ''
    if (!serverMessage) {
      console.warn(
        `[api] ${path} 失败：HTTP ${res.status}，且响应不是 Result 结构 —— `
        + '多半是网关/后端没起来（或请求在 controller 之外就失败了）。响应片段：',
        text.slice(0, 300),
      )
    }
    throw new ApiError(serverMessage || httpStatusText(res.status), res.status, res.status)
  }

  // ---- ② 业务层：HTTP 200 但 Result.code != 200 ----
  // 后端的 GlobalExceptionHandler 就是这么返回的：状态码 200，壳里的 code 是 4xx/5xx。
  if (data && typeof data.code === 'number') {
    if (data.code !== CODE_OK) {
      if (data.code === 401) clearLogin()
      throw new ApiError(data.message || '请求失败', data.code, res.status)
    }
    // 成功：把 data 交给业务代码。后端不返回业务数据时（Result<Void>）这里是 null。
    return data.data === undefined ? null : data.data
  }

  // ---- ③ 兜底：200 且没有 code 字段 ----
  // 正常不会走到这里。真走到了说明响应不是本后端的 Result 结构
  // （比如网关自己返回的错误页），直接原样交出去，别假装成功也别硬报错。
  if (import.meta.env.DEV) {
    console.warn(`[api] ${path} 的响应没有 Result.code 字段，已按裸数据返回`, data)
  }
  return data
}

export const api = {
  // ---- 认证相关（chat-auth）----

  /**
   * 获取 RSA 公钥（PEM 文本）。
   *
   * <p>后端返回 Result&lt;String&gt;，data 就是公钥本身。
   * 公钥由后端下发而不是硬编码在前端：将来换密钥不用重新打包 Electron 客户端。
   *
   * @returns {Promise<string>} PEM 格式公钥；拿不到时返回空字符串
   */
  getPublicKey: async () => {
    const pem = await request('/api/auth/public-key')
    return pem || ''
  },

  /**
   * 发送短信验证码。
   *
   * <p>后端返回 Result&lt;SmsCodeResponse&gt;，data 是 { code }：
   * 只有开发环境（chat.sms.expose-code=true）才有值，生产环境是 null。
   * 这里统一成对象，调用方就不用写 `res?.code` 这种可选链了。
   *
   * @returns {Promise<object>} 形如 { code }，生产环境下 code 为 null
   */
  sendSmsCode: async (phone) => {
    const data = await request('/api/auth/sms-code', { method: 'POST', body: { phone } })
    return data || { code: null }
  },

  /**
   * 注册。
   *
   * <p>请求体：username / phone / password / code，其中 password 是 RSA 密文。
   * 后端返回 Result&lt;Void&gt; —— 成功即成功，没有任何业务数据，
   * 所以这里也返回 null，前端拿到不报错就说明注册好了。
   */
  register: (payload) => request('/api/auth/register', { method: 'POST', body: payload }),

  /**
   * 登录。
   *
   * <p>请求体：phone / password，password 是 RSA 密文。
   * 后端返回 Result&lt;String&gt;，<b>data 就是 token 本身</b>。
   *
   * @returns {Promise<string>} token
   */
  login: (payload) => request('/api/auth/login', { method: 'POST', body: payload }),

  /** 登出：token 由 request 自动放进 Authorization 头 */
  // silent：登出自己会说"服务端没响应"，这里不必再叠一句"连不上"
  logout: () => request('/api/auth/logout', { method: 'POST', silent: true }),

  /**
   * 【修改密码】—— ⚠️ <b>后端接口还没做</b>，这里先把前端这一头的契约定下来，
   * 等后端按这个约定实现即可（路径和字段名都是约定，不是猜的）。
   *
   * <pre>
   *   POST /api/auth/password
   *   { phone, code, password }
   * </pre>
   *
   * <ul>
   *   <li>{@code phone} + {@code code}：短信验证码，和注册共用同一个发码接口
   *       （{@link sendSmsCode}）。手机号要用户自己填 —— 见下面那条说明。</li>
   *   <li>{@code password}：<b>RSA 密文</b>（{@code 时间戳|nonce|新密码}），
   *       和登录 / 注册同一套（{@link encryptCredentials}）。明文密码绝不上网。</li>
   * </ul>
   *
   * <p><b>为什么手机号要用户自己填，而不是从 {@code /user/me} 带出来？</b>
   * 因为手机号是敏感字段，{@code UserDetailVO} 里<b>故意没有它</b>
   * （连"看自己的资料"也不返回）。所以前端根本拿不到，只能让用户输入，
   * 由后端校对"这个手机号是不是当前登录用户的"。
   *
   * <p><b>为什么归属 chat-auth 而不是 chat-user？</b> 改密码改的是<b>登录凭证</b>，
   * 和登录 / 注册是一类事（都在 auth 里），而不是"用户资料"的一部分
   * —— {@code UpdateProfileRequest} 的注释里也写明了它故意不含 password。
   *
   * @returns {Promise<void>} 后端返回 Result&lt;Void&gt;，成功时 request 返回 null
   */
  changePassword: (payload) => request('/api/auth/password', { method: 'POST', body: payload }),

  // ---- 唯一性校验（chat-user，经网关直连，少一跳）----

  /**
   * 手机号是否可用。用户名不查重（允许重复），所以这里只有手机号。
   *
   * <p>后端返回 Result&lt;Boolean&gt;，data = true 表示未注册（可用）。
   *
   * @returns {Promise<boolean>}
   */
  checkPhone: async (phone) => {
    const available = await request(`/api/user/check-phone?phone=${encodeURIComponent(phone)}`)
    // 后端理论上只会返回 true/false；用 === true 兜住 null，避免把"没查到"当成"可用"
    return available === true
  },

  // ---- 用户资料（chat-user，经网关）----

  /**
   * 【看自己】查当前登录用户的资料。
   *
   * <p>身份由网关从 token 解析（注入 X-User-Id），所以<b>不需要传任何参数</b> ——
   * 这也是前端唯一能知道"我是谁（我的 userId）"的途径：登录只返回 token，
   * 不返回 userId。
   *
   * @returns {Promise<object>} 详情形状：{ userId, username, nickName, avatarUrl, gender, birthDate }
   */
  // silent：登录后后台刷新身份缓存用的，断网时不该隔一会儿就弹一次红块
  me: () => request('/api/user/me', { silent: true }),

  /**
   * 【看别人】按 userId 查资料（看主页、渲染好友列表/消息列表都用它）。
   * 后端会先查 Redis 缓存，未命中才查库。
   *
   * <p>这是"点进去看某个人"用的<b>详情形状</b>，比搜索卡片多一个 {@code username}。
   *
   * @param {number|string} userId
   * @returns {Promise<object>} 同上（详情形状）
   */
  getUser: (userId) => request(`/api/user/${encodeURIComponent(userId)}`),

  /**
   * 【搜人】按手机号<b>精确</b>查用户。加好友前先调它，把手机号"翻译"成 userId。
   *
   * <p>⚠️ 这个接口是"手机号枚举器"：拿一份手机号列表批量问，就能摸出"谁注册过、叫什么"。
   * 目前只有"必须登录"这一层防护，<b>限流还没做</b>（网关的限流过滤器以后再加），
   * 所以别拿它做"边打字边搜"的实时查询，也别批量调用。
   *
   * @param {string} phone 完整 11 位手机号
   * @returns {Promise<object>} <b>卡片形状</b>：{ userId, nickName, avatarUrl, gender, birthDate }
   *          —— 没有 username（登录名只在详情里给），也没有 updatedAt（那是缓存版本号）；
   *          没注册时后端返回 404（ApiError.code === 404，message 是"该手机号未注册"）
   */
  searchUser: (phone) => request(`/api/user/search?phone=${encodeURIComponent(phone)}`),

  /**
   * 【改资料】改自己的昵称 / 性别 / 生日 / 头像。
   *
   * <p><b>只传要改的字段</b>（部分更新）：没传的保持原样。
   * 注意<b>空字符串等于"不改"</b>，所以这个接口不能用来清空字段。
   * 身份由网关提供，请求体里没有 userId。
   *
   * <h3>为什么是 multipart（FormData）而不是普通 JSON</h3>
   * 因为要能直接传一张图片。两个部分<b>都可选</b>：
   * <pre>
   *   profile : application/json  →  只传要改的字段
   *   avatar  : image/*           →  图片字节本身
   * </pre>
   * 只改昵称就只传 profile，只换头像就只传 avatar。
   *
   * <p>⚠️ 两个必须做对的细节：
   * <ol>
   *   <li>JSON 那一部分要显式包成 {@code application/json} 的 Blob ——
   *       否则它会被当成普通文本字段，后端 {@code @RequestPart UpdateProfileRequest} 解析不出来
   *       （表现为 415 或"参数为空"，而且报错信息不会告诉你原因）；</li>
   *   <li><b>不要设 Content-Type</b>：multipart 的 boundary 由浏览器自动生成并写进
   *       Content-Type。手写一个 {@code multipart/form-data} 会把 boundary 弄丢，
   *       后端直接解析失败。所以 request() 里对 FormData 是跳过设置头的。</li>
   * </ol>
   *
   * @param {{nickName?: string, gender?: string, birthDate?: string, avatarUrl?: string}} payload 要改的字段
   * @param {File|null} [avatarFile] 选中的图片文件；不传表示这次不换头像
   * @returns {Promise<object>} 更新后的完整资料（<b>详情形状</b>，可以直接拿来刷新界面，不用再调 me()）
   */
  updateProfile: (payload, avatarFile = null) => {
    const form = new FormData()
    // 只有真的有字段要改时才带 profile 部分：空对象会变成 "{}"，
    // 后端把它解析成一个四个字段全 null 的记录，语义上没问题，但白占一次解析
    if (payload && Object.keys(payload).length > 0) {
      form.append('profile', new Blob([JSON.stringify(payload)], { type: 'application/json' }))
    }
    if (avatarFile) {
      form.append('avatar', avatarFile)
    }
    return request('/api/user/profile', { method: 'PUT', body: form })
  },

  // ---- 好友 / 联系人（chat-relation）----
  //
  // 这一组接口的"我是谁"全由网关注入的 X-User-Id 决定，所以参数里只有"对方是谁"。
  // 后端路径的规律：前端写 /api/relation/**，网关去掉 /api 再转发（服务里是 /relation/friend/**）。

  /**
   * 【好友列表】当前登录用户的所有好友。
   *
   * <p>每一项 = 用户卡片 + 关系域自己的字段：
   * <pre>
   * {
   *   userId,     // 好友的 userId —— 后续操作都用它，不是手机号
   *   nickName,   // 对方自己设的昵称
   *   avatarUrl,  // 头像地址，没设就是 null
   *   gender,     // M / F / null
   *   birthDate,  // "2000-01-01" / null
   *   remark      // 我给对方起的备注，没设就是 null（显示和排序都优先用它）
   * }
   * </pre>
   * 注意<b>没有 username</b>（登录名只在"点进去看详情"时给），也<b>没有首字母</b>：
   * 后端不算拼音（那要引拼音库），前端自己按首字兜底，排序用浏览器自带的
   * Intl.Collator（中文照样按拼音排，只是分组标题粗一点）。
   *
   * @returns {Promise<Array>} 好友数组；没有好友时是空数组
   */
  // silent：登录 / 换账号时后台拉一次，不是用户点的动作
  listFriends: () => request('/api/relation/friend/list', { silent: true }),

  /**
   * 【我和他是什么关系】—— 加好友页据此决定按钮显示什么。
   *
   * <p>为什么必须有它：搜索结果里只有"这个人的资料"，前端看不到关系表，
   * 否则只能永远显示"添加到通讯录"，点了才发现"已经是好友了"。
   *
   * @param {number|string} userId 对方 userId
   * @returns {Promise<string>} NONE / PENDING_OUT / PENDING_IN / FRIEND / SELF
   */
  friendStatus: (userId) =>
    request(`/api/relation/friend/status?userId=${encodeURIComponent(userId)}`),

  /**
   * 【加好友】发起申请。
   *
   * <p>后端对这些情况会返回明确的错误（"你们已经是好友了"、"已发送申请，等待对方验证"），
   * 调用方不用自己判断，直接把 message 显示给用户就行。
   *
   * <p>还有一种情况：<b>对方也申请过我</b> —— 后端会直接让两人成为好友，不用再点同意。
   *
   * @param {number|string} toUserId 对方 userId（不是手机号）
   * @param {string} [message] 验证消息，可以不填
   */
  addFriend: (toUserId, message) =>
    request('/api/relation/friend/request', { method: 'POST', body: { toUserId, message } }),

  /**
   * 【新的朋友】别人申请加我的、还没处理的。
   *
   * <p>每一项：{ friendRequestId, fromUserId, nickName, avatarUrl, message, status, createdAt }
   * 注意 {@code friendRequestId} 是<b>申请记录的 id</b>，同意/拒绝要传它 ——
   * 不是 {@code fromUserId}（那是申请人的用户 id）。
   * 后端故意不用含糊的 {@code id}，就是为了防这两个 id 传错。
   *
   * @returns {Promise<Array>}
   */
  // silent：同上（重连成功时也会后台刷一次）
  listFriendRequests: () => request('/api/relation/friend/requests', { silent: true }),

  /** 同意一条申请（friendRequestId = 申请记录的 id） */
  acceptFriendRequest: (friendRequestId) =>
    request(`/api/relation/friend/request/${encodeURIComponent(friendRequestId)}/accept`, {
      method: 'POST',
    }),

  /** 拒绝一条申请（之后对方还可以再申请） */
  rejectFriendRequest: (friendRequestId) =>
    request(`/api/relation/friend/request/${encodeURIComponent(friendRequestId)}/reject`, {
      method: 'POST',
    }),

  /**
   * 【改备注】备注是单向的，只影响我自己的好友列表。
   *
   * @param {number|string} friendId 好友的 userId
   * @param {string} remark 新备注；传空串表示清除备注（回到显示对方昵称）
   */
  updateFriendRemark: (friendId, remark) =>
    request(`/api/relation/friend/${encodeURIComponent(friendId)}/remark`, {
      method: 'PUT',
      body: { remark },
    }),

  /** 【删好友】双向的两行一起删；想再加回来要重新申请、对方同意 */
  deleteFriend: (friendId) =>
    request(`/api/relation/friend/${encodeURIComponent(friendId)}`, { method: 'DELETE' }),

  /** 【查单个好友】拿"我给他起的备注" —— 聊天页标题要用（备注优先，没有才显示昵称） */
  getFriend: (friendId) => request(`/api/relation/friend/${encodeURIComponent(friendId)}`),

  // ---- 群管理：创建/加入/邀请均走HTTP，群消息发送是后续功能 ----
  createGroup: (groupName, clientRequestId) => request('/api/relation/group', {
    method: 'POST', body: { groupName, clientRequestId },
  }),
  groupSyncHeads: (after=0) => request('/api/message/group/sync-heads?after='+encodeURIComponent(after),{silent:true}),
  pullGroupMessages: (groupId,memberVersion,afterSeq) => request('/api/message/group/'+encodeURIComponent(groupId)+'/messages?memberVersion='+encodeURIComponent(memberVersion)+'&afterSeq='+encodeURIComponent(afterSeq),{silent:true}),
  myGroups: (after = 0) => request(`/api/relation/group/mine?after=${encodeURIComponent(after)}`),
  searchGroups: (keyword) => request(`/api/relation/group/search?keyword=${encodeURIComponent(keyword)}`),
  groupDetail: (groupId) => request(`/api/relation/group/${encodeURIComponent(groupId)}`),
  joinGroup: (groupId) => request(`/api/relation/group/${encodeURIComponent(groupId)}/join`, { method: 'POST' }),
  inviteToGroup: (groupId, userIds) => request(`/api/relation/group/${encodeURIComponent(groupId)}/invitations`, {
    method: 'POST', body: { userIds },
  }),
  groupInvitations: (after = 0) => request(`/api/relation/group/invitations?after=${encodeURIComponent(after)}`),
  groupInvitationCount: () => request('/api/relation/group/invitations/count', { silent: true }),
  requestReminderSummary: () => request('/api/relation/requests/summary', { silent: true }),
  acceptGroupInvitation: (id) => request(`/api/relation/group/invitations/${encodeURIComponent(id)}/accept`, { method: 'POST' }),
  rejectGroupInvitation: (id) => request(`/api/relation/group/invitations/${encodeURIComponent(id)}/reject`, { method: 'POST' }),
  groupMembers: (groupId, after = 0) => request(`/api/relation/group/${encodeURIComponent(groupId)}/members?after=${encodeURIComponent(after)}`),

  // ---- 消息 / 单聊（chat-message）----
  //
  // 发消息走 WS，并等待关联的 send-result；收消息也走 WS，上传和补拉仍走 HTTP。

  /**
   * 【发消息】。
   *
   * <p>{@code clientMessageId} 由前端生成（UUID），作用是<b>幂等</b>：
   * 没收到受理结果时重发同一条，服务端靠它保证只落一条，并把已存在的那条返回回来。
   *
   * @param {number} messageType 1=文本 2=图片 3=文件 4=语音。
   *        ⚠️ 图片/文件消息的 {@code content} 必须是 {@link uploadAttachment} 返回的 URL ——
   *        服务端会校验它是不是"我们自己签发的地址"（不允许任意外链）
   * @returns {Promise<object>} { messageId, conversationId, createdAt, duplicated }
   */
  sendMessage: (toUserId, content, clientMessageId, messageType = 1) =>
    sendChatMessage(toUserId, content, clientMessageId, messageType),

  /**
   * 【上传附件】图片 / 文件先传这里，拿到 URL 再用 {@link sendMessage} 发出去。
   *
   * <p>为什么"文件字节走 HTTP、消息走 HTTP"而不是塞进 WebSocket：
   * WS 是文本帧、而且握手/断线重连那套完全不适合传几十 MB 的二进制；
   * 上传要进度、要取消、要 413 这类明确的状态码，这些都是 HTTP 的强项。
   * 消息本身也只是"一段 URL 的文本"，照旧走 {@code POST /message/send}；
   * WS 那边只负责把这条消息<b>推给接收方</b>。
   *
   * <p>服务端会按<b>文件头</b>判类型（图片必须真的是图片）、按扩展名白名单判文件，
   * 并给出 50MB 上限 —— 所以这里的 {@code messageType} 不要自己猜，用返回值里的。
   *
   * @param {File} file 用户选的文件
   * @returns {Promise<object>} { url, name, size, messageType }：
   *          {@code url} 是带 {@code ?n=&s=} 的相对地址（n=原始文件名，s=字节数），
   *          直接当消息内容用；{@code messageType} 是服务端判定的 2 或 3
   */
  uploadAttachment: (file) => {
    const form = new FormData()
    form.append('file', file)
    return request('/api/message/upload', { method: 'POST', body: form })
  },

  /**
   * 【会话列表】消息页用：每个会话一行 —— 对方是谁、最后一条的摘要、未读数、回执水位。
   *
   * <p>它<b>不含任何具体消息</b>（那是 messageHistory 的活），也不改任何状态（纯读）。
   * 一次返回全部会话（默认 50、上限 200），所以进消息页调一次就够 ——
   * 不用"每个会话问一次"。
   *
   * <p>响应里的 {@code deliveredUpTo / readUpTo} 是"我发的消息被对方送到/读到哪条为止"。
   * 送达/已读回执是<b>一次性 WS 推送</b>，我离线期间对方读了的话根本推不到我这来 ——
   * 这两个字段就是那份真相，客户端拿它推进本地水位就行，不用为此再拉一遍消息。
   *
   * <p>未读数也是权威值（按服务端的读游标算）：客户端本地算的那个只覆盖它留存的几条，
   * 换台机器更是完全没有 —— 所以进消息页是"本地先画、再用这个接口校正"。
   *
   * @param {number} [size] 最多要几个会话；不传由服务端决定
   * @returns {Promise<Array>} 按最后一条消息时间倒序；没有会话时是空数组
   */
  // silent：连上/重连时的后台校正，断网时靠实时通道那一路提示，不在这里刷屏
  messageConversations: (size = null) =>
    request(`/api/message/conversations${size ? `?size=${size}` : ''}`, { silent: true }),

  /**
   * 【拉历史消息】按对方 userId 定位会话（还没聊过就返回空数组，不会建空会话）。
   *
   * <p>⚠️ 目前<b>没有调用点</b>：进入会话只读本地库（见 Chat.vue 顶部说明）。
   * 接口留着给以后的"同步 / 往上翻页"用。
   *
   * @param {object} [options] beforeId 翻页锚点（只取比它更早的）；size 一次多少条
   * @returns {Promise<Array>} 消息数组，<b>从旧到新</b>
   */
  messageHistory: (peerUserId, { beforeId = null, size = null } = {}) => {
    const params = new URLSearchParams({ peerUserId: String(peerUserId) })
    if (beforeId) {
      params.set('beforeId', String(beforeId))
    }
    if (size) {
      params.set('size', String(size))
    }
    return request(`/api/message/history?${params.toString()}`)
  },

  /**
   * 【补拉未送达】用户上线/重连后立刻调它 —— 这是"不丢消息"的兜底。
   *
   * <p>断线期间的消息、服务端超过 3 分钟放弃实时重推的消息，都靠它捞回来。
   * 拿到之后要<b>逐个回 ack</b>（已经收到了），否则服务端会继续认为它们没送达。
   */
  // silent：60 秒一次的补拉是后台兜底，断网时不该每分钟弹一次（实时通道那一路会说）
  undeliveredMessages: (size = null) =>
    request(`/api/message/undelivered${size ? `?size=${size}` : ''}`, { silent: true }),

  /**
   * 【送达确认（HTTP 版）】—— 只在"没有实时通道"时用（比如纯浏览器里跑）。
   *
   * <p>正常路径是在 WebSocket 上回 ack，但浏览器不允许自定义握手请求头，
   * 所以 WS 只建在 Electron 主进程里。没有它的时候用这个接口兜底 ——
   * 服务端两条路走的是同一段幂等逻辑，多发一次没有副作用。
   */
  markMessagesDelivered: (messageIds) =>
    request('/api/message/delivered', {
      method: 'POST',
      body: { messageIds },
      // silent：这是"回执"类请求，失败没有任何用户可做的事，重连后还会再发一遍
      silent: true,
    }),

  /**
   * 【上报已读 —— HTTP 版】我读到哪条了（服务端取 max，乱序上报不会倒退）。
   *
   * <p>⚠️ 正常的调用路径现在是 <b>WS 帧</b> {@code {type:'read-report', …}}
   * （见 Chat.vue 的 sendReadToServer）。这个接口是<b>兜底</b>：
   * 纯浏览器里没有 WS、WS 还没连上、或者发帧失败时才用它。
   * 两条路在服务端走的是同一段 {@code MessageService#markRead}，重复上报无害。
   */
  markMessageRead: (peerUserId, lastReadMessageId) =>
    request('/api/message/read', {
      method: 'POST',
      body: { peerUserId, lastReadMessageId },
      // silent：已读上报是"顺手的事"，失败也不影响正在看的界面（下次进会话会再报）
      silent: true,
    }),
}
