package org.example.relation.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 好友关系表 {@code relation_friend} 的实体。
 *
 * <p><b>一条好友关系在表里是两行</b>（A→B 和 B→A）。
 * 所以"我的好友列表"就是 {@code where user_id = 我}，
 * 而 {@code friend_id} 才是"对方是谁"。这两个字段千万别写反 ——
 * 写反的后果是"加了别人但自己列表里看不到"。
 *
 * <p>{@code remark} 是<b>这一行的所有者</b>给对方起的备注：
 * 我这一行存的是"我给对方起的名字"，对方那一行存的是他的。
 *
 * <p>{@code createdAt} / {@code updatedAt} 由数据库自己维护
 * （建表时有 DEFAULT CURRENT_TIMESTAMP，改备注时 ON UPDATE 自动刷新），
 * 代码里<b>不设置</b>这两个字段 —— MyBatis-Plus 默认跳过 null 字段，所以不会覆盖数据库的值。
 */
@Data
@TableName("relation_friend")
public class FriendEntity {

    /**
     * 主键。<b>列名是 friend_relation_id，不是 id</b> ——
     * 单一个 {@code id} 看不出来是哪张表的 id，日志和慢查询里出现时还得回来猜；
     * 名字里带上"这张表是干什么的"就一眼清楚了（和 user 表的 user_id 一个规矩）。
     */
    @TableId(value = "friend_relation_id", type = IdType.AUTO)
    private Long friendRelationId;

    /** 这一行的所有者（我） */
    private Long userId;

    /** 我的好友（他） */
    private Long friendId;

    /** 我给对方起的备注；没设置就是 null */
    private String remark;

    /** 成为好友的时间（数据库维护） */
    private LocalDateTime createdAt;

    /** 这一行最后改动的时间（数据库维护） */
    private LocalDateTime updatedAt;
}
