// Electron 主进程本地消息存储。
//
// 对外 IPC 接口保持不变，只把原来的 JSON 文件读写替换为 SQLite：
//   useAccount / load / append / receipt / read / list / clear / info
// 附件本体仍按原约定存放在 messages/u<userId>/media/ 下；这里只替换消息和会话摘要的存储方式。

const { app } = require('electron')
const { DatabaseSync } = require('node:sqlite')
const fs = require('node:fs/promises')
const path = require('node:path')
const { writeBytesAtomic } = require('./atomic-file.cjs')
const { initGroupSchema, groupOperation } = require('./group-message-store.cjs')

const DIR_NAME = 'messages'
const DB_FILE = 'messages.sqlite'
const PREVIEW_MAX_LENGTH = 30
const MEDIA_DIR = 'media'
const MEDIA_NAME = new RegExp(
  '^[0-9a-f]{32}\\.(png|jpg|gif|webp|pdf|zip|rar|7z|doc|docx|xls|xlsx|ppt|pptx|txt|md|csv|mp3|wav|m4a|mp4|mov)$',
)

let currentOwner = null
let db = null
let chain = Promise.resolve()

function serialize(task) {
  const run = chain.catch(() => {}).then(task)
  chain = run.catch(() => {})
  return run
}

function storeDir() { return path.join(app.getPath('userData'), DIR_NAME) }
function accountDir() { return currentOwner === null ? null : path.join(storeDir(), `u${currentOwner}`) }
function dbPath() { return path.join(accountDir(), DB_FILE) }
function mediaDir() { return accountDir() === null ? null : path.join(accountDir(), MEDIA_DIR) }

function safePeerId(value) {
  const n = Number(value)
  return Number.isInteger(n) && n > 0 ? n : null
}

function positiveIntOrNull(value) {
  const n = Number(value)
  return Number.isInteger(n) && n > 0 ? n : null
}

function accountReady() { return currentOwner !== null && db !== null }

function emptySnapshot(peerUserId) {
  return {
    ok: true,
    peerUserId,
    conversationId: null,
    messages: [],
    // 没有本地库/没这个会话时，"还有更早的消息"这种说法没有意义，一律 false
    hasMore: false,
    deliveredUpTo: 0,
    readUpTo: 0,
    lastMessageId: 0,
  }
}

function normalizeMessage(input) {
  const messageId = positiveIntOrNull(input?.messageId)
  if (messageId === null) return null
  return {
    messageId,
    clientMessageId: typeof input.clientMessageId === 'string' ? input.clientMessageId : null,
    conversationId: positiveIntOrNull(input.conversationId),
    senderUserId: positiveIntOrNull(input.senderUserId),
    content: typeof input.content === 'string' ? input.content : '',
    messageType: Number(input.messageType) || 1,
    createdAt: typeof input.createdAt === 'string' ? input.createdAt : null,
  }
}

function previewOf(message) {
  if (!message || typeof message.content !== 'string') return null
  const flat = message.content.replace(/\s+/g, ' ').trim()
  return flat.length > PREVIEW_MAX_LENGTH ? `${flat.slice(0, PREVIEW_MAX_LENGTH)}…` : flat
}

function initSchema() {
  db.exec(`
    PRAGMA foreign_keys = ON;
    PRAGMA journal_mode = WAL;
    PRAGMA synchronous = NORMAL;
    CREATE TABLE IF NOT EXISTS meta (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS messages (
      message_id INTEGER PRIMARY KEY,
      peer_user_id INTEGER NOT NULL,
      conversation_id INTEGER,
      sender_user_id INTEGER,
      client_message_id TEXT,
      content TEXT NOT NULL,
      message_type INTEGER NOT NULL DEFAULT 1,
      created_at TEXT,
      UNIQUE(sender_user_id, client_message_id)
    );
    CREATE INDEX IF NOT EXISTS idx_messages_peer_id ON messages(peer_user_id, message_id);
    CREATE INDEX IF NOT EXISTS idx_messages_peer_client ON messages(peer_user_id, client_message_id);
    CREATE INDEX IF NOT EXISTS idx_messages_unread ON messages(peer_user_id, sender_user_id, message_id);
    CREATE TABLE IF NOT EXISTS conversations (
      peer_user_id INTEGER PRIMARY KEY,
      conversation_id INTEGER,
      last_message_id INTEGER,
      last_message_at TEXT,
      last_message_preview TEXT,
      last_message_type INTEGER,
      last_message_sender_user_id INTEGER,
      my_read_up_to INTEGER NOT NULL DEFAULT 0,
      unread_count INTEGER NOT NULL DEFAULT 0,
      delivered_up_to INTEGER NOT NULL DEFAULT 0,
      read_up_to INTEGER NOT NULL DEFAULT 0,
      updated_at TEXT
    );
  `)
  db.prepare('INSERT OR IGNORE INTO meta(key, value) VALUES(?, ?)').run('ownerUserId', String(currentOwner))
  initGroupSchema(db)
}

