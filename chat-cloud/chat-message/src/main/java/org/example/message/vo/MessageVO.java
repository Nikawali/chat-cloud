package org.example.message.vo;

import java.time.LocalDateTime;

import org.example.message.entity.MessageEntity;

/**
 * 一条消息 —— 给前端看的形状。
 *
 * <p>字段分三类：
 * <ol>
 *   <li><b>消息本体</b>：messageId / conversationId / senderUserId / content / messageType / createdAt</li>
 *   <li><b>发送者展示信息</b>：senderNickName / senderAvatarUrl（本服务不存用户资料，是从 chat-user 批量取的）</li>
 *   <li><b>状态</b>：deliveredAt / readAt —— 只对<b>我发出去的消息</b>有意义，
 *       表示"对方何时收到 / 何时读了"（界面上的单勾、双勾）</li>
 * </ol>
 *
 * <p><b>为什么带 clientMessageId？</b> 前端发出消息时会先在界面上乐观插入一条本地消息
 * （否则要等服务端返回才显示，输入框会"卡一下"）。服务端返回或推送回来的这条带同一个
 * clientMessageId，前端就能把本地那条替换成服务端的（拿到真正的 messageId）。
 *
 * <p><b>排序依据是 messageId，不是 createdAt</b>：重试和补拉必然造成乱序，
 * createdAt 只精确到微秒且可能相同，而 messageId 是全局自增、严格有序。
 *
 * <p>注意<b>不带</b> {@code deliveryStatus}：那个字段是"最后一次投递尝试的结果"，
 * 属于服务端调度用的信息，前端只该看 {@code deliveredAt} 是不是空。
 */
public record MessageVO(
        Long messageId,
        Long conversationId,
        Long senderUserId,
        String clientMessageId,
        String content,
        Integer messageType,
        LocalDateTime createdAt,
        String senderNickName,
        String senderAvatarUrl,
        LocalDateTime deliveredAt,
        LocalDateTime readAt
) {

    /**
     * 消息本体 → VO（发送者信息和状态由调用方补上）。
     *
     * @param senderNickName  发送者昵称，查不到就是 null（前端会用自己的兜底显示）
     * @param senderAvatarUrl 发送者头像，同上
     * @param deliveredAt     对方收到的时间；<b>只有自己发的消息才传</b>，其它情况传 null
     * @param readAt          对方已读的时间；同上
     */
    public static MessageVO of(MessageEntity message,
                               String senderNickName,
                               String senderAvatarUrl,
                               LocalDateTime deliveredAt,
                               LocalDateTime readAt) {
        return new MessageVO(
                message.getMessageId(),
                message.getConversationId(),
                message.getSenderUserId(),
                message.getClientMessageId(),
                message.getMessageContent(),
                message.getMessageType(),
                message.getCreatedAt(),
                senderNickName,
                senderAvatarUrl,
                deliveredAt,
                readAt);
    }
}
