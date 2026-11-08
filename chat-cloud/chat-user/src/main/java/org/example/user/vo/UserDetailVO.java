package org.example.user.vo;

import java.time.LocalDate;

import org.example.common.dto.UserProfileDTO;

/**
 * 用户详情 —— <b>「点进去看某个人」场景</b>返回给前端的完整资料。
 *
 * <p>用在哪：{@code GET /user/{id}}（看别人的资料）、{@code GET /user/me}（看自己）、
 * {@code PUT /user/profile}（改完资料顺手把最新的返回给前端刷新界面）。
 *
 * <p>和 {@link UserCardVO} 的差别只有一个 {@code username}（登录名/真名）：
 * 列表卡片上不给它，点进详情才给。详情页要让人确认"这就是我要找的那个人"，
 * 昵称可以重复、可以为空、可以随时改，只有登录名是稳定的。
 *
 * <p><b>依然不含 updatedAt</b>（缓存版本号，内部实现）<b>和 phone</b>（敏感信息，
 * 而且"看别人主页"本来就不该看到对方的手机号）。
 *
 * @param userId    用户 id
 * @param username  登录用户名（真名）
 * @param nickName  昵称（可能为 null）
 * @param avatarUrl 头像地址（可能为 null）
 * @param gender    性别：M / F，未填为 null
 * @param birthDate 出生日期，形如 {@code 2000-01-01}，未填为 null
 */
public record UserDetailVO(
        Long userId,
        String username,
        String nickName,
        String avatarUrl,
        String gender,
        LocalDate birthDate
) {

    /**
     * 内部资料 → 对外详情。
     *
     * @param profile 内部资料；为 null 时返回 null
     */
    public static UserDetailVO from(UserProfileDTO profile) {
        if (profile == null) {
            return null;
        }
        return new UserDetailVO(
                profile.userId(),
                profile.username(),
                profile.nickName(),
                profile.avatarUrl(),
                profile.gender(),
                profile.birthDate());
    }
}
