package org.example.user.cache;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.example.common.constant.CacheKeys;
import org.example.common.dto.UserProfileDTO;

/**
 * 用户资料缓存的<b>格式转换器</b>（DTO ⇄ Redis HASH 的字段），读写两个方向都在这一个类里。
 *
 * <p><b>为什么放在 chat-user，而不是 chat-common？</b>
 * 因为用户资料缓存<b>只有一个写入方</b>：chat-user 自己
 * （登录预热、读未命中回填、改资料写新值，三处都在这个服务里）。
 * 缓存格式是 chat-user 的<b>实现细节</b> —— 字段名、TTL、CAS 脚本都只出现在它自己的代码里，
 * 别的服务既看不到也不需要知道。将来想把 HASH 换成 JSON、或者加一层本地缓存，只改这一个服务。
 *
 * <p>（曾经它放在 chat-common，因为当时 chat-auth 登录成功时也要写这个缓存，
 * 两个服务必须用同一份编码。现在预热挪回 chat-user 内部了，共享的理由就没有了。）
 *
 * <p>把「怎么编码」和「怎么解码」放在同一个类里，还有一个额外好处：
 * <b>读和写是两个互逆的函数，写在一起才能一眼看出它们是否对称。</b>
 * （比如编码时 {@code birthDate} 存 ISO 字符串，解码时就必须按 ISO 解析。）
 *
 * <p><b>注意这个类不依赖任何 Redis 类</b>，只处理 {@code Map} ——
 * 所以它可以脱离 Redis 单独测试，也说明它只管"格式"这一件事。
 */
public final class UserProfileCacheCodec {

    /**
     * 把用户资料编码成 Redis HASH 的字段表。
     *
     * <p><b>值为 null 的字段不会写进去</b>（不是写成字符串 "null"）。
     * 读的时候 {@code HGET} 拿不到，语义正好好就是"这个字段没设置"。
     *
     * @param profile 用户资料
     * @return 字段名 → 字段值
     */
    public static Map<String, String> toHashFields(UserProfileDTO profile) {
        Map<String, String> fields = new HashMap<>();
        fields.put(CacheKeys.UserInfoField.ID, String.valueOf(profile.userId()));
        putIfPresent(fields, CacheKeys.UserInfoField.USERNAME, profile.username());
        putIfPresent(fields, CacheKeys.UserInfoField.NICKNAME, profile.nickName());
        putIfPresent(fields, CacheKeys.UserInfoField.GENDER, profile.gender());
        // LocalDate 统一用 ISO 格式（2000-01-01）—— 和下面 toLocalDate 的解析方式必须对称
        putIfPresent(fields, CacheKeys.UserInfoField.BIRTH_DATE,
                profile.birthDate() == null ? null : profile.birthDate().toString());
        putIfPresent(fields, CacheKeys.UserInfoField.AVATAR_URL, profile.avatarUrl());
        // 版本号（updated_at 的 ISO 字符串）。缓存 CAS 写入时靠它比较新旧，见 CacheKeys.UserInfoField.UPDATED_AT
        putIfPresent(fields, CacheKeys.UserInfoField.UPDATED_AT,
                profile.updatedAt() == null ? null : profile.updatedAt().toString());
        return fields;
    }

    /**
     * 把 Redis HASH 的字段表解码回用户资料。
     *
     * <p><b>数据坏了会抛异常</b>（比如被人手工改过、或者格式升级过），
     * 由调用方决定怎么处理 —— chat-user 的做法是"当未命中，去查库"，
     * 而不是把脏数据当成真的返回出去。
     *
     * @param fields Redis HASH 的内容
     * @return 用户资料
     * @throws IllegalStateException 缺少必要字段（id）
     * @throws RuntimeException      日期等字段格式不对（{@code LocalDate.parse} 抛出）
     */
    public static UserProfileDTO fromHashFields(Map<Object, Object> fields) {
        Object rawId = fields.get(CacheKeys.UserInfoField.ID);
        if (rawId == null) {
            throw new IllegalStateException("用户资料缓存里缺少 id 字段");
        }
        return new UserProfileDTO(
                Long.valueOf(rawId.toString()),
                str(fields.get(CacheKeys.UserInfoField.USERNAME)),
                str(fields.get(CacheKeys.UserInfoField.NICKNAME)),
                str(fields.get(CacheKeys.UserInfoField.GENDER)),
                toLocalDate(fields.get(CacheKeys.UserInfoField.BIRTH_DATE)),
                str(fields.get(CacheKeys.UserInfoField.AVATAR_URL)),
                toLocalDateTime(fields.get(CacheKeys.UserInfoField.UPDATED_AT)));
    }

    private static void putIfPresent(Map<String, String> map, String field, String value) {
        if (value != null && !value.isBlank()) {
            map.put(field, value);
        }
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : LocalDate.parse(text);
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : LocalDateTime.parse(text);
    }

    private UserProfileCacheCodec() {
    }
}
