package org.example.message.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 会话 {@code conversation}。
 *
 * <p>本表只保存单聊会话；群聊使用独立表。
 *
 * <p><b>{@code singleKey} 是单聊的唯一性保证</b>：两个 userId <b>升序</b>拼接（如 {@code "2:3"}），
 * 表上有唯一索引。这样"同一对人只能有一个会话"就被数据库强制住了 ——
 * 两个人同时点"发消息"也不会建出两个会话（代码里那个"先查再插"只是为了给出友好提示，
 * 真正的兜底永远是索引）。
 */
@Data
@TableName("conversation")
public class ConversationEntity {

    @TableId(value = "conversation_id", type = IdType.AUTO)
    private Long conversationId;

    /** 单聊唯一键（两个 userId 升序拼接） */
    private String singleKey;

    private Long createdByUserId;

    /** 最后一条消息 id（冗余，会话列表排序用） */
    private Long lastMessageId;

    /** 最后一条消息时间（冗余） */
    private LocalDateTime lastMessageAt;

    private LocalDateTime createdAt;
}