function rowMessage(row) {
  return {
    messageId: Number(row.message_id),
    conversationId: row.conversation_id == null ? null : Number(row.conversation_id),
    senderUserId: row.sender_user_id == null ? null : Number(row.sender_user_id),
    clientMessageId: row.client_message_id ?? null,
    content: row.content ?? '',
    messageType: Number(row.message_type) || 1,
    createdAt: row.created_at ?? null,
  }
}

function rowConversation(row) {
  if (!row) return null
  return {
    peerUserId: Number(row.peer_user_id),
    conversationId: row.conversation_id == null ? null : Number(row.conversation_id),
    lastMessageId: row.last_message_id == null ? null : Number(row.last_message_id),
    lastMessageAt: row.last_message_at ?? null,
    lastMessagePreview: row.last_message_preview ?? null,
    lastMessageType: row.last_message_type == null ? null : Number(row.last_message_type),
    lastMessageSenderUserId: row.last_message_sender_user_id == null ? null : Number(row.last_message_sender_user_id),
    myReadUpTo: Number(row.my_read_up_to) || 0,
    unreadCount: Number(row.unread_count) || 0,
    deliveredUpTo: Number(row.delivered_up_to) || 0,
    readUpTo: Number(row.read_up_to) || 0,
    updatedAt: row.updated_at ?? null,
  }
}

function messagesFor(peerId) {
  return db.prepare(`SELECT * FROM messages WHERE peer_user_id=? ORDER BY message_id`).all(peerId).map(rowMessage)
}

/**
 * 按"更早"方向取一页消息（往上翻页用）。
 *
 * <p>为什么按 message_id 而不是 created_at 排：message_id 是服务端自增主键，天然单调，
 * 而且就是本地表的索引（idx_messages_peer_id）；created_at 是字符串，排序还要再比较文本。
 *
 * @param {number} peerId 对方 userId
 * @param {number|null} beforeId 只取比它更早的；null = 从最新的一条开始
 * @param {number|null} size 最多几条；null = 全部（老行为，见 load 的说明）
 * @returns {{messages: Array, hasMore: boolean}} messages 已按"从旧到新"排好
 */
function messagesPage(peerId, beforeId, size) {
  if (size === null) {
    return { messages: messagesFor(peerId), hasMore: false }
  }
  // 多取一条来判断"还有没有更早的"：比再跑一次 COUNT(*) 便宜
  const limit = size + 1
  const rows = beforeId === null
    ? db.prepare(`SELECT * FROM messages WHERE peer_user_id=? ORDER BY message_id DESC LIMIT ?`)
      .all(peerId, limit)
    : db.prepare(`SELECT * FROM messages WHERE peer_user_id=? AND message_id<? ORDER BY message_id DESC LIMIT ?`)
      .all(peerId, beforeId, limit)
  const hasMore = rows.length > size
  // 倒序取回来是为了"从最新的往回数"，交给界面之前再正过来
  return { messages: rows.slice(0, size).map(rowMessage).reverse(), hasMore }
}

/** 这个会话的 conversationId（消息行里带着）。找不到就 null */
function conversationIdOf(peerId, summary) {
  if (summary?.conversationId != null) {
    return summary.conversationId
  }
  const row = db.prepare(
    'SELECT conversation_id FROM messages WHERE peer_user_id=? AND conversation_id IS NOT NULL LIMIT 1',
  ).get(peerId)
  return row?.conversation_id == null ? null : Number(row.conversation_id)
}

