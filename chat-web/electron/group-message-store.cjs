function validAttachment(type,content) {
  if(![2,3].includes(type) || typeof content!=='string') return false
  const ext=type===2?'png|jpg|gif|webp':'pdf|zip|rar|7z|doc|docx|xls|xlsx|ppt|pptx|txt|md|csv|mp3|wav|m4a|mp4|mov'
  return new RegExp('^/api/message/uploads/[0-9a-f]{32}\\.('+ext+')(\\?[A-Za-z0-9=&%._+-]{0,700})?$').test(content)
}
// 群文本发件箱和消息持久化；与单聊共用账号数据库，表和消息 ID 空间独立。
function initGroupSchema(db) {
  db.exec('CREATE TABLE IF NOT EXISTS group_local_progress(group_id INTEGER PRIMARY KEY,member_version INTEGER NOT NULL,visible_from_seq INTEGER NOT NULL,received_seq INTEGER NOT NULL,read_seq INTEGER NOT NULL)')
  db.exec('CREATE TABLE IF NOT EXISTS group_receipt_confirm(group_id INTEGER PRIMARY KEY,member_version INTEGER NOT NULL,received_seq INTEGER NOT NULL,read_seq INTEGER NOT NULL)')
  db.exec("CREATE TABLE IF NOT EXISTS group_receipt_state(group_id INTEGER PRIMARY KEY, data TEXT NOT NULL); CREATE TABLE IF NOT EXISTS group_device_identity(id INTEGER PRIMARY KEY CHECK(id=1),device_id TEXT NOT NULL)")
  db.prepare('INSERT OR IGNORE INTO group_device_identity VALUES(1,?)').run(require('node:crypto').randomUUID())
  db.exec(`
    CREATE TABLE IF NOT EXISTS group_conversations (
      group_id INTEGER PRIMARY KEY, metadata TEXT NOT NULL, opened_at TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS group_messages (
      client_message_id TEXT NOT NULL, group_id INTEGER NOT NULL,
      message_id INTEGER UNIQUE, seq INTEGER, content TEXT NOT NULL,
      sender_user_id INTEGER NOT NULL, sender_name TEXT, avatar_url TEXT,
      created_at TEXT NOT NULL, status TEXT NOT NULL, error TEXT,
      PRIMARY KEY(sender_user_id, client_message_id), UNIQUE(group_id, seq)
    );
    CREATE INDEX IF NOT EXISTS idx_group_messages_group_time ON group_messages(group_id, created_at, client_message_id);
  `)
  // 旧版仅存自己发出的消息，clientMessageId 曾独占主键；现在按发送者隔离幂等键。
  const primary = db.prepare('PRAGMA table_info(group_messages)').all().filter(c => c.pk)
  if (primary.length === 1) {
    db.exec('BEGIN IMMEDIATE')
    try {
      db.exec(`
        ALTER TABLE group_messages RENAME TO group_messages_legacy;
        CREATE TABLE group_messages (
          client_message_id TEXT NOT NULL, group_id INTEGER NOT NULL,
          message_id INTEGER UNIQUE, seq INTEGER, content TEXT NOT NULL,
          sender_user_id INTEGER NOT NULL, sender_name TEXT, avatar_url TEXT,
          created_at TEXT NOT NULL, status TEXT NOT NULL, error TEXT,
          PRIMARY KEY(sender_user_id, client_message_id), UNIQUE(group_id, seq)
        );
        INSERT INTO group_messages SELECT * FROM group_messages_legacy;
        DROP TABLE group_messages_legacy;
        CREATE INDEX idx_group_messages_group_time ON group_messages(group_id, created_at, client_message_id);
      `)
      db.exec('COMMIT')
    } catch (e) {db.exec('ROLLBACK');throw e}
  }
  db.exec('CREATE TABLE IF NOT EXISTS group_uploads(client_message_id TEXT PRIMARY KEY, bytes BLOB NOT NULL)')
  for(const [name,type] of [['message_type','INTEGER NOT NULL DEFAULT 1'],['attachment_json',"TEXT NOT NULL DEFAULT '{}'"]]) {
    if(!db.prepare('PRAGMA table_info(group_messages)').all().some(c=>c.name===name)) db.exec('ALTER TABLE group_messages ADD COLUMN '+name+' '+type)
  }
  db.exec('CREATE INDEX IF NOT EXISTS idx_group_messages_group_seq ON group_messages(group_id, seq)')
  if (!db.prepare('PRAGMA table_info(group_messages)').all().some(c => c.name === 'local_read'))
    db.exec('ALTER TABLE group_messages ADD COLUMN local_read INTEGER NOT NULL DEFAULT 0')
  db.exec('CREATE INDEX IF NOT EXISTS idx_group_unread ON group_messages(group_id,sender_user_id) WHERE local_read=0')
}
function receiptState(db, id) {
  const row=db.prepare('SELECT data FROM group_receipt_state WHERE group_id=?').get(id)
  return row ? JSON.parse(row.data) : null
}

