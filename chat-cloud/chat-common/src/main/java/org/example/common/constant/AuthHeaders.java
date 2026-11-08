package org.example.common.constant;

/**
 * 网关与各业务服务之间的【认证请求头】约定。
 *
 * <p>统一放在 chat-common，因为网关负责"写入"、各服务负责"读取"，
 * 两边必须用完全一样的字符串。散落在各处写魔法字符串，
 * 改一个字就是"看起来正常但永远取不到用户"的疑难杂症。
 *
 * <p><b>安全前提</b>：{@link #USER_ID} 是<b>网关生成的可信头</b>。
 * 网关必须在鉴权第一步就把它从客户端请求里剥掉（见 AuthGlobalFilter），
 * 否则任何人都能自己伪造一个 {@code X-User-Id} 冒充别人。
 */
public final class AuthHeaders {

    /** 标准认证头，格式 {@code Bearer <token>} */
    public static final String AUTHORIZATION = "Authorization";

    /** Bearer 前缀（注意末尾有个空格） */
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * 网关解析 token 后写入的当前用户 id。
     * 业务服务直接读这个头，<b>不要自己去解析 token</b>（那样每个服务都要连 Redis）。
     */
    public static final String USER_ID = "X-User-Id";

    /**
     * 内部头的统一前缀。命名约定是把这个前缀 + 大写驼峰（如 {@code X-User-Id}）。
     *
     * <p>网关只允许自己写入这类头，客户端的同类头一律剥离。
     * 需要枚举所有内部头时，看网关 {@code AuthGlobalFilter.INTERNAL_HEADER_NAMES}
     * （那里是唯一的权威名单，且有静态校验保证都符合这个前缀）。
     */
    public static final String INTERNAL_PREFIX = "X-User-";

    /**
     * 判断一个请求头名是不是内部头，用于校验命名约定。
     *
     * <p>注意用 {@code String.regionMatches} 而不是 {@code startsWith}：
     * HTTP 头名是<b>大小写不敏感</b>的，客户端可能发 {@code x-user-id}。
     */
    public static boolean isInternal(String headerName) {
        return headerName != null
                && headerName.regionMatches(true, 0, INTERNAL_PREFIX, 0, INTERNAL_PREFIX.length());
    }

    private AuthHeaders() {
    }
}
