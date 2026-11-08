// 主进程侧的「凭证保险箱」。
//
// 职责：把渲染进程交来的登录凭证用【系统级加密】存起来，下次启动再原样还回去。
//
// 为什么放在主进程，而不是渲染进程的 localStorage：
//   localStorage 是明文，页面上的任意 JS（包括被注入的 XSS 脚本）都能直接读走。
//   放到主进程后，渲染进程只能通过 preload 暴露的两个方法"存取"，
//   既看不到密文也拿不到密钥 —— 密钥由 safeStorage 交给操作系统派生：
//     Windows → DPAPI（绑定当前用户账号）
//     macOS   → Keychain
//     Linux   → libsecret / kwallet
//   换句话说，把文件拷到别的机器上也解不开。
//
// 存储在 app.getPath('userData') 下的 credentials.json，只包含密文的 base64。
const { app, safeStorage } = require('electron')
const fs = require('node:fs')
const path = require('node:path')
const { writeJsonAtomic } = require('./atomic-file.cjs')

const FILE_NAME = 'credentials.json'

/**
 * 惰性求值：app.getPath('userData') 在 app ready 之前不可靠，
 * 而这些函数都是在 IPC 里被调用的（那时早就 ready 了）。
 */
function filePath() {
  return path.join(app.getPath('userData'), FILE_NAME)
}

/** 空壳：文件不存在 / 内容坏了都返回它（都等价于"没存过"） */
function emptyState() {
  return { credential: null, expiredFlag: false }
}

/**
 * 读落盘内容。
 *
 * <p><b>三种失败必须区别对待</b>（这里写错的代价是"用户莫名其妙被登出"）：
 * <ol>
 *   <li><b>文件不存在</b>（ENOENT）→ 第一次运行，返回空壳是安全的；</li>
 *   <li><b>内容坏了</b>（JSON 解析失败）→ 当没存过，让下次写入重建；</li>
 *   <li><b>暂时读不到</b>（EACCES / EBUSY / EPERM：被杀软或索引占用、权限问题）
 *       → <b>抛出去</b>。因为 {@link patchFile} 是"读-改-写"：拿一个空壳当快照去写，
 *       会把磁盘上<b>真实存在的凭证抹掉</b> —— 用户没登出却被永久登出，而且没有任何日志。
 *       抛出去只是"这一次写失败"，磁盘上的旧值原样保留，下次就能读到。</li>
 * </ol>
 */
function readFile() {
  let raw
  try {
    raw = fs.readFileSync(filePath(), 'utf8')
  } catch (e) {
    if (e?.code === 'ENOENT') {
      return emptyState()
    }
    throw e
  }
  try {
    const parsed = JSON.parse(raw)
    return {
      credential: typeof parsed.credential === 'string' ? parsed.credential : null,
      expiredFlag: parsed.expiredFlag === true,
    }
  } catch {
    // 内容损坏（被手工改过、或写到一半断电）：当没存过，让下次写入重建
    console.warn('[vault] credentials.json 内容损坏，按"没存过"处理')
    return emptyState()
  }
}

/**
 * 所有【写】操作排成一条链（读-改-写必须串行）。
 *
 * <p><b>为什么必须要它</b>：{@link patchFile} 是"读整个文件 → 合并补丁 → 整份写回"。
 * 而登录时 {@code auth.js#saveToken} 会连着发两个写请求：存 token、以及写"过期标记"。
 * 两个读-改-写并发跑的时候，后读的那次可能还在读旧内容，于是它写回时会把另一次刚写进去的
 * 字段覆盖掉。实测就是这么坏的：磁盘上出现过
 * {@code {"credential":null,"expiredFlag":false}} 后面跟着旧内容残尾这种非法 JSON，
 * 于是 {@link readFile} 解析失败 → 按"没存过"处理 → 主进程读不到 token →
 * 界面显示"尚未登录，实时通道未建立"，而且重启后还得重新登录。
 *
 * <p>读操作不进队列：{@link readCredential} 是同步的（WebSocket 握手要立刻拿 token），
 * 而写入是原子的（临时文件 + rename），所以读到的永远是"某一次完整的写入"。
 */
let writeChain = Promise.resolve()

