package org.example.message.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 消息收据 {@code message_receipt} —— <b>每条消息 × 每个接收者一行</b>。
 *
 * <p>主键是 (message_id, receiver_user_id) 复合键，没有独立的 id（纯关联表，这样是对的）。
 *
 * <h3>它是"送达/已读"的唯一事实来源</h3>
 * <ul>
 *   <li>{@code deliveredAt} 为空 = <b>还没送到</b>。这是"未送达"的<b>唯一判据</b> ——
 *       {@code deliveryStatus} 只记"最后一次投递尝试的结果"，两个判据并存必然有一天互相打架。</li>
 *   <li>{@code readAt} 非空 = 对方已经打开会话看到它了（发送方 UI 的双勾）。</li>
 * </ul>
 *
 * <p><b>为什么"3 分钟后放弃重试"不可怕？</b> 因为放弃的只是"实时推送"这条路径，
 * 消息在库里、这一行还在（delivered_at 仍为空）—— 对方下次上线时补拉就能拿到。
 * 数据库是真相，Redis 里的待送达队列只是加速。
 *
 * <p>⚠️ <b>规模提醒</b>：这张表适合单聊；群聊时 1000 人的群、一条消息 999 行，会爆。
 * 群聊使用独立的消息表和成员/设备游标，不复用本表。
 */
@Data
@TableName("message_receipt")
public class MessageReceiptEntity {

    private Long messageId;

    /** 冗余字段：避免查 receipt 还要 join message 才知道属于哪个会话 */
    private Long conversationId;

    private Long receiverUserId;

    /** 最后一次投递尝试的结果：1=已收到ack，2=还没成功，3=尝试时对方不在线 */
    private Integer deliveryStatus;

    /** 送达时间；为空就是还没送达（唯一判据） */
    private LocalDateTime deliveredAt;

    /** 已读时间；为空就是还没读 */
    private LocalDateTime readAt;
}
