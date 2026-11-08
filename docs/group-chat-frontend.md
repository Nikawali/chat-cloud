# 群聊天前端：文本发送与 SQLite 持久化

入口：联系人 → 群聊 → 点击群名称/头像；群详情也有进入按钮。
已加入的搜索结果可以直接进入。路由为 `/message/group/:groupId`。
单聊和群聊复用 ChatHeader、ChatComposer 和现有双栏布局；两者右上角菜单均可点击。

| 页面 | 菜单 |
|---|---|
| 单聊 | 查看好友资料、会话信息 |
| 群聊 | 群聊资料与成员、返回群聊列表 |

## 文本发送

1. 确认当前账号、本地库及有效群成员身份。
2. 生成 clientMessageId，将原文和 sending 状态先写入 SQLite 发件箱。
3. 通过现有 Electron WS 发送 send-group-message，messageType 固定为 1。
4. 收到匹配的 group-send-result/code=200 后保存 messageId、groupId、seq、createdAt，并更新为 sent。
5. 失败保留原文和原 clientMessageId；点击重试复用它们，更新同一行，不新增气泡。

| 界面状态 | 含义 |
|---|---|
| 发送中 | 已写本地发件箱，正在等待服务端受理 |
| 已发送 | 已收到服务端持久化成功结果，并已保存到本地 |
| 发送失败或结果未确认 | 被业务拒绝、结果超时或本地写入异常；可查看错误并点击重试 |

自动重试复用单聊发送器：首次发送后等待 500ms，重试后等待 1000ms，再重试后等待 5000ms，仍未确认则显示失败。
4xx 直接结束当前等待；5xx、连接发送失败及结果丢失进入有限重试。
单聊与群聊使用独立发送器、独立帧类型，不能相互确认。
发送成功表示服务端受理，不表示群成员已送达或已读。

已收到的迟到成功结果由账号级监听保存，切走页面后仍生效。
成功状态不会被迟到的失败更新覆盖。退出登录后停止旧账号监听，异步操作还会校验账号令牌。

## 本地持久化

沿用 Electron 主进程的 `messages/u<userId>/messages.sqlite`，按账号目录隔离，自动创建：

| 表 | 用途 |
|---|---|
| group_conversations | 打开并加载过的群资料、会话入口 |
| group_messages | 文本发件箱、受理后的消息及收到的群消息；(sender_user_id, client_message_id) 为主键 |

群消息与单聊使用不同表，允许服务端两张消息表的 messageId 数值相同。
群消息另外约束 message_id 唯一、(group_id, seq) 唯一，重复受理只更新同一条记录。
发件箱到已发送状态更新在本地事务中完成。

同账号重启后恢复群会话和消息；未确认的 sending 显示为失败/结果未知，用户可用原 ID 重试。
草稿仍在内存，切换会话保留，重启或登出清空。
在群资料接口不可用时，已加载的本地消息仍可显示，发送区暂停使用。
本地发件箱保存失败时不提交 WS，保留输入框原文。

纯浏览器没有 SQLite/桌面 WS，不伪造发送成功；真实发送需 Electron。
本阶段支持群文本收发与连续送达/已读游标，已接入 HTTP 补拉；图片/文件上传尚未接入。

全局接收 `group-message` 后先保存 SQLite，再更新群会话和聊天界面，不依赖群页面打开。
首次收到未打开群的消息时创建会话入口，异步获取群资料；资料接口失败不影响正文保存。
重复推送合并同一条记录，已确认消息按群内 seq 排序；自己的推送与发件箱合并。
接收消息显示左侧，自己发送显示右侧。推送未包含昵称时显示用户 ID，打开成员资料后可使用已加载昵称。
单聊和群聊共用 `ChatMessageRow.vue` 的头像、气泡和发送状态布局，共用 `styles/chatMessages.css` 的消息列宽、边距和时间条样式。
时间分隔由 `utils/messageTimeline.js` 统一生成，沿用单聊的首条、五分钟间隔及跨天规则；群成员昵称通过共用组件的可选属性展示。
落盘后上报独立 group-receipt-report，复用 WS 请求重试；不调用单聊 ACK。服务端持久化回执后立即批量清理 Redis 已确认项，重试扫描负责清理失败时兜底。详见 group-receipts.md。
群未读由 SQLite 的 local_read 标记统计，重复推送不会重复计数。打开对应群聊且窗口有焦点后清除本地未读；乱序后到的消息独立计数，不用最大 seq 跳过缺口。
群会话使用现有列表红色角标；底部消息总数为单聊与群聊未读之和。新群消息复用单聊桌面通知入口，自己的消息和重复推送不通知。
旧版本本地群消息表自动迁移，已有发件箱和消息保留。

## 使用与验证

需要上一步的 chat-message、chat-connection 群发送代码运行。
本次修改了 Electron preload 和主进程存储，需完整退出并重新启动桌面客户端；只刷新网页不会更新 IPC。
不需要修改后端数据库；本地群表自动创建，单聊表保持原结构。

运行：

```powershell
node --experimental-vm-modules --test tests/foregroundRefresh.test.js tests/messageTimeline.test.js tests/messageSender.test.js tests/groupMessageStore.test.js tests/groupChatFlow.test.js tests/requestReminderCounter.test.js
npm run build
```

33 项测试覆盖真实 SQLite 关闭重开、旧表迁移、未读统计及本地清除、接收去重与乱序、自己推送与发件箱合并、重复受理、状态单调、先落盘后发送、超时重试、迟到结果和账号隔离。
流程测试模拟 WS；未执行真实账号与真实服务端的端到端发送。
浏览器夹具 `tests/group-invitations-preview.html?mode=group-chat&check=1` 验证丢失回执后的重试、单聊/群聊切换和菜单；
`?mode=group-chat&failure=1&theme=dark` 验证业务拒绝后的手动重试。夹具全部使用模拟接口和模拟 Electron IPC。
