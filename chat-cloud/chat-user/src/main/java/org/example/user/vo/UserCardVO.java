package org.example.user.vo;

import java.time.LocalDate;

import org.example.common.dto.UserProfileDTO;

/**
 * 用户卡片 —— <b>「列表 / 搜索」场景</b>返回给前端的最小信息。
 *
 * <p>用在哪：搜人（{@code GET /user/search}）、以后的好友列表。
 * 这些场景一屏要画几十个人，只需要"够画一行"的字段。
 *
 * <p><b>为什么单独做一个 VO，而不是直接把内部 DTO 返回出去？</b>
 * 因为内部 DTO（{@link UserProfileDTO}）里带着 {@code updatedAt} ——
 * 那是<b>缓存版本号</b>（Redis 里的 CAS 用它比较新旧），纯粹的实现细节，
 * 泄露给前端没有意义，还容易被人误当成业务字段。VO 这一层就是一道"只给该给的"的闸门。
 *
 * <p><b>为什么没有 username（真名）？</b>
 * 登录名只在"点进去看某个人"（{@link UserDetailVO}）时才给：
 * 列表卡片保持最小，以后要给列表加字段也会先想清楚"列表真的需要吗"，
 * 不会因为详情字段变多而让列表接口悄悄变肥。
 *
 * <p><b>为什么没有 phone？</b> 手机号是敏感信息，不该在响应里到处传；
 * 而且搜人的界面里，用户本来就知道自己输的是什么号码，回显没有价值。
 *
 * <p><b>以后加字段的规矩</b>：列表要用的加到这张卡片上；只有详情要用的加到
 * {@link UserDetailVO}；内部/缓存要用的加到 {@link UserProfileDTO}。
 * 三条线分清楚，就不会出现"列表接口返回一堆用不上的字段"。
 *
 * @param userId    用户 id。全项目统一叫 userId（不是 id）
 * @param nickName  昵称（可能为 null）
 * @param avatarUrl 头像地址（可能为 null，界面用昵称首字兜底）
 * @param gender    性别：M / F，未填为 null
 * @param birthDate 出生日期，形如 {@code 2000-01-01}，未填为 null
 */
public record UserCardVO(
        Long userId,
        String nickName,
        String avatarUrl,
        String gender,
        LocalDate birthDate
) {

    /**
     * 内部资料 → 对外卡片。
     *
     * <p>写成静态工厂而不是让 service 直接返回 VO：service 那边还要拿
     * {@code updatedAt} 去写缓存，不能把版本号丢掉；转换这一步天然属于"出口"，
     * 放在 controller 调用的地方最清楚。
     *
     * @param profile 内部资料；为 null 时返回 null
     */
    public static UserCardVO from(UserProfileDTO profile) {
        if (profile == null) {
            return null;
        }
        return new UserCardVO(
                profile.userId(),
                profile.nickName(),
                profile.avatarUrl(),
                profile.gender(),
                profile.birthDate());
    }
}
