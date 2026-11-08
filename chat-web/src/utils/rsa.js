import JSEncrypt from 'jsencrypt'
import { api } from '../api'
import { randomHex } from './random'

// 注意 import 方向：这里 import api，而 api 只 import utils/auth（不 import rsa），
// 所以不存在循环依赖。加密工具依赖网络层是合理的，反过来才不合理。

// 后端下发的 RSA 公钥缓存（一个页面生命周期内只拉一次）
let cachedPublicKey = null
let loadingPromise = null

/**
 * 从后端获取 RSA 公钥。
 *
 * 公钥本身可以公开；由后端下发的好处是：将来换密钥时【不用重新打包前端】，
 * 这对 Electron 桌面端尤其重要 —— 否则所有用户的客户端都得跟着升级。
 */
async function fetchPublicKey() {
  if (cachedPublicKey) return cachedPublicKey
  // 多个请求并发时只发一次，避免重复拉取
  if (loadingPromise) return loadingPromise

  // 这里走 api.getPublicKey 而不是裸 fetch：
  // 裸 fetch 会绕过 BASE_URL，一旦打包后设了 VITE_API_BASE，
  // 这个请求就会打到 file:// 上直接失败。统一由 api 层拼地址。
  loadingPromise = api.getPublicKey()
    .then((key) => {
      if (!key) throw new Error('获取加密公钥失败')
      cachedPublicKey = key
      return cachedPublicKey
    })
    .finally(() => {
      loadingPromise = null
    })

  return loadingPromise
}

/** 页面启动时预热公钥，用户点注册时就不用再等这一次请求 */
export function warmUpPublicKey() {
  fetchPublicKey().catch(() => {
    // 预热失败不打扰用户，真正提交时会再报错
  })
}

/**
 * 生成一次性随机数 nonce（16 字节 → 32 位十六进制字符串）。
 *
 * <p>实现细节（为什么不用 Math.random、为什么不用 randomUUID）统一在 utils/random.js 里，
 * 这里只是把"取多少字节"定下来 —— 32 位十六进制足够防碰撞。
 */
export function createNonce() {
  return randomHex(16)
}

/**
 * 构造并加密「凭证载荷」= 时间戳 + nonce + 密码，整体加密成一个密文。
 *
 * 加密前的明文格式：`时间戳|nonce|密码`
 *   - 密码放在【最后】，配合后端 `split("|", 3)`：即使密码里含 `|` 也不会被切错
 *     （前两段是纯数字和十六进制，不可能含 `|`）
 *   - 用分隔符而不是 JSON：Spring Boot 4 换成了 Jackson 3，后端不值得为解析一个字符串再引 JSON 库
 *
 * 为什么三者必须一起进密文？
 *   nonce 和 timestamp 一旦裸露在密文外面，攻击者重放时只要顺手改个 nonce，
 *   后端的"用过没"校验就绕过去了。放进密文内部后，攻击者既解不开也改不动。
 *
 * @param {string} password 明文密码
 * @returns {Promise<string>} Base64 密文
 */
export async function encryptCredentials(password) {
  if (!password) return ''

  const publicKey = await fetchPublicKey()

  const encryptor = new JSEncrypt()
  encryptor.setPublicKey(publicKey)

  const plain = `${Date.now()}|${createNonce()}|${password}`
  const encrypted = encryptor.encrypt(plain)
  if (!encrypted) {
    throw new Error('密码加密失败，请刷新页面后重试')
  }
  return encrypted
}
