# chat-connection

长连接接入服务，默认端口 `8085`，服务名 `chat-connection`。支持多个连接实例，保持一账号一个有效连接。

## 服务边界

| 服务 | 职责 |
|---|---|
| chat-gateway | token 鉴权、身份注入、HTTP/WS 路由 |
| chat-message | 消息校验与幂等、MySQL 事务、Redis 待送达重试、ACK/已读业务、历史和补拉 |
| chat-connection | WS 握手、在线会话、心跳、空闲连接清理、实际帧发送、消息提交及 ACK/已读转交 |
| chat-common | 共享帧约定及内部调用 DTO，不依赖 Servlet/WebSocket/Jackson |

客户端仍连接 `ws://localhost:8080/api/message/ws`，内部端点保持 `/message/ws`。
网关专用 WS 路由转发至 `chat-connection`；其他 `/api/message/**` 请求仍进入 `chat-message`。
客户端发送入口已使用 WS，存储方式不变；兼容 HTTP 发送接口仍保留。

## 调用流程

```text
发送：客户端 WS send-message → gateway → connection → 任意 message（校验、落库、登记重试）
                                     → Redis 查用户连接归属
                                     → Nacos 按实例元数据定位
                                     → 目标 connection 内部接口 → 接收方 WS

受理：message 返回 → 原 connection → 发送方 WS send-result（clientMessageId 关联）

确认：客户端 WS ack/read-report → gateway → connection（取会话身份）
                                         → message 内部接口（写收据/读游标）
                                         → connection（发送回执给原发送方）

心跳：客户端 WS ping → connection → pong
重推：message 定时任务 → connection 内部接口 → 接收方 WS
补拉：客户端 HTTP → gateway → message
```

WS 新增 `send-message` / `send-result`，请求、响应及重试时序见项目的 `docs/message-protocol.md`。
WS 送达 ACK/已读仍无成功响应帧；收据内部调用失败时回 `error`。
HTTP 的 `/api/message/delivered` 和 `/api/message/read` 兜底仍保留。

## 内部 HTTP 接口

所有新内部接口都校验 `X-Internal-Token`，返回统一 `Result<T>`，没有公网网关路由。

| 提供者 | 方法与路径 | 参数 | data |
|---|---|---|---|
| message | POST /internal/message/send | `{userId,toUserId,content,clientMessageId,messageType}` | `{messageId,conversationId,createdAt,duplicated}` |
| connection | GET /internal/connection/online/{userId} | 目标用户 ID | Boolean |
| connection | POST /internal/connection/push | `{userId, sessionId, frame}` | Boolean，是否写出 |
| connection | POST /internal/connection/kick | `{userId, sessionId}` | Boolean，是否关闭指定会话 |
| message | POST /internal/message/delivered | `{userId, messageIds}` | null |
| message | POST /internal/message/read | `{userId, peerUserId, upToMessageId}` | null |

`connection` 转交的 `userId` 取自已认证的 WS 会话，不取客户端帧中的身份字段。
内部调用同时检查 HTTP 状态及 `Result.code`。写出 WS 成功不等于客户端收到，
最终送达仍由客户端 ACK 和 MySQL 收据决定。

## 构建与启动

在 `chat-cloud` 目录执行：

```powershell
.\mvnw.cmd -pl chat-message,chat-connection,chat-gateway -am clean package
```

正常联调需要 Nacos、MySQL、Redis，以及原有 auth/user/relation 服务。
更新后重新启动 gateway、message，并增加启动 connection；服务发现均通过 Nacos。

```powershell
java -jar .\chat-connection\target\chat-connection-0.0.1-SNAPSHOT.jar
```

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| CHAT_CONNECTION_PORT | 8085 | 连接服务端口 |
| CHAT_CONNECTION_INSTANCE_ID | 机器地址:端口 | 每个连接实例必须唯一，并登记到 Nacos metadata |
| CHAT_REDIS_HOST | localhost | 与 message 使用同一个 Redis |
| CHAT_REDIS_PORT | 6379 | Redis 端口 |
| CHAT_MESSAGE_PORT | 8083 | message 实例端口，多开时区分 |
| CHAT_NACOS_SERVER_ADDR | localhost:8848 | 连接服务 Nacos 地址 |
| CHAT_CONNECTION_DISCOVERY_ENABLED | true | 是否启用连接服务发现 |
| CHAT_INTERNAL_TOKEN | 通过环境变量设置，不提供凭证默认值 | message/connection 共用的内部调用凭证 |

两个服务的 `CHAT_INTERNAL_TOKEN` 必须一致。默认凭证只供本机学习，生产必须替换，
并通过内网访问控制/TLS 保护内部服务。握手仍信任网关注入的 `X-User-Id`，
因此不能将连接服务端口直接暴露给不可信客户端。

仅验证启动、暂不使用 Nacos 时可传 `--spring.cloud.nacos.discovery.enabled=false`；
此时跨服务调用需要显式配置 Feign 目标地址，或使用下面的集成测试。

## 验证

```powershell
.\mvnw.cmd -pl chat-message,chat-connection,chat-gateway -am test
```

连接服务集成测试需要独立测试 Redis，显式设置 `CHAT_TEST_REDIS_PORT` 后执行：

```powershell
$env:CHAT_TEST_REDIS_PORT = '16379'
.\mvnw.cmd -pl chat-message,chat-connection,chat-gateway -am test
```

未设置该变量时跳过外部 Redis 集成测试。请使用专用空 Redis，避免与业务 Redis 混用。
测试使用两个真实随机端口 connection 实例、真实 Redis、本机消息服务桩及测试服务发现目录，验证：
匿名握手拒绝、内部凭证校验、welcome/ping/pong、消息及回执推送、
ACK/已读身份转交、下游业务失败转 error、跨实例踢旧连接、旧会话不能覆盖新路由、路由自动过期。
不访问业务数据库，也不向 Nacos 注册。

原有 `scripts/verify-websocket.ps1` 与 `scripts/verify-chat-push.ps1`
仍走原网关地址，可在完整服务运行时做端到端联调。

## 多实例部署

真实 WS 会话仍在各 connection 实例内存中；Redis HASH `chat:connection:route:{userId}`
保存 `userId / instanceId / sessionId`，默认 TTL 120 秒，仅当前会话心跳能续期。
消息服务按此归属定向调用，路由变更时最多重试一次；客户端 ACK、已读及 HTTP 补拉语义保持原样。
跨机器部署需要所有服务使用同一组 Nacos、Redis、MySQL，并确保 Nacos 发布的地址可相互访问。

详见仓库根目录 `docs/multi-instance.md`。当前没有引入 MQ 或 Outbox。
