package org.example.relation.mapper;

import java.util.List;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import org.example.relation.entity.GroupEntity;

@Mapper
public interface GroupMapper extends BaseMapper<GroupEntity> {
    @Select("SELECT * FROM group_chat WHERE group_id=#{id} FOR UPDATE")
    GroupEntity lock(@Param("id") Long id);

    @Select("SELECT * FROM group_chat WHERE created_by_user_id=#{user} AND client_request_id=#{request}")
    GroupEntity findCreated(@Param("user") Long user, @Param("request") String request);

    @Select("SELECT g.* FROM group_member m JOIN group_chat g ON g.group_id=m.group_id " +
            "WHERE m.user_id=#{user} AND m.member_status=1 AND g.group_status=1 " +
            "AND m.group_id>#{after} ORDER BY m.group_id LIMIT 50")
    List<GroupEntity> mine(@Param("user") Long user, @Param("after") long after);

    @Update("UPDATE group_chat SET member_count=member_count+1 WHERE group_id=#{id} " +
            "AND group_status=1 AND member_count<max_members")
    int incrementMembers(@Param("id") Long id);
}