function readConversation(peerId) {
  return rowConversation(db.prepare('SELECT * FROM conversations WHERE peer_user_id=?').get(peerId))
}

function unreadCount(messages, peerId, readCursor) {
  return messages.filter((m) => m.senderUserId === peerId && m.messageId > readCursor).length
}

function rebuildConversation(peerId, previous = {}) {
  const lastRow = db.prepare('SELECT * FROM messages WHERE peer_user_id=? ORDER BY message_id DESC LIMIT 1').get(peerId)
  const last = lastRow ? rowMessage(lastRow) : null
  const readCursor = previous.myReadUpTo == null
    ? (positiveIntOrNull(previous.lastMessageId) || 0)
    : (positiveIntOrNull(previous.myReadUpTo) || 0)
  const now = new Date().toISOString()
  const summary = {
    peerUserId: peerId,
    conversationId: last?.conversationId ?? previous.conversationId ?? null,
    lastMessageId: last?.messageId ?? previous.lastMessageId ?? null,
    lastMessageAt: last?.createdAt ?? previous.lastMessageAt ?? null,
    lastMessagePreview: previewOf(last),
    lastMessageType: positiveIntOrNull(last?.messageType),
    lastMessageSenderUserId: positiveIntOrNull(last?.senderUserId),
    myReadUpTo: readCursor,
    unreadCount: db.prepare('SELECT COUNT(*) AS n FROM messages WHERE peer_user_id=? AND sender_user_id=? AND message_id>?').get(peerId,peerId,readCursor).n,
    deliveredUpTo: Number(previous.deliveredUpTo) || 0,
    readUpTo: Number(previous.readUpTo) || 0,
    updatedAt: now,
  }
  db.prepare(`
    INSERT INTO conversations
      (peer_user_id, conversation_id, last_message_id, last_message_at, last_message_preview,
       last_message_type, last_message_sender_user_id, my_read_up_to, unread_count,
       delivered_up_to, read_up_to, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(peer_user_id) DO UPDATE SET
      conversation_id=excluded.conversation_id, last_message_id=excluded.last_message_id,
      last_message_at=excluded.last_message_at, last_message_preview=excluded.last_message_preview,
      last_message_type=excluded.last_message_type, last_message_sender_user_id=excluded.last_message_sender_user_id,
      my_read_up_to=excluded.my_read_up_to, unread_count=excluded.unread_count,
      updated_at=excluded.updated_at
  `).run(
    summary.peerUserId, summary.conversationId, summary.lastMessageId, summary.lastMessageAt,
    summary.lastMessagePreview, summary.lastMessageType, summary.lastMessageSenderUserId,
    summary.myReadUpTo, summary.unreadCount, summary.deliveredUpTo, summary.readUpTo, summary.updatedAt,
  )
  return summary
}

async function readJson(file, fallback) {
  try {
    return JSON.parse(await fs.readFile(file, 'utf8'))
  } catch (e) {
    if (e?.code === 'ENOENT') return fallback
    console.warn('[message-store] 旧 JSON 读取失败，跳过: %s (%s)', file, e?.message)
    return fallback
  }
}

async function importLegacyJson() {
  const dir = accountDir()
  const marker = db.prepare('SELECT value FROM meta WHERE key=?').get('legacyImported')
  if (marker?.value === '1') return false

  // 兼容升级前的两种布局：messages/u<user>/ 和旧版平铺 messages/。
  let names = []
  try { names = await fs.readdir(dir) } catch { names = [] }
  const index = await readJson(path.join(dir, 'index.json'), null)
  const summaries = index?.conversations || {}
  const files = names.filter((name) => /^\d+\.json$/.test(name))

  for (const name of files) {
    const peerId = Number(name.slice(0, -5))
    const data = await readJson(path.join(dir, name), null)
    const list = Array.isArray(data?.messages) ? data.messages.map(normalizeMessage).filter(Boolean) : []
    for (const message of list) {
      db.prepare(`INSERT OR IGNORE INTO messages
        (message_id, peer_user_id, conversation_id, sender_user_id, client_message_id, content, message_type, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)`).run(
        message.messageId, peerId, message.conversationId, message.senderUserId,
        message.clientMessageId, message.content, message.messageType, message.createdAt,
      )
    }
    const previous = summaries[String(peerId)] || {}
    if (list.length > 0 || previous) rebuildConversation(peerId, previous)
  }

  db.prepare('INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)').run('legacyImported', '1')
  if (files.length > 0 || index) {
    console.log('[message-store] 已将旧 JSON 消息导入 SQLite: %s', dbPath())
    return true
  }
  return false
}

