package org.example.common.dto;

import java.util.List;

/** 连接服务从已认证的 WS 会话获取身份后，向消息服务转交送达确认。 */
public record DeliveredReport(Long userId, List<Long> messageIds) {
}
