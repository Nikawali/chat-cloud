# 群创建、搜索、加入和邀请

联系人页面切换“群聊”，使用“我的群聊 / 创建群聊 / 搜索加入 / 群聊邀请”。
后端由 chat-relation 的 GroupController / GroupServiceImpl 实现，外部统一走网关鉴权。
群消息发送、MQ分发及群聊同步还未接入，本期只实现群管理。

## 当前业务规则

| 场景 | 规则 |
|---|---|
| 创建 | 名称去首尾空格，1～100字符；创建者成为群主；人数上限1000 |
| 创建幂等 | 同一创建者+clientRequestId返回原群；同一ID换名称返回409 |
| 搜索 | 仅接受完整、有效的数字群 ID，按主键精确查询；不支持群名称和 ID 前缀模糊搜索，最多返回 1 条 |
| 搜索加入 | 当前所有正常群开放直接加入，不设审批或私密群；已加入时重复调用不加人数 |
| 邀请 | 只有有效群主/管理员能邀请自己的好友，一次最多50人；对方同意才入群 |
| 邀请幂等 | 同群同受邀者只保留一行；有效待处理邀请不重复发送 |
| 拒绝/过期 | 邀请7天有效；拒绝后24小时不能重邀；过期后可重发 |
| 接受 | 只有受邀者可操作；再次检查群状态、邀请有效性、邀请人的管理权限及人数 |
| 重入群 | 主动退出者可以重入；成员版本递增、重新设置历史边界并清理旧设备进度 |
| 移出群 | 被移除状态的用户不能自行加入或被重新邀请，解除限制需后续管理功能 |
| 历史 | 新成员从入群时last_seq+1开始可见；read_seq初始化为入群基线；不创建设备接收确认 |

群成员写入与人数增加在同一个数据库事务内，先以 SELECT ... FOR UPDATE 锁定群行。
加入、邀请、接受、拒绝沿用同一加锁顺序。邀请本身不预占人数名额，接受时群满会失败。
群详情仅暴露群基础资料；成员列表只允许有效群成员访问。

## HTTP 接口

所有地址以 `/api/relation/group` 为前缀，必须携带有效token。
发送者/操作者身份由网关鉴权后注入，不接受客户端body中的userId冒充。
返回沿用 `{code,message,data}`，业务成功code=200，4xx为参数/权限/状态错误。

| 方法 | 后缀 | 参数 | data |
|---|---|---|---|
| POST | 空 | `{groupName,clientRequestId}` | GroupVO |
| GET | `/mine` | `after`默认0 | 我的群，按groupId升序，每页50条 |
| GET | `/search` | `keyword` | GroupVO数组，最多20条 |
| GET | `/{groupId}` | 群ID | GroupVO |
| POST | `/{groupId}/join` | 无body | GroupVO |
| POST | `/{groupId}/invitations` | `{userIds:[...]}` | 本次新发/重新发送邀请数，0表示已在群或已有待处理邀请 |
| GET | `/invitations` | `after`默认0 | 待处理且未过期邀请，每页50条，按invitationId升序 |
| GET | `/invitations/count` | 无 | 当前用户待处理邀请总数，不受分页大小限制，排除过期和已解散群 |
| POST | `/invitations/{invitationId}/accept` | 无body | GroupVO |
| POST | `/invitations/{invitationId}/reject` | 无body | null |
| GET | `/{groupId}/members` | `after`默认0 | 成员，每页100条，按userId升序 |

分页after取上一页最后一条对应ID，不使用OFFSET。达到页大小时前端显示“加载更多”。
邀请详情的群名和邀请人昵称、成员列表的昵称/头像由服务端批量补齐。

GroupVO字段：`groupId,groupName,avatarUrl,ownerUserId,memberCount,maxMembers,memberRole,createdAt`。
memberRole为null代表当前用户未加入，1=成员、2=管理员、3=群主。

邀请字段：`invitationId,groupId,groupName,inviterUserId,inviterNickName,expiresAt`。
成员字段：`userId,nickName,avatarUrl,memberRole`。

好友请求与群邀请由同一份全局提醒状态维护；统一调用
`GET /api/relation/requests/summary`，返回 `{friendRequestCount,groupInvitationCount}`。
底部联系人导航显示两类数量之和，好友和群聊入口分别显示各自数量。
好友页提供“群聊邀请”入口，可直接跳到邀请列表。接受/拒绝或直接加入后刷新计数。
两类数量在主布局启动、窗口聚焦/切回前台、WS重连时统一刷新，没有30秒定时轮询；
并发请求合并，短时间内去重。打开详情列表和处理请求后也可主动对齐；此功能为HTTP检查。
登出清空计数，旧账号迟到响应不会更新新账号的角标。
好友申请详情进入“新的朋友”时加载，群邀请详细列表按页获取；用户昵称接口不可用不影响数量汇总。

## 数据库部署

首次部署：依次执行 `chat-cloud/sql/group.sql` 和 `chat-cloud/sql/group-management.sql`。
已有旧版群表：只执行 `group-management.sql`，补创建幂等字段、唯一索引、前缀搜索索引和邀请表。
该迁移按information_schema检查后再添加，可重复执行，不删除已有业务数据。

本期测试使用隔离的H2内存库，覆盖真实Mapper SQL、事务、权限、幂等和并发人数上限；
MySQL特有DDL需通过实际MySQL迁移验证。群消息相关表本期无业务写入。
