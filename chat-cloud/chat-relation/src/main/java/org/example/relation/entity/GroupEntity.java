package org.example.relation.entity;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("group_chat")
public class GroupEntity {
    @TableId(value = "group_id", type = IdType.AUTO)
    private Long groupId;
    private String groupName;
    private String avatarUrl;
    private Long ownerUserId;
    private Long createdByUserId;
    private String clientRequestId;
    private Integer groupStatus;
    private Integer memberCount;
    private Integer maxMembers;
    private Long lastSeq;
    private LocalDateTime createdAt;
}
