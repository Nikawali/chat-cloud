# 聊天消息模块文档

> 适用范围：当前项目的单聊消息功能。消息业务位于 `chat-cloud/chat-message`，WS 接入及发送位于 `chat-cloud/chat-connection`，公共帧约定位于 `chat-common`，客户端位于 `chat-web`。
>
> 对外 HTTP/WS 地址与帧格式保持兼容。内部调用及启动说明见 `chat-cloud/chat-connection/README.md`。

> `conversation` / `conversation_member` 现在只负责单聊，不保留群类型、群名称、群角色、群昵称或退群字段。升级现有数据库前请按 `chat-cloud/sql/single-chat-cleanup.md` 执行迁移；群聊使用独立表。

## 1. 先看结论

### 1.1 消息通道总表

| 场景 | 通道 | 接口/帧 | 返回 |
|---|---|---|---|
| 发送消息（当前客户端） | WebSocket | `type=send-message` | `type=send-result`，用 `clientMessageId` 关联 |
| 发送消息（兼容接口） | HTTP | `POST /api/message/send` | `SendMessageVO`，当前客户端不调用 |
| 接收新消息 | WebSocket | 服务端发送 `type=message` | 消息内容 |
| 送达 ACK | WebSocket 优先 | 客户端发送 `type=ack` | 无成功响应帧 |
| 送达 ACK 兜底 | HTTP | `POST /api/message/delivered` | `data=null` |
| 上报已读 | WebSocket 优先 | 客户端发送 `type=read-report` | 无成功响应帧 |
| 上报已读兜底 | HTTP | `POST /api/message/read` | `data=null` |
| 补拉未送达消息 | HTTP | `GET /api/message/undelivered` | `MessageVO[]` |
| 拉取历史消息 | HTTP | `GET /api/message/history` | `MessageVO[]` |
| 查询会话摘要 | HTTP | `GET /api/message/conversations` | `ConversationVO[]` |
| 上传图片/文件 | HTTP | `POST /api/message/upload` | `AttachmentVO` |

### 1.2 消息状态

| 状态 | 含义 | 触发方式 |
|---|---|---|
| 发送中 | 前端已经显示，服务端尚未返回结果 | 前端乐观插入 |
| 已发送 | 服务端已创建或确认已有消息 | `send-result.code=200` |
| 已送达 | 接收方客户端已收到并保存 | 接收方回 `ack` |
| 已读 | 接收方已上报读到该消息 | `read-report` 或 `/read` |

`ACK` 只表示“客户端已安全收到并保存”，不表示用户已经阅读。

## 2. 全链路流程

### 2.1 发送消息

```text
点击发送 → 生成 clientMessageId → 本地显示“发送中”
       → WS send-message → connection → 内部 HTTP /internal/message/send → message 原发送业务
       → message 返回受理结果 → connection 通过原 WS 返回 send-result
       → 成功：用 messageId 替换本地临时消息
       → 失败：保留原 clientMessageId，重试时复用
       → 服务端通过 WS 推送给接收方
```

### 2.1.1 WS 发送请求与受理响应

请求（身份从 WS 会话取，不能传 `userId` 冒充别人）：

```json
{"type":"send-message","toUserId":2,"content":"你好","clientMessageId":"客户端生成的UUID","messageType":1}
```

成功响应（仅代表受理，不代表接收方已送达）：

```json
{"type":"send-result","clientMessageId":"客户端生成的UUID","code":200,"message":"success","data":{"messageId":101,"conversationId":12,"createdAt":"2026-10-23T10:00:00","duplicated":false}}
```

| 字段 | 说明 |
|---|---|
| `toUserId` | 必填，接收方用户 ID，正整数 |
| `content` | 必填，最多 2000 字符；附件必须为上传接口签发的 URL |
| `clientMessageId` | 必填，最多 64 字符；自动、手动重试都复用原 ID |
| `messageType` | 默认 1；沿用现有消息类型和校验逻辑 |
| `code` | 200 受理成功；4xx 明确拒绝，立即显示失败；5xx 结果不确定，有限重试 |
| `duplicated` | true 也是成功，表示数据库已经存在同一发送者和 clientMessageId 的消息 |

| 相对首次发送时间 | 未获有效结果时的动作 |
|---|---|
| 0ms | 首次发送 |
| 500ms | 第一次重试 |
| 1500ms | 第二次重试 |
| 6500ms | 显示“发送结果未确认，请点击重试”，不再自动发送 |