// 调用方持有写事务。旧库首次使用或成员边界变化时建立基线，其后只检查游标之后。
function advanceProgress(db, id, owner, state = receiptState(db,id)) {
  if (!state) return null
  const previous=db.prepare('SELECT * FROM group_local_progress WHERE group_id=?').get(id)
  const same=previous?.member_version===state.memberVersion && previous?.visible_from_seq===state.visibleFromSeq
  let received=same?previous.received_seq:state.visibleFromSeq-1
  let read=same?previous.read_seq:state.visibleFromSeq-1
  for (const row of db.prepare('SELECT seq FROM group_messages WHERE group_id=? AND seq>? ORDER BY seq').iterate(id,received)) {
    if(row.seq!==received+1) break
    received=row.seq
  }
  for (const row of db.prepare('SELECT seq,sender_user_id,local_read FROM group_messages WHERE group_id=? AND seq>? AND seq<=? ORDER BY seq').iterate(id,read,received)) {
    if(row.seq!==read+1 || (row.sender_user_id!==owner && !row.local_read)) break
    read=row.seq
  }
  if(!same || received!==previous.received_seq || read!==previous.read_seq) {
    db.prepare('INSERT INTO group_local_progress VALUES(?,?,?,?,?) ON CONFLICT(group_id) DO UPDATE SET member_version=excluded.member_version,visible_from_seq=excluded.visible_from_seq,received_seq=excluded.received_seq,read_seq=excluded.read_seq')
      .run(id,state.memberVersion,state.visibleFromSeq,received,read)
  }
  return {received_seq:received,read_seq:read}
}