async function migrateFlatLegacyLayout() {
  const root = storeDir()
  let names = []
  try { names = await fs.readdir(root) } catch { return false }
  const legacy = names.filter((name) => name === 'index.json' || /^\d+\.json$/.test(name))
  if (legacy.length === 0) return false
  await fs.mkdir(accountDir(), { recursive: true })
  for (const name of legacy) {
    const from = path.join(root, name)
    const to = path.join(accountDir(), name)
    try {
      await fs.access(to)
      await fs.rename(from, `${from}.stale`)
    } catch {
      try { await fs.rename(from, to) } catch (e) {
        console.warn('[message-store] 平铺 JSON 迁移失败: %s (%s)', name, e?.code)
      }
    }
  }
  return true
}

function useAccount(myUserId) {
  const owner = positiveIntOrNull(myUserId)
  if (owner === null) {
    currentOwner = null
    if (db) { db.close(); db = null }
    return Promise.resolve({ ok: true, applied: false, reason: 'no-account' })
  }
  return serialize(async () => {
    const switched = currentOwner !== owner
    if (db) { db.close(); db = null }
    currentOwner = owner
    await fs.mkdir(accountDir(), { recursive: true })
    await migrateFlatLegacyLayout()
    db = new DatabaseSync(dbPath())
    initSchema()
    await importLegacyJson()
    return { ok: true, applied: switched, reason: switched ? 'account-switched' : 'same-account' }
  })
}

/**
 * 读某个会话的本地快照（水位 + 一页消息）。
 *
 * @param {number} peerUserId 对方 userId
 * @param {{size?: number, beforeMessageId?: number}} [options]
 *        size：这次最多要几条。不传 = 全部（保持老行为，调用方都是"要全量"的地方）；
 *        beforeMessageId：只取比它更早的（往上翻页）。
 * @returns {Promise<object>} { ok, conversationId, messages, hasMore, deliveredUpTo, readUpTo, lastMessageId }
 *        hasMore = 还有没有更早的消息（只在传了 size 时有意义）
 */
function load(peerUserId, options) {
  const peerId = safePeerId(peerUserId)
  if (peerId === null) return Promise.resolve({ ok: false, reason: 'bad-peer-id' })
  if (!accountReady()) return Promise.resolve(emptySnapshot(peerId))

  const size = positiveIntOrNull(options?.size)
  const beforeId = positiveIntOrNull(options?.beforeMessageId)

  return serialize(async () => {
    const summary = readConversation(peerId)
    const page = Array.isArray(options?.clientMessageIds)
      ? {messages:options.clientMessageIds.flatMap(id=>db.prepare('SELECT * FROM messages WHERE peer_user_id=? AND client_message_id=?').all(peerId,id).map(rowMessage)),hasMore:false}
      : messagesPage(peerId, beforeId, size)
    return {
      ok: true,
      peerUserId: peerId,
      conversationId: conversationIdOf(peerId, summary),
      messages: page.messages,
      hasMore: page.hasMore,
      deliveredUpTo: summary?.deliveredUpTo || 0,
      readUpTo: summary?.readUpTo || 0,
      lastMessageId: summary?.lastMessageId || 0,
    }
  })
}