超时不代表数据库没有消息。超时后继续保留关联信息 60 秒，迟到的成功回执会修正气泡并保存本地；之后可点击重试，用原 ID 查询/提交并获得原受理结果。登出或主动重建连接会停止当前自动重试。附件重试复用已上传 URL，避免重复上传改变同一 ID 的消息内容。上传、历史、HTTP 补拉及送达/已读逻辑不变。纯浏览器环境没有目前 Electron 的 WS 桥，发送将明确报错，不自动回退 HTTP。

### 2.2 接收消息与 ACK

```text
WS 推送 type=message
       → 按 messageId 去重
       → 写入本地消息库
           ├─ 失败：不 ACK，等待重推/补拉
           └─ 成功：通知页面，再发送 ACK
                    ├─ Electron：WS type=ack
                    └─ 无 WS：POST /api/message/delivered
```

### 2.3 回执

```text
接收方 ACK → 服务端写 delivered_at → 推送 delivered 给发送方
接收方读到消息 → read-report → 服务端推进读游标 → 推送 read 给发送方
```

## 3. 通用约定

### 3.1 HTTP 返回

后端返回 `Result<T>`，前端 `request()` 取出 `data`，因此本文的返回内容指 `data`：

| 返回类型 | 前端拿到 |
|---|---|
| `Result<SendMessageVO>` | `SendMessageVO` 对象 |
| `Result<List<MessageVO>>` | `MessageVO[]` |
| `Result<Void>` | `null` |
| 失败 | 统一错误处理返回的错误信息 |

### 3.2 身份来源

| 通道 | 身份来源 |
|---|---|
| HTTP | 网关验证 token 后注入 `X-User-Id` |
| WebSocket | 握手时读取 `X-User-Id` 并保存到会话属性 |
| ACK | 不从请求体读取用户 ID，使用当前请求/连接身份 |

## 4. HTTP 接口一览

| 方法 | 路径 | 参数/请求体 | 返回 |
|---|---|---|---|
| `POST` | `/api/message/send` | JSON | `SendMessageVO` |
| `POST` | `/api/message/upload` | multipart `file` | `AttachmentVO` |
| `GET` | `/api/message/history` | `peerUserId`、分页参数 | `MessageVO[]` |
| `GET` | `/api/message/undelivered` | `size` | `MessageVO[]` |
| `POST` | `/api/message/delivered` | `messageIds[]` | `null` |
| `POST` | `/api/message/read` | `peerUserId`、`lastReadMessageId` | `null` |
| `GET` | `/api/message/conversations` | `size` | `ConversationVO[]` |

## 5. HTTP 接口详细说明

### 5.1 发送消息：`POST /api/message/send`

这是保留的兼容接口，当前桌面前端使用第 2.1.1 节的 WS 发送协议；两者复用同一段消息业务。

请求体字段：

| 字段 | 类型 | 必填 | 约束/说明 |
|---|---|---:|---|
| `toUserId` | `Long` | 是 | 单聊对方用户 ID，不是手机号 |
| `content` | `String` | 是 | 不能为空，最多 2000 字符；附件消息放 URL |
| `clientMessageId` | `String` | 是 | 前端生成的唯一 ID，最多 64 字符 |
| `messageType` | `Integer` | 否 | 不传按 `1` 文本处理 |

消息类型：

| 值 | 类型 | 当前说明 |
|---:|---|---|
| `1` | 文本 | 直接发送 |
| `2` | 图片 | 先上传，再发送 URL |
| `3` | 文件 | 先上传，再发送 URL |
| `4` | 语音 | 类型已定义，当前未发现完整语音流程 |

请求示例：

```json
{
  "toUserId": 2,
  "content": "你好",
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
  "messageType": 1
}
```

返回字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `messageId` | `Long` | 服务端消息 ID，后续 ACK/已读使用 |
| `conversationId` | `Long` | 单聊会话 ID |
| `createdAt` | `LocalDateTime` | 服务端创建时间 |
| `duplicated` | `boolean` | 是否为重复提交 |

```json
{
  "messageId": 101,
  "conversationId": 12,
  "createdAt": "2026-10-23T10:00:00",
  "duplicated": false
}
```

幂等规则：

| 场景 | 服务端行为 | 前端处理 |
|---|---|---|
| 第一次提交 | 创建消息 | 替换临时消息 |
| 超时后用同一 ID 重试 | 返回原消息，`duplicated=true` | 按成功处理 |
| 重试时生成新 ID | 被视为新消息 | 禁止 |

