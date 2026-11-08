package org.example.common.dto;

/** 只关闭指定旧会话，避免迟到的踢人请求误关新连接。 */
public record KickConnectionRequest(Long userId, String sessionId) {
}
