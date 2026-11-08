package org.example.relation.constant;

/**
 * 好友申请的状态值（存进 {@code relation_friend_request.status}）。
 *
 * <p>用字符串常量而不是魔法数字：数据库里直接看得懂，
 * 排查时不用回来翻"0 和 1 到底哪个是同意"。
 *
 * <p>⚠️ 这三个值要和建表语句里的注释保持一致 ——
 * 改名字必须同时改数据库里已有的数据（或者写迁移脚本把旧值刷成新值）。
 */
public final class FriendRequestStatus {

    /** 待处理：申请已经发出，等对方同意/拒绝 */
    public static final String PENDING = "PENDING";

    /** 已同意：这一刻两人成为好友（关系表里已经写了两行） */
    public static final String ACCEPTED = "ACCEPTED";

    /** 已拒绝：对方拒绝了。申请方可以再次申请（把这一行改回 PENDING） */
    public static final String REJECTED = "REJECTED";

    private FriendRequestStatus() {
    }
}
