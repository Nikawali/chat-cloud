package org.example.relation.vo;

import java.time.LocalDateTime;

public record GroupInvitationVO(Long invitationId, Long groupId, String groupName,
        Long inviterUserId, String inviterNickName, LocalDateTime expiresAt) {}
