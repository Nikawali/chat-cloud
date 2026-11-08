package org.example.relation.mapper;

import java.util.List;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import org.example.relation.entity.GroupInvitationEntity;

@Mapper
public interface GroupInvitationMapper extends BaseMapper<GroupInvitationEntity> {
    @Select("SELECT COUNT(*) FROM group_invitation i JOIN group_chat g ON g.group_id=i.group_id " +
            "WHERE i.invitee_user_id=#{user} AND i.invitation_status='PENDING' " +
            "AND i.expires_at>CURRENT_TIMESTAMP(6) AND g.group_status=1")
    long pendingCount(@Param("user") Long user);

    @Select("SELECT * FROM group_invitation WHERE group_id=#{group} AND invitee_user_id=#{user}")
    GroupInvitationEntity find(@Param("group") Long group, @Param("user") Long user);

    @Select("SELECT i.* FROM group_invitation i JOIN group_chat g ON g.group_id=i.group_id " +
            "WHERE i.invitee_user_id=#{user} AND i.invitation_status='PENDING' " +
            "AND i.expires_at>CURRENT_TIMESTAMP(6) AND g.group_status=1 " +
            "AND i.invitation_id>#{after} ORDER BY i.invitation_id LIMIT 50")
    List<GroupInvitationEntity> pending(@Param("user") Long user, @Param("after") long after);

    @Update("UPDATE group_invitation SET invitation_status='ACCEPTED',handled_at=CURRENT_TIMESTAMP(6) " +
            "WHERE group_id=#{group} AND invitee_user_id=#{user} AND invitation_status='PENDING'")
    int acceptPending(@Param("group") Long group, @Param("user") Long user);

}
