package org.example.relation.vo;

import java.time.LocalDateTime;

public record GroupVO(Long groupId, String groupName, String avatarUrl, Long ownerUserId,
                      Integer memberCount, Integer maxMembers, Integer memberRole, LocalDateTime createdAt) {}
