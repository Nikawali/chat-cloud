// 随机数相关的小工具。
//
// 目前只有 randomHex：两处都在做"取 N 字节随机数再转十六进制"——
// nonce（登录防重放）和 clientMessageId（发送幂等）。规则只有一条，但很关键。

/**
 * 取一个随机十六进制字符串。
 *
 * <p><b>为什么必须用 {@code crypto.getRandomValues} 而不是 {@code Math.random()}</b>：
 * 后者伪随机且可预测 —— 攻击者能算出下一个 nonce，防重放就失效了；
 * 而 clientMessageId 撞车会让两条不同的消息被服务端当成同一条（唯一索引把它们合并）。
 *
 * <p><b>为什么不用 {@code crypto.randomUUID()}</b>：它要求"安全上下文"，
 * 而打包后页面跑在 {@code file://} 下 —— 与其赌浏览器把它算作安全上下文，
 * 不如用 {@code getRandomValues}（任何上下文都能用）。
 *
 * @param {number} byteLength 取多少字节（1 字节 = 2 个十六进制字符）
 * @returns {string} 小写十六进制，长度 = byteLength * 2
 */
export function randomHex(byteLength) {
  const bytes = new Uint8Array(byteLength)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}
