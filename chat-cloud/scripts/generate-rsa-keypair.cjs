// 生成 RSA 密钥对：
//   私钥 -> chat-cloud/chat-auth/src/main/resources/rsa/private_key.pem （后端用来解密，绝不能外泄）
//
// 为什么这里【不再】生成前端的公钥文件了：
//   前端原本硬编码一份公钥（chat-web/src/utils/rsa-public-key.js），现在改成
//   启动时调 GET /api/auth/public-key 向 chat-auth 要。后端 RsaUtils 直接从私钥
//   推导出公钥，所以只需维护私钥这一个文件。
//   好处：换密钥只重启后端，不用重新打包 Electron 客户端（否则每个用户都得升级）。
//
// 用法：node chat-cloud/scripts/generate-rsa-keypair.cjs
const { generateKeyPairSync } = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')

const { privateKey } = generateKeyPairSync('rsa', {
  modulusLength: 2048,
  publicKeyEncoding: { type: 'spki', format: 'pem' },
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
})

// __dirname = <repo>/chat-cloud/scripts -> 上两级是仓库根 chat/
const repoRoot = path.resolve(__dirname, '..', '..')
const privatePath = path.join(
  repoRoot,
  'chat-cloud', 'chat-auth', 'src', 'main', 'resources', 'rsa', 'private_key.pem',
)

fs.mkdirSync(path.dirname(privatePath), { recursive: true })
fs.writeFileSync(privatePath, privateKey, 'utf8')

console.log('私钥已生成 ->', privatePath)
console.log('公钥由 chat-auth 的 RsaUtils 从私钥推导，通过 GET /api/auth/public-key 下发，无需落文件。')
console.log('注意：换密钥后，之前用旧公钥加密的请求体会解不开，用户需要刷新页面重新取公钥。')
