package org.example.relation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.relation.entity.FriendRequestEntity;
import org.example.relation.mapper.FriendRequestMapper;
import org.example.relation.mapper.GroupInvitationMapper;
import org.example.relation.vo.RequestReminderVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RequestReminderService {
    private final FriendRequestMapper friends;
    private final GroupInvitationMapper groups;

    @Transactional(readOnly = true)
    public RequestReminderVO summary(Long user) {
        long friendCount = friends.selectCount(Wrappers.<FriendRequestEntity>lambdaQuery()
                .eq(FriendRequestEntity::getToUserId, user)
                .eq(FriendRequestEntity::getStatus, "PENDING"));
        return new RequestReminderVO(friendCount, groups.pendingCount(user));
    }
}
