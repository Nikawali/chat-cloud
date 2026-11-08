# 群消息投递（直接 MQ，无 outbox）

| 步骤 | 处理 |
|---|---|
| 1 | 事务内校验成员、分配 seq、保存 group_message、更新群摘要 |
| 2 | 提交后将 messageId 交给有界发布线程池，同时返回发送受理结果 |
| 3 | 发布持久化 RabbitMQ 消息；确认或路由失败最多尝试3次，退避500ms/1000ms；每次确认等待最多3秒 |
| 4 | 消费者枚举在线有效群成员，Redis登记待送达后推 WS |
| 5 | 接收者 SQLite 连续落盘后上报 receivedSeq，查看且聚焦后上报 readSeq |
| 6 | 回执事务提交后立即批量移除对应用户 Redis 中被游标覆盖的调度、次数及租约 |
| 7 | MQ发布丢失、消费失败、离线、Redis丢失或重推耗尽，由客户端 HTTP 补拉数据库 |

正文和群摘要是权威数据。受理成功不承诺立即推送成功。没有持久化发布任务，进程在提交后崩溃会失去此次主动通知，但消息仍可补拉。

## MQ 与重试

- exchange: chat.group.events.v2，queue: chat.group.fanout.v2，routing: message.created。
- 新消息体是 messageId。使用 v2 队列隔离旧版 outboxId，防止旧任务被错误解释成消息ID。
- 发布线程池2～4线程，队列最多500项；满载或MQ故障记录日志并依赖补拉，不阻塞发送受理线程等待broker确认。
- 消费失败交给现有容器退避重试（3次），耗尽后拒绝，不无限重新入队。数据库正文负责恢复。
- 消费幂等标记 chat:group-fanout:v2:done:<messageId> 保留7天；Redis NX登记避免重复MQ事件重置待送达任务。
- 群与单聊复用 DeliveryQueue 的领取租约、退避和原子批量删除，Redis key空间独立。
- 已持久化送达游标用于消费和重推的去重；ACK与入队并发时，入队后再查送达状态并清理。
- 当前每账号单在线端，群接收游标按设备记录；完整多端登录方案另行实现。

## HTTP 补拉接口

外部请求需正常登录，经网关注入用户身份；不接受查询参数中的userId。

| 接口 | 参数 | 返回 |
|---|---|---|
| GET /api/message/group/sync-heads | after（群ID，默认0） | 最多100个有效群，按groupId升序；包含群资料、memberVersion、visibleFromSeq、lastSeq |
| GET /api/message/group/{groupId}/messages | memberVersion、afterSeq（默认0） | groupId、memberVersion、visibleFromSeq、lastSeq、messages（最多100条） |

消息字段：messageId、groupId、seq、senderUserId、clientMessageId、messageType、content、createdAt。
服务端再次校验有效成员和成员版本，只返回可见范围内且已提交的消息；同一(group_id,seq)索引用于分页。

前端复用单聊 syncNow 的重连、前台、聚焦和可见窗口60秒同步触发。先分页读取群摘要，再从本机 SQLite 连续位置开始补拉有差异的群；一轮每群最多20页，剩余留待下一轮继续。群列表能发现从未打开且从未收到推送的群。同步不依赖WS可用，回执恢复后重报。

每页先落盘，后算连续位置，再通过现有群回执同步上报。重复消息去重，缺口/磁盘错误不越过序号；账号切换后拒绝旧结果。自己的消息同样会补拉，用原幂等ID与发件箱合并。

## 升级

1. 停止全部旧版 message 实例（避免旧代码继续写outbox），部署新后端并重启客户端。
2. 新安装使用 group.sql，不再创建 outbox；旧库执行 sql/remove-group-outbox.sql 删除弃用任务表。该脚本未自动在实际数据库执行。
3. RabbitMQ自动声明v2拓扑。旧 chat.group.fanout 队列及 chat.group.events 交换机不再使用，可在全部旧实例下线后清理。
4. 本次无需修改消息正文、成员或设备同步表。旧缺口（例如seq3～6）会按正常补拉恢复。

发布开关 chat.group.mq-enabled=false 时停止主动发布与消费，HTTP补拉仍可工作。无论有无MQ，事务失败都不能发布未提交正文。
