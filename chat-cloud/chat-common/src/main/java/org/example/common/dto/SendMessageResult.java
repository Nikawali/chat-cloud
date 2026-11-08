package org.example.common.dto;

import java.time.LocalDateTime;

public record SendMessageResult(Long messageId, Long conversationId,
                                LocalDateTime createdAt, boolean duplicated) {}
