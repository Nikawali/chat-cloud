package org.example.common.dto;

/** 连接服务向消息服务转交已读上报。 */
public record ReadReport(Long userId, Long peerUserId, Long upToMessageId) {
}
