package org.example.relation.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 好友申请表 {@code relation_friend_request} 的实体。
 *
 * <p>和关系表不同，申请<b>只有一行</b>：申请天然是单向的，
 * 所以有明确的"谁申请谁"——{@code fromUserId} 是发起人，{@code toUserId} 是要处理的人。
 *
 * <p><b>表上有唯一索引 (from_user_id, to_user_id)</b>，所以同一个方向只有一行：
 * 被拒绝后重新申请是<b>改这一行</b>（状态回到 PENDING），不是插新行。
 * 这让"我申请过谁"的查询永远只命中一行，不用去重。
 */
@Data
@TableName("relation_friend_request")
public class FriendRequestEntity {

    /**
     * 主键。<b>列名是 friend_request_id，不是 id</b>：
     * 这个值是<b>前端同意/拒绝时要传回来的那个 id</b>，
     * 而列表里同时还有 {@code fromUserId}（人的 id）—— 两个 id 摆在一起时，
     * 一个笼统的 {@code id} 特别容易让人传错，名字必须写清楚。
     */
    @TableId(value = "friend_request_id", type = IdType.AUTO)
    private Long friendRequestId;

    /** 申请人（谁发起的） */
    private Long fromUserId;

    /** 被申请人（要处理这条申请的人，也是唯一有权限同意/拒绝的人） */
    private Long toUserId;

    /** 验证消息，可以为 null */
    private String message;

    /** 状态：见 {@code FriendRequestStatus}（PENDING / ACCEPTED / REJECTED） */
    private String status;

    /** 第一次申请的时间（数据库维护） */
    private LocalDateTime createdAt;

    /** 最后变动时间（数据库维护） */
    private LocalDateTime updatedAt;

    /** 同意/拒绝的时间；还没处理就是 null */
    private LocalDateTime handledAt;
}