function serializeWrite(task) {
  // 前一个任务失败不能把链断掉：先 catch 掉它的异常再接着排
  const run = writeChain.catch(() => {}).then(task)
  writeChain = run.catch(() => {})
  return run
}

/**
 * 把内存里的状态整体落盘（读-改-写，保证两个字段互不覆盖）。
 *
 * <p>写失败会抛出去，这是刻意的：调用方（IPC）把它变成 rejected promise，
 * 渲染进程那边只 warn 一句，而<b>磁盘上的旧值仍然完好</b>。
 * 反过来"默默写个空壳"才是真正的数据丢失。
 *
 * <p>原子写（临时文件 + rename + Windows 上偶发 EPERM 的重试）见 electron/atomic-file.cjs。
 */
function patchFile(patch) {
  return serializeWrite(async () => {
    await writeJsonAtomic(filePath(), { ...readFile(), ...patch })
  })
}

/** 写：加密后落盘。加密不可用就如实报错，让渲染进程自己决定降级策略 */
async function saveCredential(value) {
  if (!safeStorage.isEncryptionAvailable()) {
    return { ok: false, reason: 'encryption-unavailable' }
  }
  const encrypted = safeStorage.encryptString(value).toString('base64')
  await patchFile({ credential: encrypted })
  return { ok: true }
}

/** 读：解密后返回原文；任何一步失败都返回 null（等价于"没登录"） */
function readCredential() {
  let file
  try {
    file = readFile()
  } catch (e) {
    // ⚠️ 读不到 ≠ 没登录：这里只让"本次读"失败，绝不动磁盘（磁盘上可能好好的）
    console.warn('[vault] 读取凭证文件失败，本次按未登录处理（磁盘内容未改动）:', e?.message)
    return null
  }
  if (!file.credential) return null
  if (!safeStorage.isEncryptionAvailable()) return null

  try {
    return safeStorage.decryptString(Buffer.from(file.credential, 'base64'))
  } catch (e) {
    // 换了机器／换了系统账号／文件被改过，都会解不开。
    // 这种情况直接当没登录处理，比报错让用户卡住更友好。
    console.warn('[vault] 凭证解密失败，按未登录处理:', e?.message)
    return null
  }
}

/**
 * 清掉凭证。
 *
 * <p>和上面的写路径不一样：这里<b>读失败也要照清</b>（用空壳兜底）——
 * 调用方的意图就是"把这个凭证弄掉"，读不出来反而更应该把它删掉。
 *
 * <p>同样进写队列：它和"存 token / 写过期标记"是同一个文件的读-改-写，不能交错。
 */
function clearCredential() {
  return serializeWrite(async () => {
    let file
    try {
      file = readFile()
    } catch {
      file = emptyState()
    }
    await writeJsonAtomic(filePath(), { ...file, credential: null })
    return { ok: true }
  })
}

/** 读"是不是因为过期被登出"的标记；读失败按 false（宁可少提示一次，也别把界面卡住） */
function getExpiredFlag() {
  try {
    return readFile().expiredFlag
  } catch {
    return false
  }
}

async function setExpiredFlag(on) {
  await patchFile({ expiredFlag: on === true })
  return { ok: true }
}

/**
 * 注册所有 IPC 处理器。在 app ready 之后调用一次。
 * 通道名统一带 `credential:` 前缀，和 preload 里暴露的方法一一对应。
 */
function registerCredentialHandlers(ipcMain) {
  ipcMain.handle('credential:save', (event, value) => saveCredential(String(value ?? '')))
  ipcMain.handle('credential:read', () => readCredential())
  ipcMain.handle('credential:clear', () => clearCredential())
  ipcMain.handle('credential:get-expired-flag', () => getExpiredFlag())
  ipcMain.handle('credential:set-expired-flag', (event, on) => setExpiredFlag(on))
  // 渲染进程启动时先问一句能不能加密，好决定是走保险箱还是退化成 localStorage
  ipcMain.handle('credential:is-encryption-available', () => safeStorage.isEncryptionAvailable())
}

// 除了注册 IPC，还把 readCredential 暴露出来：
// 主进程里的 WebSocket 模块要自己读 token（握手要带 Authorization），
// 这样 token 全程不用经过渲染进程 —— 渲染进程连"我拿到过 token"这件事都不需要发生。
module.exports = { registerCredentialHandlers, readCredential, filePath }