唯一性依据是：`发送者 userId + clientMessageId`。

### 5.2 上传附件：`POST /api/message/upload`

请求类型：`multipart/form-data`。

| 表单字段 | 类型 | 必填 |
|---|---|---:|
| `file` | `MultipartFile` | 是 |

返回字段：

| 字段 | 类型 | 用途 |
|---|---|---|
| `url` | `String` | 作为 `/send` 的 `content` |
| `name` | `String` | 原始文件名 |
| `size` | `Long` | 文件字节数 |
| `messageType` | `Integer` | 服务端判定的 `2` 或 `3` |

流程：`选择文件 → /upload → 得到 url/messageType → /send`。

### 5.3 拉历史：`GET /api/message/history`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `peerUserId` | `Long` | 是 | 对方用户 ID |
| `beforeId` | `Long` | 否 | 只取更早的消息；不传取最新一批 |
| `size` | `Integer` | 否 | 默认 20，上限由服务端限制 |

返回 `MessageVO[]`，按旧到新排列。

### 5.4 补拉未送达：`GET /api/message/undelivered`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `size` | `Integer` | 否 | 前端当前传 50，服务端默认 20 |

返回当前用户收到且 `delivered_at` 为空的消息，按 `messageId` 升序。

正确处理顺序：

```text
拉一页 → 本地落盘 → ACK 这一页 → 再拉下一页
```

该接口没有独立游标，不能全部拉完后才统一 ACK。

### 5.5 送达 ACK：`POST /api/message/delivered`

请求体：

```json
{ "messageIds": [101, 102] }
```

| 字段 | 类型 | 必填 | 约束 |
|---|---|---:|---|
| `messageIds` | `List<Long>` | 是 | 非空，一次最多 200 条 |

成功返回 `data=null`。与 WS `ack` 共用服务端逻辑，重复提交幂等。

### 5.6 已读上报：`POST /api/message/read`

请求体：

```json
{ "peerUserId": 2, "lastReadMessageId": 101 }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `peerUserId` | `Long` | 是 | 对方用户 ID |
| `lastReadMessageId` | `Long` | 是 | 读到的最后一条消息 ID |

服务端取最大值，乱序上报不会让游标倒退。成功返回 `data=null`。

### 5.7 会话列表：`GET /api/message/conversations`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `size` | `Integer` | 否 | 默认 50，上限 200 |

`ConversationVO` 字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `conversationId` | `Long` | 会话 ID |
| `peerUserId` | `Long` | 单聊对方 ID |
| `lastMessageId` | `Long` | 最后一条消息 ID |
| `lastMessageAt` | `LocalDateTime` | 最后一条消息时间 |
| `lastMessagePreview` | `String` | 最后一条消息摘要 |
| `lastMessageType` | `Integer` | 最后一条消息类型 |
| `lastMessageSenderUserId` | `Long` | 最后一条消息发送者 |
| `unreadCount` | `long` | 未读数量 |
| `deliveredUpTo` | `Long` | 我发出的消息已送达水位 |
| `readUpTo` | `Long` | 我发出的消息已读水位 |

## 6. WebSocket 协议

### 6.1 连接信息

| 项目 | 内容 |
|---|---|
| 网关地址 | `/api/message/ws` |
| 服务内部地址 | `/message/ws` |
| 数据格式 | JSON 文本帧 |
| 身份来源 | 网关注入 `X-User-Id` |
| 无身份连接 | `401 Unauthorized` |
| 当前客户端 | Electron 主进程建立 WS；纯浏览器环境没有实时连接 |

### 6.2 帧类型总表

| 方向 | `type` | 用途 | 成功响应 |
|---|---|---|---|
| 服务端 → 客户端 | `welcome` | 建连后确认身份 | 无 |
| 服务端 → 客户端 | `message` | 推送新消息 | 客户端回 `ack` |
| 客户端 → 服务端 | `ack` | 确认已保存 | 无；失败回 `error` |
| 服务端 → 客户端 | `delivered` | 通知发送方已送达 | 无 |
| 客户端 → 服务端 | `read-report` | 上报已读水位 | 无；失败回 `error` |
| 服务端 → 客户端 | `read` | 通知发送方已读 | 无 |
| 客户端 → 服务端 | `ping` | 心跳 | 服务端回 `pong` |
| 服务端 → 客户端 | `pong` | 心跳响应 | 无 |
| 服务端 → 客户端 | `error` | 协议/业务错误 | 无 |

### 6.3 新消息：`message`

```json
{
  "type": "message",
  "data": {
    "messageId": 101,
    "conversationId": 12,
    "senderUserId": 2,
    "clientMessageId": null,
    "content": "你好",
    "messageType": 1,
    "createdAt": "2026-10-23T10:00:00",
    "senderNickName": "用户2",
    "senderAvatarUrl": null,
    "deliveredAt": null,
    "readAt": null
  }
}
```

### 6.4 送达 ACK：`ack`

客户端 → 服务端：

```json
{ "type": "ack", "messageIds": [101, 102] }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `type` | `String` | 是 | 固定为 `ack` |
| `messageIds` | `Long[]` | 是 | 已经成功落盘的消息 ID |

