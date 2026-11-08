package org.example.common.dto;

/** userId 由 connection 从已认证的 WS 会话注入。 */
public record SendGroupMessageCommand(Long userId, Long groupId, String content,
                                      String clientMessageId, Integer messageType) {}
