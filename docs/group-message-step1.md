# 群消息第 1 步：持久化与发送受理

本文记录第 1 步的发送受理设计。后续已接入前端文本发送和 SQLite（见 group-chat-frontend.md），以及 RabbitMQ 在线群分发和 Redis 有限重推（见 group-message-delivery.md）。群接收落盘、连续送达/已读游标及 HTTP 补拉均已接入；当前实现不再使用 outbox。

## 调用链路

客户端 WS → chat-connection → 内部 HTTP chat-message → 数据库事务提交 → 内部 HTTP 响应 → 原 WS 连接返回受理结果。

| 环节 | 通道 | 接口 / 帧 |
|---|---|---|
| 客户端发送 | WS | `send-group-message` |
| connection 调用 message | HTTP | `POST /internal/message/group/send`，校验 X-Internal-Token |
| 客户端收到结果 | WS | `group-send-result`，使用 clientMessageId 关联请求 |

发送帧：

```json
{"type":"send-group-message","groupId":10,"clientMessageId":"group-msg-001","messageType":1,"content":"大家好"}
```

| 参数 | 规则 |
|---|---|
| groupId | 必填，正整数群 ID |
| clientMessageId | 必填，1～64 位字母、数字、下划线或连字符；同一发送者的群消息中唯一 |
| messageType | 可省略，默认 1；沿用单聊 1 文本、2 图片、3 文件、4 语音 |
| content | 必填，非空白，最多 2000 字符；保存时去掉首尾空白 |
| userId | 客户端无需传；始终从认证后的 WS 会话取，客户端伪造值不生效 |

图片、文件复用现有 AttachmentStorageService 的上传地址格式校验。这不是新增内容审核或密码学签名验证。语音沿用现有类型规则，未新增上传功能。

受理成功示例：

```json
{
  "type":"group-send-result",
  "clientMessageId":"group-msg-001",
  "code":200,
  "message":"成功",
  "data":{
    "messageId":101,
    "groupId":10,
    "seq":1,
    "createdAt":"2026-11-02T10:00:00",
    "duplicated":false
  }
}
```

`message` 为说明文字，以实际响应为准；客户端依据 code 判断结果。重复提交返回原 messageId、groupId、seq、createdAt，duplicated=true。

| code | 含义 |
|---|---|
| 200 | 事务已提交，或相同请求此前已提交 |
| 400 | 参数、内容、类型或附件地址不合法 |
| 403 | 非有效群成员，或内部调用凭证无效 |
| 404 | 群不存在或已解散 |
| 409 | 同一幂等 ID 更换群、内容或类型；或群序号耗尽 |
| 503 | 调用失败、超时或未取得有效受理结果；可能已经提交，使用原 ID 和原内容重试 |

业务失败响应仍携带 clientMessageId，data 为空。无法解析的 JSON 沿用通用 error 帧。
服务端不会因受理结果发送失败撤销已提交消息；客户端超时重试必须复用 clientMessageId。

## 事务与并发

1. 校验参数、类型及内容。
2. 事务内锁定 group_chat 行，检查群有效状态。
3. 锁定发送者的有效 group_member 行，验证成员身份。
4. 查询 sender_user_id + client_message_id；已有记录须与当前群、类型和规范化内容一致。
5. 以 last_seq + 1 分配群内序号，写 group_message。
6. 更新 group_chat.last_seq、last_message_id、last_message_at，与消息正文一起提交；提交后异步尽力发布 MQ，不再写 outbox。

同群消息通过群行锁排序，不在 Redis 分配序号。消息插入或摘要更新失败时整个事务回滚。
保证连续的是群内 seq；数据库自增 message_id 回滚后可以有空洞。
跨群并发复用同一幂等 ID 由唯一索引兜底，在事务回滚后重新查询并返回冲突。

依赖原有索引：
- group_message UNIQUE(group_id, seq)
- group_message UNIQUE(sender_user_id, client_message_id)
- group_chat PRIMARY KEY(group_id)
- group_member PRIMARY KEY(group_id, user_id)

## 当前数据库边界

当前项目共用 chat 数据库，消息服务直接读取并锁定群状态/成员行，与关系服务采用相同的先群后成员锁顺序。
这样能够把成员有效性和消息写入放在同一数据库事务里。将来拆分物理数据库时，需要重新设计群权限与消息写入的一致性，不能直接把此查询替换为一次普通远程校验。

已有禁言字段本阶段不参与发送校验。成员有效状态仍为 member_status=1。

## 启动与验证

表结构复用 chat-cloud/sql/group.sql，不新增生产表字段。本次未连接或修改实际业务数据库；运行前应确保现有数据库已执行群聊表脚本，特别是 group_message 和上述唯一索引。
重新编译并重启 chat-message 与 chat-connection（多实例时全部更新）。

执行：

```powershell
.\mvnw.cmd -pl chat-message,chat-connection -am test '-Dtest=GroupMessagePersistenceTest,SingleConversationTest,ConnectionBoundaryTest,SendMessageHandlerTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

共 19 项通过。群消息持久化测试使用 H2 MySQL 模式验证实际事务、Mapper、并发幂等及回滚；WS 测试模拟内部客户端，验证身份、受理结果和错误关联。未进行真实 MySQL 多实例端到端或负载测试。
