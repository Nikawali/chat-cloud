package org.example.common.dto;

/** userId 只能由连接服务从已鉴权会话取得。 */
public record SendMessageCommand(Long userId, Long toUserId, String content,
                                 String clientMessageId, Integer messageType) {}
