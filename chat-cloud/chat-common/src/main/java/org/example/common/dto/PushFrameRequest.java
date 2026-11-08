package org.example.common.dto;

import java.util.Map;

/** 消息服务向连接服务发出的内部发送命令。userId 为接收者，不是消息发送者。 */
public record PushFrameRequest(Long userId, String sessionId, Map<String, Object> frame) {
}
