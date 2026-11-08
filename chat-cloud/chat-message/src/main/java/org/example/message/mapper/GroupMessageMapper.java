package org.example.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import org.example.message.entity.GroupMessageEntity;

@Mapper
public interface GroupMessageMapper extends BaseMapper<GroupMessageEntity> {
    @Select("SELECT m.user_id FROM group_member m JOIN group_chat g ON g.group_id=m.group_id " +
            "WHERE m.group_id=#{groupId} AND g.group_status=1 AND m.member_status=1 " +
            "AND m.visible_from_seq<=#{seq} AND (m.visible_to_seq IS NULL OR m.visible_to_seq>=#{seq}) " +
            "AND m.user_id>#{after} ORDER BY m.user_id LIMIT 100")
    java.util.List<Long> recipients(@Param("groupId") Long groupId, @Param("seq") Long seq, @Param("after") long after);

    @Select("SELECT COUNT(*) FROM group_member m JOIN group_chat g ON g.group_id=m.group_id " +
            "WHERE m.group_id=#{groupId} AND m.user_id=#{userId} AND g.group_status=1 AND m.member_status=1 " +
            "AND m.visible_from_seq<=#{seq} AND (m.visible_to_seq IS NULL OR m.visible_to_seq>=#{seq})")
    int eligible(@Param("groupId") Long groupId, @Param("seq") Long seq, @Param("userId") Long userId);
    @Select("SELECT COUNT(*) FROM group_device_sync d JOIN group_member m ON m.group_id=d.group_id AND m.user_id=d.user_id AND m.member_version=d.member_version WHERE d.group_id=#{groupId} AND d.user_id=#{userId} AND d.received_seq>=#{seq}")
    int delivered(@Param("groupId") Long groupId,@Param("seq") Long seq,@Param("userId") Long userId);
    @Select("SELECT message_id,seq FROM group_message WHERE group_id=#{groupId} AND seq>#{after} AND seq<=#{upTo} ORDER BY seq LIMIT 200")
    java.util.List<ReceiptPosition> receiptIds(@Param("groupId") Long groupId,@Param("after") long after,@Param("upTo") long upTo);
    record ReceiptPosition(Long messageId,Long seq) {}
    record GroupHead(Long groupId, Integer groupStatus, Long lastSeq) {}

    @Select("SELECT group_id, group_status, last_seq FROM group_chat WHERE group_id=#{groupId} FOR UPDATE")
    GroupHead lockGroup(Long groupId);

    @Select("SELECT group_id, group_status, last_seq FROM group_chat WHERE group_id=#{groupId}")
    GroupHead readGroup(Long groupId);

    @Select("SELECT user_id FROM group_member WHERE group_id=#{groupId} AND user_id=#{userId} AND member_status=1 FOR UPDATE")
    Long lockActiveMember(@Param("groupId") Long groupId, @Param("userId") Long userId);

    @Select("SELECT * FROM group_message WHERE sender_user_id=#{userId} AND client_message_id=#{clientId}")
    GroupMessageEntity findDuplicate(@Param("userId") Long userId, @Param("clientId") String clientId);

    @Update("UPDATE group_chat SET last_seq=#{seq}, last_message_id=#{messageId}, last_message_at=#{createdAt} WHERE group_id=#{groupId}")
    int updateHead(GroupMessageEntity message);
}
