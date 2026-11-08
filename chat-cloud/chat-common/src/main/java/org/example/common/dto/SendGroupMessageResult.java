package org.example.common.dto;

import java.time.LocalDateTime;

/** 仅代表事务已提交，不代表任何成员已收到。 */
public record SendGroupMessageResult(Long messageId, Long groupId, Long seq,
                                     LocalDateTime createdAt, boolean duplicated) {}