成功时服务端不回 ACK 成功帧，而是向原发送方推送 `delivered`。失败示例：

```json
{ "type": "error", "message": "送达确认处理失败，请稍后重发 ack" }
```

### 6.5 已送达回执：`delivered`

服务端 → 原发送方：

```json
{ "type": "delivered", "conversationId": 12, "upToMessageId": 101 }
```

前端只提升本地送达水位，不降低水位。

### 6.6 已读上报：`read-report`

客户端 → 服务端：

```json
{ "type": "read-report", "peerUserId": 2, "upToMessageId": 101 }
```

无成功响应；失败回 `error`，前端可以调用 HTTP `/api/message/read`。

### 6.7 已读回执：`read`

服务端 → 原发送方：

```json
{ "type": "read", "conversationId": 12, "upToMessageId": 101 }
```

### 6.8 心跳：`ping/pong`

客户端每约 30 秒发送 `{ "type": "ping" }`，服务端返回：

```json
{ "type": "pong", "userId": 2, "serverTime": "2026-10-23T10:00:00" }
```

前端约 75 秒没有收到任何入站帧时，会主动重连。

## 7. `MessageVO` 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `messageId` | `Long` | 服务端消息 ID；排序、去重使用 |
| `conversationId` | `Long` | 单聊会话 ID |
| `senderUserId` | `Long` | 发送者用户 ID |
| `clientMessageId` | `String` | 客户端幂等 ID；替换发送方本地消息使用 |
| `content` | `String` | 文本内容或附件 URL |
| `messageType` | `Integer` | `1` 文本、`2` 图片、`3` 文件、`4` 语音 |
| `createdAt` | `LocalDateTime` | 服务端创建时间 |
| `senderNickName` | `String` | 发送者昵称 |
| `senderAvatarUrl` | `String` | 发送者头像 |
| `deliveredAt` | `LocalDateTime` | 对方收到时间 |
| `readAt` | `LocalDateTime` | 对方已读时间 |

前端处理原则：

| 事项 | 规则 |
|---|---|
| 排序 | 按 `messageId`，不要只按 `createdAt` |
| 去重 | 按 `messageId` |
| 替换乐观消息 | 按 `clientMessageId` |
| 判断状态 | 看 `deliveredAt`/`readAt` 是否为空 |

## 8. ACK、重试和补拉规则

| 情况 | 服务端 | 前端 |
|---|---|---|
| 接收方在线但未 ACK | 保持未送达并重试 | 收到后落盘，再 ACK |
| 收到 ACK | 写 `delivered_at`，通知发送方 | 更新发送方的送达水位 |
| 接收方不在线 | 保留消息 | 上线后调用 `/undelivered` |
| WS 推送失败 | 不删除消息 | 依赖重试/补拉 |
| 本地落盘失败 | 继续认为未送达 | 不 ACK |
| 重复 ACK | 幂等 | 可以安全重发 |
| WS 不可用 | 发送消息有限重试后提示未确认；HTTP 查询/上传仍可用 | 送达 ACK 退回 `/delivered`，已读退回 `/read` |

## 9. 当前未实现内容

| 功能 | 当前状态 |
|---|---|
| 群聊 | 未发现群聊接口和群聊帧 |
| 多端同时在线 | 当前同一账号同一时间只维护一个在线连接 |
| 消息撤回/编辑 | 未发现协议 |
| 回复/引用消息 | 未发现字段或协议 |
| 语音完整流程 | 类型已定义，但未发现完整上传/播放协议 |

后续增加群聊时，需要重新确定 `peerUserId` 与 `conversationId` 的关系、群成员权限、群消息 ACK 归属，以及多端同时在线时的送达定义。

