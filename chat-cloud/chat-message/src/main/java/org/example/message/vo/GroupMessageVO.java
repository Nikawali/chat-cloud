package org.example.message.vo;
import java.time.LocalDateTime;
import org.example.message.entity.GroupMessageEntity;
public record GroupMessageVO(Long messageId,Long groupId,Long seq,Long senderUserId,String clientMessageId,Integer messageType,String content,LocalDateTime createdAt) {
 public static GroupMessageVO of(GroupMessageEntity m) {return new GroupMessageVO(m.getMessageId(),m.getGroupId(),m.getSeq(),m.getSenderUserId(),m.getClientMessageId(),m.getMessageType(),m.getMessageContent(),m.getCreatedAt());}
}
