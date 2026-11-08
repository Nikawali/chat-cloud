# 群聊送达与已读

| 方向 | WS 帧 | 作用 |
|---|---|---|
| 前端 → connection | group-receipt-report | 查询成员边界，或上报连续接收/已读游标 |
| connection → 前端 | group-receipt-result | 对应 clientMessageId 的 code/message/data；成功才算回执确认 |
| message → connection → 在线成员 | group-receipt | 某个成员的最新送达和已读位置 |

上报示例：

```json
{"type":"group-receipt-report","clientMessageId":"request-id","groupId":10,"deviceId":"local-db-uuid","memberVersion":1,"receivedSeq":103,"readSeq":101}
```

首次请求省略 memberVersion、receivedSeq、readSeq，返回 groupId、memberVersion、visibleFromSeq、lastSeq 和 members。每个成员包含 userId、memberVersion、visibleFromSeq、receivedSeq、readSeq。身份始终来自 WS 鉴权会话，忽略帧中伪造 userId。deviceId 由本账号 SQLite 消息库生成并保存，同一 WS 会话首次使用后绑定，不是 WS sessionId；当前仍是单在线端，不是完整多设备登录鉴权方案。

前端从 visibleFromSeq-1 起扫描 SQLite：只有序号连续且已经落盘才能推进 receivedSeq，不能拿服务端的接收游标替代本机内容。101、103 已落盘时仍只报101，补齐102后才报103。readSeq 同样连续，且只跨过自己的消息或本机已经阅读的消息；窗口有焦点并打开对应群聊时标本地已读。

查询其他成员水位与上报自己的游标分开执行：

| 场景 | 查询成员水位快照 |
|---|---|
| 打开群聊 | 查询该群一次 |
| 保持在该群且窗口可见、有焦点 | 上轮完成后60秒查询该群 |
| 重新聚焦或回到前台 | 当前群立即查询，短时间重复事件合并 |
| 切换会话、离开页面、失焦或隐藏 | 停止/暂停原群定时查询 |
| WS welcome（连接/重连） | 获取有效群列表，逐群查询全部群的水位（包括未打开的群） |
| pong 心跳、全局正文补拉 | 不查询全群成员水位 |
| group-receipt 实时推送 | 合并该成员水位，不触发查询 |

普通游标上报只在本机连续位置超过已确认位置时进行，250ms合并；复用 createMessageSender 的500ms/1000ms传输重试，仍未确认则按1s、2s、4s等退避，最高30s。确认位置按成员版本保存SQLite，重启不重复发送已确认游标。失败或结果丢失不标记确认，重连后重报。

已有成员版本时直接上报；缺少版本或收到409时，用 ownOnly=true 查询自己的成员边界，不读取其他成员水位。完整快照查询省略receivedSeq/readSeq；普通上报和ownOnly查询仅返回自己的members条目。前端以partial合并自己的确认和实时推送，以完整快照替换成员集合。

后端事务锁定群及成员，校验有效成员、memberVersion、可见范围、已提交 lastSeq；receivedSeq 按 group/user/device 单调写 group_device_sync；readSeq 按 group/user 单调写 group_member，且不超过本设备连续接收位置。重复或乱序回执不会倒退，非法已读导致整个事务回滚。提交后推 group-receipt，失败由后续状态同步恢复。

Redis 沿用现有 DeliveryQueue：回执事务提交后立即按本次推进区间批量删除 Redis 调度、次数和租约；删除失败时，群重推扫描依据数据库 receivedSeq 自行清理。不是逐条数据库收据。

发送者使用共用气泡状态区显示“X 人已送达 · Y 人已读”，按当前有效且能看到该消息的其他成员游标计算，不表示逐条阅读时间，也不保存历史退群成员统计。

## 启动与边界

完整重启 Electron，重编译并重启全部 chat-message、chat-connection 实例。服务端复用 group.sql 中的 group_device_sync 和 group_member 现有字段，确保已执行该建表脚本；本地表自动升级。

群 HTTP 补拉已接入，与单聊共用重连、聚焦、前台和每60秒同步触发。按有效群摘要与本地连续游标比较，存在缺口时分页读取正文、落盘后再推进并上报游标。接口和迁移说明见 group-message-delivery.md。

验证包含真实 SQLite 连续位置/已读缺口/成员版本，模拟 WS 上报流程，H2 MySQL 模式事务/单调更新/设备隔离/非法回执回滚，以及 connection 身份隔离。未完成真实多客户端端到端联调。

本次水位查询调整验证：前端33项通过，Vite构建通过；后端 GroupReceiptTest（5）、GroupMessagePersistenceTest（8）、GroupMqFlowTest（7）、SendMessageHandlerTest（4）共24项通过。测试使用启动时加载 Mockito agent，避开当前 Windows 沙箱动态附加限制。
