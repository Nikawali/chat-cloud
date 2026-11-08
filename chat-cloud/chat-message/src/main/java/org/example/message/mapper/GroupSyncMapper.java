package org.example.message.mapper;
import org.apache.ibatis.annotations.*;
import java.util.List;
import org.example.message.entity.GroupMessageEntity;
@Mapper
public interface GroupSyncMapper {
 record Head(Long groupId,String groupName,String avatarUrl,Integer memberRole,Integer memberCount,Long memberVersion,Long visibleFromSeq,Long lastSeq) {}
 @Select("SELECT g.group_id,g.group_name,g.avatar_url,m.member_role,g.member_count,m.member_version,m.visible_from_seq,LEAST(g.last_seq,COALESCE(m.visible_to_seq,g.last_seq)) AS last_seq FROM group_member m JOIN group_chat g ON g.group_id=m.group_id WHERE m.user_id=#{userId} AND m.member_status=1 AND g.group_status=1 AND g.group_id>#{after} ORDER BY g.group_id LIMIT 100")
 List<Head> heads(@Param("userId") Long userId,@Param("after") long after);
 @Select("SELECT * FROM group_message WHERE group_id=#{groupId} AND seq>#{after} AND seq<=#{until} ORDER BY seq LIMIT 100")
 List<GroupMessageEntity> page(@Param("groupId") Long groupId,@Param("after") long after,@Param("until") long until);
}
