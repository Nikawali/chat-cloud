package org.example.message.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 消息 {@code message}。
 *
 * <p><b>排序和分页只看 {@code messageId}</b>（数据库自增）：重试和补拉必然造成乱序，
 * 客户端必须按这个 id 排序 + 去重，不能按到达顺序。{@code createdAt} 只用来"显示时间"。
 *
 * <p><b>{@code clientMessageId}</b> 是前端生成的（UUID），配合同表的唯一索引
 * {@code uk_message_sender_client (sender_user_id, client_message_id)} 实现<b>幂等</b>：
 * 客户端没收到受理 ack 会重发，服务端插入冲突时应该"返回已存在的那条"，而不是报错。
 * 注意它不是"防重放攻击"的东西（那是登录那套 nonce 的活）。
 */
@Data
@TableName("message")
public class MessageEntity {

    @TableId(value = "message_id", type = IdType.AUTO)
    private Long messageId;

    private Long conversationId;

    private Long senderUserId;

    /** 前端生成的 UUID；同一个发送者内唯一（唯一索引在 (sender_user_id, client_message_id)） */
    private String clientMessageId;

    private String messageContent;

    /** 1=文本，2=图片，3=文件，4=语音 */
    private Integer messageType;

    private LocalDateTime createdAt;
}
