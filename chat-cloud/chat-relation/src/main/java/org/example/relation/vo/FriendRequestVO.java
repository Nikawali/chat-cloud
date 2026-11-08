package org.example.relation.vo;

import java.time.LocalDateTime;

import org.example.common.dto.UserProfileDTO;
import org.example.relation.constant.FriendRequestStatus;
import org.example.relation.entity.FriendRequestEntity;

/**
 * 「新的朋友」列表里的一项 —— 别人申请加我的记录。
 *
 * <p>展示需要三样东西：<b>是谁申请的</b>（名字+头像）、<b>他说了什么</b>（验证消息）、
 * <b>什么时候</b>。前两样来自 chat-user（本服务不存用户资料），后两样来自申请表。
 *
 * @param friendRequestId 申请记录的 id —— 同意/拒绝时要传它回去
 *                        （<b>不是</b> {@code fromUserId}：那是申请人的用户 id，两个 id 很容易混，
 *                         所以这里也不叫含糊的 {@code id}）
 * @param fromUserId 申请人的 userId（点了资料页要用它）
 * @param nickName  申请人的昵称
 * @param avatarUrl 申请人的头像
 * @param message   验证消息，可能为空
 * @param status    状态，见 {@code FriendRequestStatus}（列表里通常只会出现 PENDING）
 * @param createdAt 申请时间
 */
public record FriendRequestVO(
        Long friendRequestId,
        Long fromUserId,
        String nickName,
        String avatarUrl,
        String message,
        String status,
        LocalDateTime createdAt
) {

    /** 申请行 + 申请人资料 → 前端要的一项 */
    public static FriendRequestVO of(FriendRequestEntity request, UserProfileDTO profile) {
        return new FriendRequestVO(
                request.getFriendRequestId(),
                request.getFromUserId(),
                profile == null ? null : profile.nickName(),
                profile == null ? null : profile.avatarUrl(),
                request.getMessage(),
                request.getStatus() == null ? FriendRequestStatus.PENDING : request.getStatus(),
                request.getCreatedAt());
    }
}
