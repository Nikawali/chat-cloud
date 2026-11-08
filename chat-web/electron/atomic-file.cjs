// 原子写文件：先写临时文件，再 rename 覆盖目标。
//
// ── 为什么要单独抽一个模块 ──────────────────────────────────────
// credential-vault.cjs（凭证）和 message-store.cjs（本地消息）要做的是同一件事，
// 而"写临时文件 + rename"里有三个坑，必须一起处理对 —— 抄第二遍一定会漏一个：
//   ① rename 覆盖【已存在】的文件在 Windows 上会偶发报 EPERM：
//      刚写出来的临时文件正被杀软实时扫描 / 被索引服务抓着，通常几十毫秒就放开。
//      实测在这个项目里真的撞到过（本地消息库的第 2 次 append 直接失败，紧接着重试一次就好了）。
//   ② 重试还不行时要有兜底，不能把异常一直往上抛 —— 对上层来说"写不进去"往往比
//      "写得不够原子"严重得多（凭证写不进去 = 用户下次要重新登录；消息写不进去 = ack 发不出去）。
//   ③ 临时文件要么被 rename 掉、要么被删掉，不能留下来越堆越多。
//
// 调用方对"原子性"的依赖程度不一样，取舍写在各自的注释里：
//   · 凭证：读-改-写，所以【读失败绝不能当"没存过"】，否则会把磁盘上真实的凭证抹掉；
//   · 本地消息：只是缓存，允许"重试失败后退化成直接写"，服务端那份才是真相。
const fs = require('node:fs/promises')
const path = require('node:path')

/** rename 最多试几次；每次退避 20/40/60/80/100 毫秒 */
const RENAME_ATTEMPTS = 5

/** 临时文件名的自增序号（同一进程内保证不重名） */
let tmpSeq = 0

/**
 * 临时文件路径：<b>每次写都要唯一</b>。
 *
 * <p>⚠️ 这里原来用的是固定的 {@code `${file}.tmp`}，那是个真实的 bug：
 * 同一个文件如果被并发写两次（凭证就是这样 —— 存 token 和写"过期标记"是两个 IPC），
 * 两次写会共用同一个临时文件：后写的把前一份内容截断掉，接着某个 rename 把这份
 * 半成品搬到目标位置，最终文件就变成"新内容的前半段 + 旧内容的残尾"这种非法 JSON。
 * 实测在 credentials.json 上就发生过（见 credential-vault 的注释），后果是"明明登录了却
 * 显示未登录"。
 *
 * <p>加上 pid + 时间 + 序号之后，并发写各写各的临时文件，最后谁 rename 成功谁生效
 * （rename 本身是原子的，所以目标文件永远是"某一次完整的写入"）。
 */
function tmpPathFor(file) {
  tmpSeq = (tmpSeq + 1) % 1_000_000
  return `${file}.${process.pid}-${Date.now().toString(36)}-${tmpSeq}.tmp`
}

/**
 * 原子写的公共实现：先写临时文件，再 rename 覆盖目标。
 *
 * @param {string} file 目标文件（父目录不存在会自动创建）
 * @param {string|Buffer|Uint8Array} data 要写入的完整内容
 * @param {string|undefined} encoding 文本才传 'utf8'；<b>二进制必须传 undefined</b>
 *        （传了 'utf8' 会把图片字节当文本编码，写出来就是坏的）
 */
async function writeAtomic(file, data, encoding) {
  await fs.mkdir(path.dirname(file), { recursive: true })
  const tmp = tmpPathFor(file)
  await fs.writeFile(tmp, data, encoding)

  let lastError = null
  for (let attempt = 0; attempt < RENAME_ATTEMPTS; attempt++) {
    try {
      await fs.rename(tmp, file)
      return
    } catch (e) {
      lastError = e
      // 等一小会儿再试：这种占用通常只有几十毫秒
      await new Promise((resolve) => setTimeout(resolve, 20 * (attempt + 1)))
    }
  }

  console.warn('[atomic-file] rename 重试多次仍失败，改为直接写入: %s (%s)', file, lastError?.code)
  await fs.writeFile(file, data, encoding)
  // 删临时文件失败不影响正确性（下次写会用自己的临时文件），不能让它把"其实已经写成功"变成抛异常
  await fs.rm(tmp, { force: true }).catch(() => {})
}

/**
 * 把 text 原子地写到 file（覆盖已存在的文件）。
 *
 * @param {string} file 目标文件（父目录不存在会自动创建）
 * @param {string} text 要写入的完整内容
 */
function writeFileAtomic(file, text) {
  return writeAtomic(file, text, 'utf8')
}

/**
 * 把<b>二进制</b>（图片/文件本体）原子地写到 file。
 *
 * <p>和 {@link writeFileAtomic} 的唯一区别是不传编码 —— 传 'utf8' 会把图片字节
 * 按文本编码一遍，写出来的是坏文件（而且要到渲染时才发现是裂图，最难查）。
 *
 * @param {string} file 目标文件
 * @param {Buffer|Uint8Array} bytes 文件内容
 */
function writeBytesAtomic(file, bytes) {
  return writeAtomic(file, bytes, undefined)
}

/**
 * 写 JSON。
 *
 * @param {boolean} [pretty] 缩进两格。只给"人会打开看的小文件"用（比如 index.json）；
 *                          大文件（消息正文）压缩着写，省空间也快。
 */
async function writeJsonAtomic(file, data, pretty = false) {
  await writeFileAtomic(file, JSON.stringify(data, null, pretty ? 2 : 0))
}

module.exports = { writeFileAtomic, writeBytesAtomic, writeJsonAtomic }
