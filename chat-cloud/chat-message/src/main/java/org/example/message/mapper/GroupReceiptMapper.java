package org.example.message.mapper;
import org.apache.ibatis.annotations.*;
import org.example.common.dto.GroupReceiptState;
import java.util.List;
@Mapper
public interface GroupReceiptMapper {
 record Member(Long memberVersion,Long visibleFromSeq,Long visibleToSeq,Long readSeq) {}
 @Select("SELECT member_version,visible_from_seq,visible_to_seq,read_seq FROM group_member WHERE group_id=#{groupId} AND user_id=#{userId} AND member_status=1 FOR UPDATE")
 Member lock(@Param("groupId") Long groupId,@Param("userId") Long userId);
 @Select("SELECT member_version,visible_from_seq,visible_to_seq,read_seq FROM group_member WHERE group_id=#{groupId} AND user_id=#{userId} AND member_status=1")
 Member find(@Param("groupId") Long groupId,@Param("userId") Long userId);
 @Select("SELECT received_seq FROM group_device_sync WHERE group_id=#{groupId} AND user_id=#{userId} AND device_id=#{deviceId} AND member_version=#{version}")
 Long received(@Param("groupId") Long groupId,@Param("userId") Long userId,@Param("deviceId") String deviceId,@Param("version") Long version);
 @Select("SELECT COALESCE(MAX(received_seq),0) FROM group_device_sync WHERE group_id=#{groupId} AND user_id=#{userId} AND member_version=#{version}")
 long receivedAcrossDevices(@Param("groupId") Long groupId,@Param("userId") Long userId,@Param("version") Long version);
 @Insert("INSERT INTO group_device_sync(group_id,user_id,device_id,member_version,received_seq) VALUES(#{groupId},#{userId},#{deviceId},#{version},#{seq}) ON DUPLICATE KEY UPDATE received_seq=CASE WHEN member_version=VALUES(member_version) THEN GREATEST(received_seq,VALUES(received_seq)) ELSE VALUES(received_seq) END,member_version=VALUES(member_version)")
 void receive(@Param("groupId") Long groupId,@Param("userId") Long userId,@Param("deviceId") String deviceId,@Param("version") Long version,@Param("seq") Long seq);
 @Update("UPDATE group_member SET read_updated_at=CASE WHEN read_seq<#{seq} THEN CURRENT_TIMESTAMP ELSE read_updated_at END,read_seq=GREATEST(read_seq,#{seq}) WHERE group_id=#{groupId} AND user_id=#{userId}")
 void read(@Param("groupId") Long groupId,@Param("userId") Long userId,@Param("seq") Long seq);
 @Select("SELECT m.user_id,m.member_version,m.visible_from_seq,COALESCE(MAX(d.received_seq),0) AS received_seq,m.read_seq FROM group_member m LEFT JOIN group_device_sync d ON d.group_id=m.group_id AND d.user_id=m.user_id AND d.member_version=m.member_version WHERE m.group_id=#{groupId} AND m.member_status=1 GROUP BY m.user_id,m.member_version,m.visible_from_seq,m.read_seq")
 List<GroupReceiptState.Member> states(Long groupId);
 @Select("SELECT m.user_id,m.member_version,m.visible_from_seq,COALESCE(MAX(d.received_seq),0) AS received_seq,m.read_seq FROM group_member m LEFT JOIN group_device_sync d ON d.group_id=m.group_id AND d.user_id=m.user_id AND d.member_version=m.member_version WHERE m.group_id=#{groupId} AND m.user_id=#{userId} AND m.member_status=1 GROUP BY m.user_id,m.member_version,m.visible_from_seq,m.read_seq")
 List<GroupReceiptState.Member> ownState(@Param("groupId") Long groupId,@Param("userId") Long userId);
 /** Only active authors whose visible messages are covered by a newly advanced watermark. */
 @Select("SELECT DISTINCT msg.sender_user_id FROM group_message msg " +
   "JOIN group_member author ON author.group_id=msg.group_id AND author.user_id=msg.sender_user_id " +
   "JOIN group_chat g ON g.group_id=msg.group_id " +
   "WHERE msg.group_id=#{groupId} AND g.group_status=1 AND author.member_status=1 " +
   "AND msg.sender_user_id<>#{reporter} AND msg.sender_user_id>#{afterUser} " +
   "AND msg.seq>=author.visible_from_seq AND (author.visible_to_seq IS NULL OR msg.seq<=author.visible_to_seq) " +
   "AND ((msg.seq>#{receivedAfter} AND msg.seq<=#{receivedUpTo}) OR (msg.seq>#{readAfter} AND msg.seq<=#{readUpTo})) " +
   "ORDER BY msg.sender_user_id LIMIT 100")
 List<Long> affectedSenders(@Param("groupId") Long groupId,@Param("reporter") Long reporter,
   @Param("receivedAfter") long receivedAfter,@Param("receivedUpTo") long receivedUpTo,
   @Param("readAfter") long readAfter,@Param("readUpTo") long readUpTo,@Param("afterUser") long afterUser);
}
