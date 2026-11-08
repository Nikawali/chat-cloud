package org.example.relation.entity;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("group_invitation")
public class GroupInvitationEntity {
    @TableId(value = "invitation_id", type = IdType.AUTO)
    private Long invitationId;
    private Long groupId;
    private Long inviterUserId;
    private Long inviteeUserId;
    private String invitationStatus;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime handledAt;
}
