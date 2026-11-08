package org.example.relation.entity;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("group_member")
public class GroupMemberEntity {
    private Long groupId;
    private Long userId;
    private Integer memberRole;
    private Integer memberStatus;
    private Long memberVersion;
    private Long visibleFromSeq;
    private Long visibleToSeq;
    private Long readSeq;
    private LocalDateTime joinedAt;
}
