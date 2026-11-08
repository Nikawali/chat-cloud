package org.example.relation.mapper;

import java.util.List;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import org.example.relation.entity.GroupMemberEntity;

@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMemberEntity> {
    @Delete("DELETE FROM group_device_sync WHERE group_id=#{group} AND user_id=#{user}")
    int resetDevices(@Param("group") Long group, @Param("user") Long user);

    @Select("SELECT * FROM group_member WHERE group_id=#{group} AND user_id=#{user}")
    GroupMemberEntity find(@Param("group") Long group, @Param("user") Long user);

    @Select("SELECT * FROM group_member WHERE group_id=#{group} AND member_status=1 " +
            "AND user_id>#{after} ORDER BY user_id LIMIT 100")
    List<GroupMemberEntity> page(@Param("group") Long group, @Param("after") long after);

    @Update("UPDATE group_member SET member_status=1,member_role=1,member_version=member_version+1," +
            "visible_from_seq=#{baseline}+1,visible_to_seq=NULL,read_seq=#{baseline},cleared_to_seq=0," +
            "read_updated_at=NULL,group_nickname=NULL,muted_until=NULL,joined_at=CURRENT_TIMESTAMP(6),left_at=NULL " +
            "WHERE group_id=#{group} AND user_id=#{user} AND member_status<>1")
    int rejoin(@Param("group") Long group, @Param("user") Long user, @Param("baseline") long baseline);
}
