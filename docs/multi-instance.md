# 消息与长连接多实例

## 实例职责

| 组件 | 职责 |
|---|---|
| gateway | 鉴权、身份注入；HTTP 与初始 WS 握手负载均衡 |
| message | 消息事务、会话、ACK/已读、补拉、重推；可部署多个实例 |
| connection | 持有本实例 WS、心跳、实际发送、将 ACK/已读转交任意 message |
| Redis | 连接归属、投递重试调度与处理租约 |
| Nacos | 服务发现、连接实例标识到地址的映射 |
| MySQL | 消息和收据的权威状态、数据库唯一索引与事务 |

## 连接登记

1. 客户端向原地址 `/api/message/ws` 握手，gateway 验证 token 并选择连接实例。
2. connection 保存本地真实 WS 会话。
3. Lua 原子替换 Redis 路由并设置 TTL，取得旧实例/旧会话。
4. 若旧会话在其他实例，通过定向内部接口关闭它；失败时旧端在后续心跳发现失去归属后关闭。
5. 新端返回原格式的 welcome。

Redis 使用 HASH（不是序列化 WS 对象或 JSON 字符串）：

```text
key: chat:connection:route:2
fields:
  userId: 2
  instanceId: connection-2
  sessionId: 某条实际连接的 ID
TTL: 默认 120 秒
```

所有权由 `instanceId + sessionId` 决定。续期与删除都由 Lua 比较这两个字段后执行；
旧连接的断开回调或心跳不能影响新路由。Redis 注册失败不会返回正常 welcome；
续期失败会关闭连接以触发客户端恢复。实例崩溃后路由由 TTL 清理。

## 发送与回执

```text
A WS send-message → gateway → 接入 connection → 任意 message（内部 HTTP）
    → 校验、幂等、数据库事务提交
    → 登记 Redis 重试
    → 查询 B 连接归属
    → 从 Nacos connection-instance-id 元数据定位准确实例
    → 内部 push（携带目标 sessionId）
    → 目标 connection 校验本地会话与 Redis 所有权 → B 的 WS

message 受理结果 → 接入 connection → A 原 WS send-result（关联 clientMessageId）

B 本地保存 → 沿原 WS 回 ack → 原 connection
    → 负载均衡调用任意 message 写收据、出队
    → 查询 A 的连接归属 → 定向调用 A 所在 connection → delivered
```

内部 Feign 调用使用服务发现返回的目标 URI，不把定向推送随机负载均衡到其他连接实例。
不接受客户端提供实例地址。路由切换或调用失败时，重新查路由，最多再尝试一次。
没有路由视为离线，消息仍在数据库；路由查询故障不能当成确定离线删除重试。
WS 写出成功不等于客户端保存成功，只有客户端 ACK 才推进送达状态。

多实例并发更新会话摘要时，只允许更大的 last_message_id 更新摘要。
同一消息的重试调度需原子领取并带租约，避免不同 message 实例同时领取同一任务；
ACK 删除任务后，迟到的重试不得将其重新登记。具体实现见 DeliveryQueue 与 DeliveryRetryJob。

默认任务租约为 30000 毫秒，可通过 `CHAT_MESSAGE_RETRY_LEASE_MS` 调整。
租约约束队列状态修改，不能撤回已经在网络中发送的帧；客户端仍必须按 messageId 去重。
ACK 出队改为一次 Lua 调用，原子批量删除调度、重试次数和领取令牌，保留单次往返的批处理效果。

## 本机多开示例

先构建：在 `chat-cloud` 目录执行 `.\mvnw.cmd -pl chat-message,chat-connection,chat-gateway -am package`。
以下命令分别在不同终端执行，使用同一套 Redis、MySQL、Nacos 和内部调用凭证：

```powershell
java -jar .\chat-connection\target\chat-connection-0.0.1-SNAPSHOT.jar --server.port=8085 --chat.connection.instance-id=connection-1
java -jar .\chat-connection\target\chat-connection-0.0.1-SNAPSHOT.jar --server.port=8086 --chat.connection.instance-id=connection-2
java -jar .\chat-message\target\chat-message-0.0.1-SNAPSHOT.jar --server.port=8083
java -jar .\chat-message\target\chat-message-0.0.1-SNAPSHOT.jar --server.port=8087
```

connection 的实例标识必须唯一；通过 Nacos metadata 的 `connection-instance-id` 发布，
与 Redis 中记录的一致。默认值为机器 IP 与端口的组合，生产建议显式指定。
外部入口仍是 gateway，客户端无须传实例标识或修改帧格式。

## 测试与边界

真实 Redis 集成测试需独立测试 Redis，并显式设置 `CHAT_TEST_REDIS_PORT` 后运行 Maven test。
没有该变量时跳过依赖外部 Redis 的集成测试。
集成测试以两个真实 HTTP/WS 服务实例验证路由、跨实例踢人、旧回调保护及 ACK 转交；
Nacos 由测试服务发现目录替代，消息业务由 HTTP 桩替代，不访问业务数据库。
真实 Nacos 注册、MySQL 并发与整套前端仍需在完整环境联调。

本次升级应先停止旧版 message 重推 worker，再启动新版，避免新旧调度协议混跑。
当前多 key Lua 使用单机/主从 Redis；Redis Cluster 需要先将调度、次数、令牌 key
迁移至同一个 hash slot，不能直接沿用当前 key 部署到 Cluster。

仍是一账号一个有效连接；多设备同时在线需要另行扩展为账号/设备维度的路由与回执。
未新增 MQ、Outbox 或消息数据库表。
