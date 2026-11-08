package org.example.message.entity;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("group_message")
public class GroupMessageEntity {
    @TableId(value = "message_id", type = IdType.AUTO)
    private Long messageId;
    private Long groupId;
    private Long seq;
    private Long senderUserId;
    private String clientMessageId;
    private Integer messageType;
    private String messageContent;
    private LocalDateTime createdAt;
}
