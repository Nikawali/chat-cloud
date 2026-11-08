# message 服务职责重构

## 结构

| 包 | 职责 |
|---|---|
| service.send | MessageSender<C,R> 公共发送契约；MessageContentPolicy 消息类型及附件 URL 规则 |
| service.single | SingleMessageSender / Query / Receipt / Delivery，单聊会话与消息组装 |
| service.group | GroupMessageSender、群访问加锁、群回执、群同步 |
| service.delivery | DeliveryQueue、公共 DeliveryRetryRunner、群投递 |
| mq | GroupMessagePublisher、GroupFanoutConsumer |
| service.attachment | 上传、文件类型及存储 |
| service.impl.MessageServiceImpl | 原 MessageService 的薄兼容适配层，不再承载查询、收据和投递实现 |

单聊发送实现 MessageSender<SendMessageCommand,SendMessageVO>，群聊实现 MessageSender<SendGroupMessageCommand,SendGroupMessageResult>。Spring按泛型区分发送实现。群发送原来只有一项send方法的专用接口已移除。

## 保持的行为

- 对外 HTTP 地址、内部 HTTP 地址和 WS 帧不变，数据库结构及配置键不变。
- 单聊先检查消息类型/附件URL和好友关系，再查询幂等键。新消息事务写正文、收据和会话摘要，提交后投递。
- 单聊普通重复查询会补登记投递；唯一键冲突后返回旧结果的分支仍维持原样。没有借重构新增重复请求的正文或目标冲突校验。
- 群发送在事务内先锁群再检查成员，分配seq、写正文和摘要，事务提交后发布MQ。事务失败不发布。
- 群发送的404/403权限错误及重复请求冲突校验保留。群回执和补拉仍先锁群再锁成员，版本/可见范围规则保留。
- 单聊逐条收据与群连续游标各自保留。送达事实提交后才清理Redis和通知发送者，通知失败不撤销数据库事实。
- 单聊重推默认批次200、租约30000ms、180秒窗口及原退避公式不变。群重推仍每批100、租约30000ms、次数上限8及原退避公式。
- 公共重试执行器只负责批次、异常隔离和finally续排，不把两套停止条件混在一起；租约和ACK并发防护仍在原Lua。
- 群重推继续逐条检查成员和送达状态，避免批量权限快照改变退出/重入群期间的检查时机。

## 消除的重复与额外查询

- 消息类型和附件URL校验只有一份实现，上传类保留原静态入口作为委托。
- 群HTTP补拉与WS推送都从GroupMessageVO.of构造正文；WS仍保留原有type=group的data封装。
- GroupAccess统一锁群/锁成员及可见上界计算。
- 群ACK每批一次读取messageId和seq，删除原来额外查询最后消息seq的SELECT；继续按200条一批清理。

## 验证与启动

先在重构前建立单聊发送行为基线，再逐步拆分并复测。测试覆盖提交前不投递、回滚、两种重复分支、实际H2查询和收据更新、已读修复送达、通知失败、群消息事务与回执、租约重试、泛型Spring注入以及附件URL规则。

执行 Maven clean test，避免旧包.class残留造成重复Bean。Redis、RabbitMQ与真实连接集成测试只有配置专用CHAT_TEST_*环境时才运行，未据此宣称真实环境端到端验证完成。

类包位置变更，需要重新编译并重启message服务；HTTP/WS协议未变，前端无需配合修改。

最终验证：message与connection相关模块执行Maven clean test，46项通过；11项外部Redis/RabbitMQ/连接集成测试因未设置专用环境而跳过。此次没有修改前端、数据库结构或运行中的业务数据。
