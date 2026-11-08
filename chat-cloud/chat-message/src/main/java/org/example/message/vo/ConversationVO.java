package org.example.message.vo;

import java.time.LocalDateTime;

/**
 * 会话列表里的一项 —— 消息页（会话列表）要的全部信息。
 *
 * <p><b>它只给"列表那一行"要的东西，不给具体消息。</b> 具体消息属于
 * {@code GET /message/history}（按会话翻页看）和补拉；这里要是带上消息，
 * 50 个会话就变成 50 段历史，纯属浪费。
 *
 * <p>字段分四类：
 * <ol>
 *   <li><b>定位</b>：conversationId / peerUserId（点进去用哪个人打开会话）；</li>
 *   <li><b>最后一条的摘要</b>：lastMessage*（列表那一行显示的那句"最后一句"）；</li>
 *   <li><b>未读数</b>：unreadCount —— <b>权威值</b>，按 {@code conversation_member.last_read_message_id} 算。
 *       客户端本地也能算一个，但它只留最近 500 条消息，所以那个只是"离线时的近似"；</li>
 *   <li><b>回执水位</b>：deliveredUpTo / readUpTo —— "我发的消息被对方送到/读到哪条为止"。
 *       <b>它和未读数方向正好相反</b>（未读 = 对方发的我读了多少；水位 = 我发的对方读了多少）。
 *       带上它是为了让客户端<b>不用拉消息</b>就能把聊天页的勾对上账 ——
 *       送达/已读回执是一次性推送，我离线期间对方读了的那些永远推不到我这来，
 *       以前靠"重连时拉一次历史"补，现在靠这个接口顺带带回来。</li>
 * </ol>
 *
 * <p><b>为什么没有对方昵称/头像/备注？</b> 本服务不存用户资料；备注更是"我给对方起的名字"，
 * 只有 chat-relation 的 {@code relation_friend.remark} 知道。前端进消息页时本来就会拿一次好友列表，
 * 用 peerUserId join 一下名字头像备注全有了 —— 这里再调一次 chat-user 是重复劳动。
 *
 * <p>⚠️ 字段名<b>故意和客户端本地库（{@code messages/u<我>/index.json} 里的会话摘要）完全一致</b>：
 * 这样客户端"用服务端数据校正本地那份"时可以直接整份替换，不用写字段映射（少一处会跑偏的地方）。
 *
 * @param conversationId 会话 id
 * @param peerUserId     单聊的对方 userId
 * @param lastMessageId  最后一条消息的 id
 * @param lastMessageAt  最后一条消息的时间（列表按它倒序）
 * @param lastMessagePreview 最后一条的摘要（已截断、换行压平；非文本消息由前端按类型显示成 [图片]）
 * @param lastMessageType 最后一条的类型：1=文本 2=图片 3=文件 4=语音
 * @param lastMessageSenderUserId 最后一条是谁发的（前端据此决定要不要加"你: "前缀）
 * @param unreadCount    未读条数（对方发的、id 大于我的读游标）
 * @param deliveredUpTo  我发的消息里"送到某条为止"（没送达过就是 null）
 * @param readUpTo       我发的消息里"读到某条为止"（没读过就是 null）
 */
public record ConversationVO(
        Long conversationId,
        Long peerUserId,
        Long lastMessageId,
        LocalDateTime lastMessageAt,
        String lastMessagePreview,
        Integer lastMessageType,
        Long lastMessageSenderUserId,
        long unreadCount,
        Long deliveredUpTo,
        Long readUpTo
) {
}