function append(payload) {
  const peerId = safePeerId(payload?.peerUserId)
  if (peerId === null) return Promise.resolve({ ok: false, reason: 'bad-peer-id' })
  if (!accountReady()) return Promise.resolve({ ok: false, reason: 'no-account' })
  const incoming = (Array.isArray(payload?.messages) ? payload.messages : []).map(normalizeMessage).filter(Boolean)
  if (incoming.length === 0) return Promise.resolve({ ok: true, added: 0 })
  return serialize(async () => {
    const previous = readConversation(peerId) || {}
    db.exec('BEGIN')
    try {
      const insert = db.prepare(`INSERT OR IGNORE INTO messages
        (message_id, peer_user_id, conversation_id, sender_user_id, client_message_id, content, message_type, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)`)
      let added = 0
      for (const m of incoming) {
        const result = insert.run(m.messageId, peerId, m.conversationId, m.senderUserId, m.clientMessageId, m.content, m.messageType, m.createdAt)
        added += Number(result.changes || 0)
      }
      rebuildConversation(peerId, previous)
      db.exec('COMMIT')
      return { ok: true, added }
    } catch (e) {
      db.exec('ROLLBACK')
      throw e
    }
  })
}

function raiseReceipt(payload) {
  const conversationId = positiveIntOrNull(payload?.conversationId)
  const upToMessageId = positiveIntOrNull(payload?.upToMessageId)
  const level = payload?.level === 'read' ? 'read_up_to' : payload?.level === 'delivered' ? 'delivered_up_to' : null
  if (conversationId === null || upToMessageId === null || level === null) return Promise.resolve({ ok: false, reason: 'bad-payload' })
  if (!accountReady()) return Promise.resolve({ ok: false, reason: 'no-account' })
  return serialize(async () => {
    const row = db.prepare('SELECT * FROM conversations WHERE conversation_id=?').get(conversationId)
    if (!row) return { ok: true, applied: false, reason: 'unknown-conversation' }
    const current = Number(row[level]) || 0
    if (upToMessageId <= current) return { ok: true, applied: false, reason: 'not-newer' }
    db.prepare(`UPDATE conversations SET ${level}=?, updated_at=? WHERE peer_user_id=?`).run(upToMessageId, new Date().toISOString(), row.peer_user_id)
    return { ok: true, applied: true }
  })
}

function markRead(payload) {
  const peerId = safePeerId(payload?.peerUserId)
  const upTo = positiveIntOrNull(payload?.upToMessageId)
  if (peerId === null || upTo === null) return Promise.resolve({ ok: false, reason: 'bad-payload' })
  if (!accountReady()) return Promise.resolve({ ok: false, reason: 'no-account' })
  return serialize(async () => {
    const previous = readConversation(peerId)
    if (!previous) return { ok: true, applied: false, reason: 'unknown-conversation' }
    const cursor = Math.max(previous.myReadUpTo || 0, upTo)
    const count = db.prepare('SELECT COUNT(*) AS n FROM messages WHERE peer_user_id=? AND sender_user_id=? AND message_id>?').get(peerId,peerId,cursor).n
    db.prepare('UPDATE conversations SET my_read_up_to=?, unread_count=?, updated_at=? WHERE peer_user_id=?').run(cursor, count, new Date().toISOString(), peerId)
    return { ok: true, applied: true, unreadCount: count }
  })
}

function list() {
  return serialize(async () => {
    if (!accountReady()) return { ok: true, conversations: [] }
    const conversations = db.prepare('SELECT * FROM conversations ORDER BY last_message_at DESC').all().map(rowConversation)
    return { ok: true, conversations }
  })
}

function clear(payload) {
  if (!accountReady()) return Promise.resolve({ ok: false, reason: 'no-account' })
  const raw = payload?.peerUserId
  return serialize(async () => {
    if (raw === undefined || raw === null) {
      db.close(); db = null
      await fs.rm(accountDir(), { recursive: true, force: true })
      await fs.mkdir(accountDir(), { recursive: true })
      db = new DatabaseSync(dbPath()); initSchema()
      return { ok: true, cleared: 'all' }
    }
    const peerId = safePeerId(raw)
    if (peerId === null) return { ok: false, reason: 'bad-peer-id' }
    db.exec('BEGIN')
    try {
      db.prepare('DELETE FROM messages WHERE peer_user_id=?').run(peerId)
      db.prepare('DELETE FROM conversations WHERE peer_user_id=?').run(peerId)
      db.exec('COMMIT')
    } catch (e) { db.exec('ROLLBACK'); throw e }
    return { ok: true, cleared: peerId }
  })
}

