package org.example.message.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 会话成员 {@code conversation_member}。
 *
 * <p><b>没有自增主键</b>，主键是 (conversation_id, user_id) 复合键 ——
 * 天然保证"一个人在同一个会话里只有一行"。这个设计（复合主键而不是额外 id）
 * 对"成员表"这种纯关联表是对的。<b>所以实体里没有 {@code @TableId}</b>，
 * MyBatis-Plus 的 {@code selectById} / {@code updateById} 对它不可用，
 * 查询要用 {@code selectOne(wrapper)} 按这两个字段定位。
 */
@Data
@TableName("conversation_member")
public class ConversationMemberEntity {

    private Long conversationId;

    private Long userId;

    /**
     * 我读到哪条消息了 —— <b>未读数的唯一可靠来源</b>。
     * 单聊按会话内消息ID比较计算未读数。
     */
    private Long lastReadMessageId;

    private LocalDateTime joinedAt;

}