function rowMessage(r, owner) {
  return { key:`${r.sender_user_id}:${r.client_message_id}`, clientMessageId:r.client_message_id, groupId:r.group_id,
    ...JSON.parse(r.attachment_json || '{}'), messageType:r.message_type, messageId:r.message_id, seq:r.seq, content:r.content, senderUserId:r.sender_user_id,
    senderName:r.sender_name, avatarUrl:r.avatar_url, createdAt:r.created_at, status:r.status, error:r.error, mine:r.sender_user_id === owner }
}
function snapshot(db, id, owner, summary = false, options = {}) {
  const row = db.prepare('SELECT * FROM group_conversations WHERE group_id=?').get(id)
  if (!row) return null
  const size = 30
  let messages = [], hasMore = false
  if (summary) messages = db.prepare('SELECT * FROM group_messages WHERE group_id=? ORDER BY (seq IS NULL) DESC, seq DESC, created_at DESC, rowid DESC LIMIT 1').all(id)
  else if (!options.delta) {
    const rows = options.beforeSeq
      ? db.prepare('SELECT * FROM group_messages WHERE group_id=? AND seq<? ORDER BY seq DESC LIMIT ?').all(id,options.beforeSeq,size+1)
      : db.prepare('SELECT * FROM group_messages WHERE group_id=? AND seq IS NOT NULL ORDER BY seq DESC LIMIT ?').all(id,size+1)
    hasMore = rows.length > size
    messages = rows.slice(0,size).reverse()
    if (!options.beforeSeq) messages.push(...db.prepare('SELECT * FROM group_messages WHERE group_id=? AND seq IS NULL ORDER BY created_at,rowid').all(id))
  }
  if (options.delta) messages = options.rows || []
  const unreadCount = db.prepare('SELECT COUNT(*) AS n FROM group_messages WHERE group_id=? AND sender_user_id!=? AND local_read=0').get(id,owner).n
  const receiptRow = db.prepare('SELECT data FROM group_receipt_state WHERE group_id=?').get(id)
  const receiptState = receiptRow ? JSON.parse(receiptRow.data) : null
  return { receiptState, group:JSON.parse(row.metadata), openedAt:row.opened_at, unreadCount, messages:messages.map(r => rowMessage(r,owner)), loaded:!summary && !options.delta, hasMore, delta:!!options.delta }
}
function groupOperation(db, owner, payload, inBatch = false) {
  if (!db || !Number.isSafeInteger(payload?.ownerUserId) || owner !== payload.ownerUserId)
    throw new Error('本地账号已变化，请重新打开群聊')
  const { op } = payload
  if (op === 'list') return { sessions:db.prepare('SELECT group_id FROM group_conversations').all().map(r => snapshot(db,r.group_id,owner,true)) }
  const id = Number(payload.groupId)
  if (!Number.isSafeInteger(id) || id <= 0) throw new Error('群 ID 不合法')
  if (op === 'cursors') {
    const state = receiptState(db,id)
    let progress=db.prepare('SELECT * FROM group_local_progress WHERE group_id=?').get(id)
    if(state && (progress?.member_version!==state.memberVersion || progress?.visible_from_seq!==state.visibleFromSeq)) {
      db.exec('BEGIN IMMEDIATE')
      try {progress=advanceProgress(db,id,owner,state);db.exec('COMMIT')}
      catch(e) {db.exec('ROLLBACK');throw e}
    }
    const receivedSeq=state?progress.received_seq:0, readSeq=state?progress.read_seq:0
    const confirmed=db.prepare('SELECT * FROM group_receipt_confirm WHERE group_id=? AND member_version=?').get(id,state?.memberVersion || 0)
    return { confirmedReceivedSeq:confirmed?.received_seq ?? (state ? state.visibleFromSeq-1 : 0),confirmedReadSeq:confirmed?.read_seq ?? (state ? state.visibleFromSeq-1 : 0), deviceId:db.prepare('SELECT device_id FROM group_device_identity WHERE id=1').get().device_id,
      memberVersion:state?.memberVersion, receivedSeq, readSeq }
  }
  if (op === 'upload-bytes') {
    const row=db.prepare('SELECT u.bytes FROM group_uploads u JOIN group_messages m ON m.client_message_id=u.client_message_id WHERE m.group_id=? AND m.sender_user_id=? AND m.client_message_id=?').get(id,owner,payload.clientMessageId)
    if(!row) throw new Error('待上传文件不存在')
    return {bytes:row.bytes}
  }
  if (op === 'load') return { session:snapshot(db,id,owner,false,payload) }
  if (op === 'receive-batch') {
    if (!Array.isArray(payload.messages) || payload.messages.length>100) throw new Error('群消息批次不合法')
    db.exec('BEGIN IMMEDIATE')
    try {
      const rows=[];let added=0
      for (const message of payload.messages) {
        const result=groupOperation(db,owner,{...payload,op:'receive',message},true)
        rows.push(...result.rows);if(result.inserted) added++
      }
      advanceProgress(db,id,owner)
      db.exec('COMMIT')
      return {session:snapshot(db,id,owner,false,{delta:true,rows}),added}
    } catch(e) {db.exec('ROLLBACK');throw e}
  }
  let inserted = false
  if (!inBatch) db.exec('BEGIN IMMEDIATE')
  try {
    if (op === 'confirm-receipt') {
      const c=payload.cursors, state=receiptState(db,id)
      if (!c || !state || c.memberVersion!==state.memberVersion || ![c.receivedSeq,c.readSeq].every(n=>Number.isSafeInteger(n) && n>=state.visibleFromSeq-1) || c.readSeq>c.receivedSeq) throw new Error('群回执确认不合法')
      db.prepare('INSERT INTO group_receipt_confirm VALUES(?,?,?,?) ON CONFLICT(group_id) DO UPDATE SET received_seq=CASE WHEN member_version=excluded.member_version THEN MAX(received_seq,excluded.received_seq) ELSE excluded.received_seq END,read_seq=CASE WHEN member_version=excluded.member_version THEN MAX(read_seq,excluded.read_seq) ELSE excluded.read_seq END,member_version=excluded.member_version').run(id,c.memberVersion,c.receivedSeq,c.readSeq)
    } else if (op === 'receipt-state') {
      const data = payload.data
      if (!data || data.groupId !== id || !Number.isSafeInteger(data.memberVersion) || data.memberVersion < 1 || !Number.isSafeInteger(data.visibleFromSeq) || data.visibleFromSeq < 1 || !Array.isArray(data.members)) throw new Error('群回执状态不合法')
      const oldRow = db.prepare('SELECT data FROM group_receipt_state WHERE group_id=?').get(id)
      const old = oldRow ? JSON.parse(oldRow.data) : null
      if (!old || old.memberVersion <= data.memberVersion) {
        if (payload.partial && old?.memberVersion===data.memberVersion) data.members=[...old.members.filter(m=>!data.members.some(n=>n.userId===m.userId)),...data.members]
        if (old?.memberVersion === data.memberVersion) data.members = data.members.map(m => {
          const previous = old.members.find(p => p.userId === m.userId)
          if (previous && previous.memberVersion > m.memberVersion) return previous
          if (previous && previous.memberVersion < m.memberVersion) return m
          return previous ? {...m,receivedSeq:Math.max(m.receivedSeq,previous.receivedSeq),readSeq:Math.max(m.readSeq,previous.readSeq)} : m
        })
        db.prepare('INSERT INTO group_receipt_state VALUES(?,?) ON CONFLICT(group_id) DO UPDATE SET data=excluded.data').run(id,JSON.stringify(data))
      }
    } else if (op === 'local-read' && payload.upToSeq != null) {
      if(!Number.isSafeInteger(payload.upToSeq) || payload.upToSeq<0) throw new Error('已读位置不合法')
      db.prepare('UPDATE group_messages SET local_read=1 WHERE group_id=? AND seq<=? AND sender_user_id!=? AND local_read=0').run(id,payload.upToSeq,owner)
    } else if (op === 'local-read') {
      if (!Array.isArray(payload.messageIds) || !payload.messageIds.every(n => Number.isSafeInteger(n) && n > 0))
        throw new Error('本地已读消息不合法')
      const update = db.prepare('UPDATE group_messages SET local_read=1 WHERE group_id=? AND message_id=? AND sender_user_id!=?')
      for (const messageId of payload.messageIds) update.run(id,messageId,owner)
    } else if (op === 'receive') {
      const m = payload.message
      if (!m || m.groupId !== id || ![m.messageId,m.seq,m.senderUserId].every(n => Number.isSafeInteger(n) && n > 0)
          || typeof m.clientMessageId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(m.clientMessageId)
          || ![1,2,3].includes(m.messageType) || typeof m.content !== 'string' || !m.content.trim() || m.content.length > 2000
          || typeof m.createdAt !== 'string' || !Number.isFinite(Date.parse(m.createdAt)))
        throw new Error('群推送消息不合法')
      if(m.messageType!==1 && !validAttachment(m.messageType,m.content)) throw new Error('群附件地址不合法')
      const previous = db.prepare('SELECT * FROM group_messages WHERE sender_user_id=? AND client_message_id=?')
        .get(m.senderUserId,m.clientMessageId)
      if (previous && (previous.group_id !== id || previous.content !== m.content || previous.message_type !== m.messageType
          || (previous.message_id != null && (previous.message_id !== m.messageId || previous.seq !== m.seq))))
        throw new Error('群推送消息与已有记录冲突')
      // 正文先保存；首次收到未打开过的群时，也必须有会话入口，不依赖资料接口成功。
      db.prepare('INSERT OR IGNORE INTO group_conversations(group_id,metadata,opened_at) VALUES(?,?,?)')
        .run(id,JSON.stringify({groupId:id,groupName:'群聊 ' + id,metadataPending:true}),new Date().toISOString())
      if (previous) {
        db.prepare("UPDATE group_messages SET message_id=?,seq=?,created_at=?,status=?,error=NULL WHERE sender_user_id=? AND client_message_id=?")
          .run(m.messageId,m.seq,m.createdAt,m.senderUserId === owner ? 'sent' : 'received',m.senderUserId,m.clientMessageId)
      } else {
        inserted = true
        db.prepare(`INSERT INTO group_messages
          (client_message_id,group_id,message_id,seq,content,sender_user_id,sender_name,avatar_url,created_at,status,message_type)
          VALUES(?,?,?,?,?,?,?,?,?,?,?)`).run(m.clientMessageId,id,m.messageId,m.seq,m.content,m.senderUserId,
            typeof m.senderName === 'string' ? m.senderName : null,
            typeof m.avatarUrl === 'string' ? m.avatarUrl : null,m.createdAt,m.senderUserId === owner ? 'sent' : 'received',m.messageType)
      }
    } else if (op === 'open') {
      if (Number(payload.group?.groupId) !== id) throw new Error('群资料不匹配')
      db.prepare(`INSERT INTO group_conversations(group_id,metadata,opened_at) VALUES(?,?,?)
        ON CONFLICT(group_id) DO UPDATE SET metadata=excluded.metadata`).run(id,JSON.stringify(payload.group),new Date().toISOString())
    } else {
      if (!snapshot(db,id,owner,true)) throw new Error('群会话不存在')
      const key = payload.clientMessageId
      if (typeof key !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(key)) throw new Error('消息 ID 不合法')
      const existing = db.prepare('SELECT * FROM group_messages WHERE client_message_id=? AND sender_user_id=?').get(key,owner)
      if (existing && existing.group_id !== id) throw new Error('消息归属不匹配')
      if (op === 'put-upload') {
        const m=payload.message,bytes=payload.bytes
        if(!m || m.senderUserId!==owner || ![2,3].includes(m.messageType) || typeof m.fileName!=='string' || !m.fileName || m.fileName.length>255 || !(bytes instanceof Uint8Array) || !bytes.byteLength || bytes.byteLength>50*1024*1024) throw new Error('附件文件不合法')
        if(existing) throw new Error('附件已存在，请使用原消息重试')
        const metadata=JSON.stringify({fileName:m.fileName,fileSize:bytes.byteLength,mimeType:m.mimeType || '',uploadPending:true})
        db.prepare("INSERT INTO group_messages(client_message_id,group_id,content,sender_user_id,sender_name,avatar_url,created_at,status,message_type,attachment_json) VALUES(?,?,?,?,?,?,?,'sending',?,?)").run(key,id,m.fileName,owner,m.senderName || '我',m.avatarUrl || null,new Date().toISOString(),m.messageType,metadata)
        db.prepare('INSERT INTO group_uploads VALUES(?,?)').run(key,bytes)
      } else if (op === 'upload-ready') {
        const uploaded=payload.uploaded
        if(!existing || !JSON.parse(existing.attachment_json).uploadPending || !validAttachment(uploaded?.messageType,uploaded?.url)) throw new Error('上传结果不合法')
        const metadata={...JSON.parse(existing.attachment_json),uploadPending:false}
        db.prepare('UPDATE group_messages SET content=?,message_type=?,attachment_json=? WHERE sender_user_id=? AND client_message_id=?').run(uploaded.url,uploaded.messageType,JSON.stringify(metadata),owner,key)
        db.prepare('DELETE FROM group_uploads WHERE client_message_id=?').run(key)
      } else if (op === 'put') {
        const m = payload.message
        if (!m || typeof m.content !== 'string' || !m.content.trim() || m.content.length > 2000 || m.senderUserId !== owner)
          throw new Error('消息内容或发送身份不合法')
        if (existing && (existing.content !== m.content || existing.message_type !== (m.messageType || 1))) throw new Error('重试不能改变消息内容')
        if(![1,2,3].includes(m.messageType || 1) || ((m.messageType || 1)!==1 && !validAttachment(m.messageType,m.content))) throw new Error('群消息类型或附件地址不合法')
        if (!existing) db.prepare(`INSERT INTO group_messages
          (client_message_id,group_id,content,sender_user_id,sender_name,avatar_url,created_at,status,message_type)
          VALUES(?,?,?,?,?,?,?,'sending',?)`).run(key,id,m.content,owner,m.senderName || '我',m.avatarUrl || null,new Date().toISOString(),m.messageType || 1)
      } else if (op === 'accept') {
        const d = payload.data
        if (!existing || d?.groupId !== id || !Number.isSafeInteger(d.messageId) || d.messageId <= 0
            || !Number.isSafeInteger(d.seq) || d.seq <= 0 || typeof d.createdAt !== 'string' || !Number.isFinite(Date.parse(d.createdAt)))
          throw new Error('群消息受理结果不合法')
        if (existing.status === 'sent' && (existing.message_id !== d.messageId || existing.seq !== d.seq))
          throw new Error('群消息受理结果冲突')
        db.prepare("UPDATE group_messages SET message_id=?,seq=?,created_at=?,status='sent',error=NULL WHERE client_message_id=? AND sender_user_id=?")
          .run(d.messageId,d.seq,d.createdAt,key,owner)
      } else if (op === 'status') {
        if (!existing || !['sending','failed'].includes(payload.status)) throw new Error('消息状态不合法')
        db.prepare("UPDATE group_messages SET status=?,error=? WHERE client_message_id=? AND sender_user_id=? AND status!='sent'")
          .run(payload.status,String(payload.error || '').slice(0,300),key,owner)
      } else throw new Error('未知群消息操作')
    }
    if (!inBatch) {
      if(['receive','accept','local-read','receipt-state'].includes(op)) advanceProgress(db,id,owner)
      db.exec('COMMIT')
    }
  } catch (e) { if (!inBatch) db.exec('ROLLBACK'); throw e }
  const key=op==='receive'?payload.message.clientMessageId:payload.clientMessageId
  const sender=op==='receive'?payload.message.senderUserId:owner
  const rows=key?db.prepare('SELECT * FROM group_messages WHERE group_id=? AND sender_user_id=? AND client_message_id=?').all(id,sender,key):[]
  if(inBatch) return {rows,inserted}
  return { session:snapshot(db,id,owner,false,{delta:true,rows}), inserted }
}
module.exports = { initGroupSchema, groupOperation }