function resolveLastAccount() {
  return serialize(async () => {
    let names
    try { names = await fs.readdir(storeDir()) } catch { return { ok: true, userId: null, reason: 'no-store-dir' } }
    const dirs = names.filter((name) => /^u\d+$/.test(name))
    let best = null
    for (const name of dirs) {
      try {
        const stat = await fs.stat(path.join(storeDir(), name, DB_FILE))
        if (!best || stat.mtimeMs > best.mtimeMs) best = { name, mtimeMs: stat.mtimeMs }
      } catch {}
    }
    const userId = best ? Number(best.name.slice(1)) : null
    return { ok: true, userId: Number.isInteger(userId) && userId > 0 ? userId : null, reason: 'from-disk' }
  })
}

function safeMediaName(name) { return typeof name === 'string' && MEDIA_NAME.test(name) ? name : null }

function saveMedia(payload) {
  const name = safeMediaName(payload?.name)
  if (!name) return Promise.resolve({ ok: false, reason: 'bad-name' })
  if (!accountReady()) return Promise.resolve({ ok: false, reason: 'no-account' })
  const bytes = payload?.bytes
  if (!bytes || typeof bytes.byteLength !== 'number' || bytes.byteLength === 0) return Promise.resolve({ ok: false, reason: 'bad-bytes' })
  return serialize(async () => {
    const dir = mediaDir(); await fs.mkdir(dir, { recursive: true })
    await writeBytesAtomic(path.join(dir, name), Buffer.from(bytes))
    return { ok: true, name, size: bytes.byteLength }
  })
}

function readMedia(payload) {
  const name = safeMediaName(payload?.name)
  if (!name) return Promise.resolve({ ok: false, reason: 'bad-name' })
  if (!accountReady()) return Promise.resolve({ ok: false, reason: 'no-account' })
  return serialize(async () => {
    try { return { ok: true, name, bytes: new Uint8Array(await fs.readFile(path.join(mediaDir(), name))) } } catch (e) {
      if (e?.code === 'ENOENT') return { ok: true, name, bytes: null }
      throw e
    }
  })
}

function info() {
  return serialize(async () => {
    const rootDir = storeDir()
    if (!accountReady()) return { rootDir, accountDir: null, ownerUserId: null, accountReady: false, conversations: 0, files: [] }
    const conversations = Number(db.prepare('SELECT COUNT(*) AS count FROM conversations').get().count)
    return { rootDir, accountDir: accountDir(), ownerUserId: currentOwner, accountReady: true, conversations, files: [DB_FILE] }
  })
}

function registerMessageHandlers(ipcMain) {
  ipcMain.handle('messages:group', (_event, payload) =>
    serialize(() => ({ ok:true, ...groupOperation(db, currentOwner, payload) })))
  ipcMain.handle('messages:use-account', (event, id) => useAccount(id))
  ipcMain.handle('messages:load', (event, payload) => {
    // 兼容两种调用：messages:load(peerId) 和 messages:load({ peerUserId, size, beforeMessageId })
    if (payload && typeof payload === 'object') {
      return load(payload.peerUserId, payload)
    }
    return load(payload)
  })
  ipcMain.handle('messages:append', (event, payload) => append(payload))
  ipcMain.handle('messages:receipt', (event, payload) => raiseReceipt(payload))
  ipcMain.handle('messages:read', (event, payload) => markRead(payload))
  ipcMain.handle('messages:list', () => list())
  ipcMain.handle('messages:clear', (event, payload) => clear(payload))
  ipcMain.handle('messages:info', () => info())
  ipcMain.handle('messages:last-account', () => resolveLastAccount())
  ipcMain.handle('messages:save-media', (event, payload) => saveMedia(payload))
  ipcMain.handle('messages:read-media', (event, payload) => readMedia(payload))
}

module.exports = {
  registerMessageHandlers, useAccount, load, append, raiseReceipt, markRead, list, clear, info,
  resolveLastAccount, saveMedia, readMedia, storeDir,
}
