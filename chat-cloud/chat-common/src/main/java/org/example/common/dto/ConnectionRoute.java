package org.example.common.dto;

/** 用户的连接归属；真实 WS 会话只存在连接实例内存中。 */
public record ConnectionRoute(String instanceId, String sessionId) {
}
