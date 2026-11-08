package org.example.relation.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.relation.service.GroupService;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.common.dto.UserBatchRequest;
import org.example.common.dto.UserProfileDTO;
import org.example.common.exception.BizException;
import org.example.common.support.InternalAuth;
import org.example.relation.client.UserClient;
import org.example.relation.dto.CreateGroupRequest;
import org.example.relation.entity.*;
import org.example.relation.mapper.*;
import org.example.relation.vo.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {
    private final GroupMapper groups;
    private final GroupMemberMapper members;
    private final GroupInvitationMapper invites;
    private final FriendMapper friends;
    private final UserClient users;
    private final TransactionTemplate transactions;

    @Override
    public GroupVO create(Long user, CreateGroupRequest request) {
        String name = request.groupName().trim();
        if (name.isEmpty()) throw new BizException(400, "群名称不能为空");
        var existing = groups.findCreated(user, request.clientRequestId());
        if (existing != null) return sameCreation(user, existing, name);
        try {
            return transactions.execute(status -> {
                var group = new GroupEntity();
                group.setGroupName(name);
                group.setOwnerUserId(user);
                group.setCreatedByUserId(user);
                group.setClientRequestId(request.clientRequestId());
                group.setGroupStatus(1);
                group.setMemberCount(1);
                group.setMaxMembers(1000);
                group.setLastSeq(0L);
                groups.insert(group);
                insertMember(group, user, 3);
                return view(groups.selectById(group.getGroupId()), 3);
            });
        } catch (DuplicateKeyException e) {
            // 在事务回滚后重新查询，避免并发创建时读到旧快照。
            existing = groups.findCreated(user, request.clientRequestId());
            if (existing == null) throw e;
            return sameCreation(user, existing, name);
        }
    }

    private GroupVO sameCreation(Long user, GroupEntity group, String name) {
        if (!name.equals(group.getGroupName())) throw new BizException(409, "同一创建请求不能更换群名称");
        requireActive(group);
        return view(group, role(members.find(group.getGroupId(), user)));
    }

    @Override
    public List<GroupVO> mine(Long user, long after) {
        return views(user, groups.mine(user, after));
    }

    @Override
    public List<GroupVO> search(Long user, String keyword) {
        String query = keyword == null ? "" : keyword.trim();
        if (!query.matches("[1-9][0-9]{0,18}")) throw new BizException(400, "请输入正确、完整的群 ID，仅支持数字精确查询");
        long id;
        try {
            id = Long.parseLong(query);
        } catch (NumberFormatException e) {
            throw new BizException(400, "群 ID 超出有效范围");
        }
        var group = groups.selectById(id);
        return group == null || group.getGroupStatus() != 1 ? List.of() : views(user, List.of(group));
    }

    @Override
    public GroupVO detail(Long user, Long group) {
        var row = groups.selectById(group);
        requireActive(row);
        return view(row, role(members.find(group, user)));
    }

    @Override
    public GroupVO join(Long user, Long groupId) {
        return transactions.execute(status -> {
            var group = locked(groupId);
            joinLocked(group, user);
            // 搜索加入和邀请接受共用入群规则；已存在的待处理邀请一并结束。
            invites.acceptPending(groupId, user);
            return view(group, role(members.find(groupId, user)));
        });
    }

    private void joinLocked(GroupEntity group, Long user) {
        var old = members.find(group.getGroupId(), user);
        if (role(old) != null) return; // 已在群内，重复加入不改人数和游标。
        if (old != null && old.getMemberStatus() == 3) throw new BizException(403, "你已被移出该群，暂不能重新加入");
        if (groups.incrementMembers(group.getGroupId()) != 1) throw new BizException(409, "群成员已满");
        if (old == null) insertMember(group, user, 1);
        else {
            if (members.rejoin(group.getGroupId(), user, group.getLastSeq()) != 1) {
                throw new BizException(409, "成员状态已变化，请重试");
            }
            members.resetDevices(group.getGroupId(), user);
        }
        group.setMemberCount(group.getMemberCount() + 1);
    }

    private void insertMember(GroupEntity group, Long user, int role) {
        var member = new GroupMemberEntity();
        member.setGroupId(group.getGroupId());
        member.setUserId(user);
        member.setMemberRole(role);
        member.setMemberStatus(1);
        member.setMemberVersion(1L);
        member.setVisibleFromSeq(group.getLastSeq() + 1);
        member.setReadSeq(group.getLastSeq());
        members.insert(member);
    }

    @Override
    public int invite(Long user, Long groupId, List<Long> targets) {
        if (targets == null || targets.isEmpty() || targets.size() > 50
                || targets.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BizException(400, "每次请选择1至50位好友");
        }
        var unique = new LinkedHashSet<>(targets);
        return transactions.execute(status -> {
            var group = locked(groupId);
            requireManager(group, user);
            if (group.getMemberCount() >= group.getMaxMembers()) throw new BizException(409, "群成员已满");
            var friendIds = friends.selectList(Wrappers.<FriendEntity>lambdaQuery()
                    .eq(FriendEntity::getUserId, user).in(FriendEntity::getFriendId, unique))
                    .stream().map(FriendEntity::getFriendId).collect(Collectors.toSet());
            if (!friendIds.containsAll(unique)) throw new BizException(403, "只能邀请自己的好友");
            int count = 0;
            var now = LocalDateTime.now();
            for (Long target : unique) {
                var member = members.find(groupId, target);
                if (role(member) != null) continue;
                if (member != null && member.getMemberStatus() == 3) throw new BizException(403, "不能邀请已被移除的成员");
                var invitation = invites.find(groupId, target);
                if (invitation != null && "PENDING".equals(invitation.getInvitationStatus())
                        && invitation.getExpiresAt().isAfter(now)) continue;
                if (invitation != null && "REJECTED".equals(invitation.getInvitationStatus())
                        && invitation.getHandledAt() != null && invitation.getHandledAt().isAfter(now.minusDays(1))) {
                    throw new BizException(409, "对方已拒绝邀请，请24小时后再试");
                }
                boolean fresh = invitation == null;
                if (fresh) invitation = new GroupInvitationEntity();
                invitation.setGroupId(groupId);
                invitation.setInviterUserId(user);
                invitation.setInviteeUserId(target);
                invitation.setInvitationStatus("PENDING");
                invitation.setCreatedAt(now);
                invitation.setExpiresAt(now.plusDays(7));
                invitation.setHandledAt(null);
                if (fresh) invites.insert(invitation);
                else {
                    // updateById默认跳过null，显式清除上次处理时间。
                    invites.update(invitation, Wrappers.<GroupInvitationEntity>lambdaUpdate()
                            .eq(GroupInvitationEntity::getInvitationId, invitation.getInvitationId())
                            .set(GroupInvitationEntity::getHandledAt, null));
                }
                count++;
            }
            return count;
        });
    }

    @Override
    public List<GroupInvitationVO> invitations(Long user, long after) {
        var rows = invites.pending(user, after);
        if (rows.isEmpty()) return List.of();
        var byGroup = groups.selectBatchIds(rows.stream().map(GroupInvitationEntity::getGroupId).distinct().toList())
                .stream().collect(Collectors.toMap(GroupEntity::getGroupId, g -> g));
        var profiles = profiles(rows.stream().map(GroupInvitationEntity::getInviterUserId).distinct().toList());
        return rows.stream().map(i -> new GroupInvitationVO(i.getInvitationId(), i.getGroupId(),
                byGroup.containsKey(i.getGroupId()) ? byGroup.get(i.getGroupId()).getGroupName() : "群聊",
                i.getInviterUserId(), nickname(profiles.get(i.getInviterUserId()), i.getInviterUserId()), i.getExpiresAt())).toList();
    }

    @Override
    public long invitationCount(Long user) {
        return invites.pendingCount(user);
    }

    @Override
    public GroupVO accept(Long user, Long invitationId) {
        var initial = invitationFor(user, invitationId);
        return transactions.execute(status -> {
            var group = locked(initial.getGroupId());
            // 首次快照读放在群锁之后，避免REPEATABLE READ复用等待锁之前的邀请状态。
            var invitation = invitationFor(user, invitationId);
            if ("ACCEPTED".equals(invitation.getInvitationStatus())) {
                var role = role(members.find(group.getGroupId(), user));
                if (role == null) throw new BizException(409, "邀请已处理，你当前不在群内");
                return view(group, role);
            }
            requirePending(invitation);
            requireManager(group, invitation.getInviterUserId());
            joinLocked(group, user);
            invites.acceptPending(group.getGroupId(), user);
            return view(group, role(members.find(group.getGroupId(), user)));
        });
    }

    @Override
    public void reject(Long user, Long invitationId) {
        var initial = invitationFor(user, invitationId);
        transactions.executeWithoutResult(status -> {
            locked(initial.getGroupId());
            var invitation = invitationFor(user, invitationId);
            if ("REJECTED".equals(invitation.getInvitationStatus())) return;
            requirePending(invitation);
            invites.update(null, Wrappers.<GroupInvitationEntity>lambdaUpdate()
                    .eq(GroupInvitationEntity::getInvitationId, invitationId)
                    .set(GroupInvitationEntity::getInvitationStatus, "REJECTED")
                    .set(GroupInvitationEntity::getHandledAt, LocalDateTime.now()));
        });
    }

    @Override
    public List<GroupMemberVO> members(Long user, Long groupId, long after) {
        requireActive(groups.selectById(groupId));
        if (role(members.find(groupId, user)) == null) throw new BizException(403, "加入群聊后才能查看成员");
        var rows = members.page(groupId, after);
        if (rows.isEmpty()) return List.of();
        var profiles = profiles(rows.stream().map(GroupMemberEntity::getUserId).toList());
        return rows.stream().map(m -> {
            var profile = profiles.get(m.getUserId());
            return new GroupMemberVO(m.getUserId(), nickname(profile, m.getUserId()),
                    profile == null ? null : profile.avatarUrl(), m.getMemberRole());
        }).toList();
    }

    private Map<Long, UserProfileDTO> profiles(List<Long> ids) {
        var result = InternalAuth.requireSuccess(users.batch(new UserBatchRequest(ids)));
        if (result == null) throw new BizException(503, "用户资料暂时不可用");
        return result.stream().collect(Collectors.toMap(UserProfileDTO::userId, p -> p));
    }
    private static String nickname(UserProfileDTO p, Long id) {
        return p == null ? "用户 " + id : p.nickName();
    }
    private GroupInvitationEntity invitationFor(Long user, Long id) {
        var row = invites.selectById(id);
        if (row == null || !user.equals(row.getInviteeUserId())) throw new BizException(404, "邀请不存在");
        return row;
    }
    private static void requirePending(GroupInvitationEntity row) {
        if (!"PENDING".equals(row.getInvitationStatus())) throw new BizException(409, "邀请已处理");
        if (!row.getExpiresAt().isAfter(LocalDateTime.now())) throw new BizException(409, "邀请已过期");
    }
    private GroupEntity locked(Long id) {
        var row = groups.lock(id);
        requireActive(row);
        return row;
    }
    private static void requireActive(GroupEntity row) {
        if (row == null || row.getGroupStatus() != 1) throw new BizException(404, "群不存在或已解散");
    }
    private void requireManager(GroupEntity group, Long user) {
        Integer role = role(members.find(group.getGroupId(), user));
        if (role == null || role < 2) throw new BizException(403, "只有群主或管理员可以邀请好友");
    }
    private List<GroupVO> views(Long user, List<GroupEntity> rows) {
        if (rows.isEmpty()) return List.of();
        var mine = members.selectList(Wrappers.<GroupMemberEntity>lambdaQuery()
                .eq(GroupMemberEntity::getUserId, user)
                .in(GroupMemberEntity::getGroupId, rows.stream().map(GroupEntity::getGroupId).toList()))
                .stream().collect(Collectors.toMap(GroupMemberEntity::getGroupId, m -> m));
        return rows.stream().map(g -> view(g, role(mine.get(g.getGroupId())))).toList();
    }
    private static Integer role(GroupMemberEntity row) {
        return row != null && row.getMemberStatus() == 1 ? row.getMemberRole() : null;
    }
    private static GroupVO view(GroupEntity group, Integer role) {
        return new GroupVO(group.getGroupId(), group.getGroupName(), group.getAvatarUrl(), group.getOwnerUserId(),
                group.getMemberCount(), group.getMaxMembers(), role, group.getCreatedAt());
    }
}
