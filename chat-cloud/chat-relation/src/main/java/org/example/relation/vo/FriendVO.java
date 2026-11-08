package org.example.relation.vo;

import java.time.LocalDate;

import org.example.common.dto.UserProfileDTO;
import org.example.relation.entity.FriendEntity;

/**
 * 好友列表里的一项 —— <b>用户卡片 + 关系域特有的字段</b>。
 *
 * <p>字段和 chat-user 的 {@code UserCardVO}（userId / nickName / avatarUrl / gender / birthDate）
 * 对齐，外加关系域自己的 {@code remark}。前端就按这一套渲染好友列表。
 *
 * <p><b>为什么没有 username（登录名）？</b> 和搜索卡片一样：列表是最小信息，
 * 登录名只在"点进去看详情"时给。要看详情前端会拿 userId 去调 chat-user。
 *
 * <p><b>为什么没有首字母 {@code initial}？</b> 中文要算拼音首字母得有拼音库
 * （多一个依赖）。现在的做法是前端自己按首字兜底：英文取首字母、其它落到 {@code #} 组，
 * 而<b>排序</b>用的是浏览器自带的 {@code Intl.Collator('zh-Hans-CN')}，中文仍然按拼音排 ——
 * 也就是"顺序是对的，只是分组标题粗一点"。真要精确分组时再加库、加列。
 *
 * @param userId    好友的 userId（来自关系表的 friend_id，不是用户资料里的）
 * @param nickName  昵称
 * @param avatarUrl 头像
 * @param gender    性别 M/F
 * @param birthDate 生日
 * @param remark    我给对方起的备注；没设置就是 null（前端显示名优先用它）
 */
public record FriendVO(
        Long userId,
        String nickName,
        String avatarUrl,
        String gender,
        LocalDate birthDate,
        String remark
) {

    /**
     * 关系行 + 用户资料 → 前端要的好友项。
     *
     * <p>两个参数的角色不一样，别搞混：
     * <ul>
     *   <li>{@code relation} —— <b>必须有</b>，好友是谁（friend_id）和你给他起的备注都在这里</li>
     *   <li>{@code profile} —— 可以缺（chat-user 那边没查到，比如用户被删了）：
     *       这时 userId 照样是对的，只是名字头像为空。
     *       <b>不能让整个列表因为一个人查不到就失败</b></li>
     * </ul>
     */
    public static FriendVO of(FriendEntity relation, UserProfileDTO profile) {
        return new FriendVO(
                relation.getFriendId(),
                profile == null ? null : profile.nickName(),
                profile == null ? null : profile.avatarUrl(),
                profile == null ? null : profile.gender(),
                profile == null ? null : profile.birthDate(),
                relation.getRemark());
    }
}
