package org.example.relation.service;

import java.util.List;
import org.example.relation.dto.CreateGroupRequest;
import org.example.relation.vo.GroupInvitationVO;
import org.example.relation.vo.GroupMemberVO;
import org.example.relation.vo.GroupVO;

public interface GroupService {
    GroupVO create(Long user, CreateGroupRequest request);
    List<GroupVO> mine(Long user, long after);
    List<GroupVO> search(Long user, String keyword);
    GroupVO detail(Long user, Long group);
    GroupVO join(Long user, Long group);
    int invite(Long user, Long group, List<Long> targets);
    List<GroupInvitationVO> invitations(Long user, long after);
    long invitationCount(Long user);
    GroupVO accept(Long user, Long invitation);
    void reject(Long user, Long invitation);
    List<GroupMemberVO> members(Long user, Long group, long after);
}
