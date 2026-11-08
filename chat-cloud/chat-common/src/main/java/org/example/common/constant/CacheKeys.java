package org.example.common.constant;

import java.time.Duration;

/**
 * 所有 <b>Redis key 的统一约定</b>（缓存、token、验证码、nonce）。
 *
 * <p><b>为什么全都放这里？</b> 因为 Redis 里的每一样东西几乎都是
 * <b>一个服务写、另一个服务读</b>，而 key 一旦对不上，症状都是"静默失效"：
 * <ul>
 *   <li>网关和 chat-auth 的 token 前缀不一致 → <b>登录成功但所有接口都 401</b></li>
 *   <li>缓存字段名和这里的常量不一致 → <b>写进去读不出来</b></li>
 * </ul>
 * 这类问题<b>不会报错</b>，只能靠肉眼比对两个不同文件里的字符串 ——
 * 所以宁可"多一个公共类"，也不让同一个字符串出现两遍。
 *
 * <p><b>key 的命名约定</b>：{@code 域:对象:标识}
 * <pre>
 *   auth:token:{token}         chat-auth 写，chat-gateway 读   （token → userId）
 *   auth:user-token:{userId}   chat-auth 写读                  （userId → token）
 *   auth:login-fail:{phone}    chat-auth 写读                  （登录失败次数）
 *   chat:sms:code:{phone}      chat-auth 写读                  （短信验证码）
 *   chat:sms:attempt:{phone}   chat-auth 写读                  （验证码错误次数）
 *   chat:sms:limit:{phone}     chat-auth 写读                  （发送间隔限流）
 *   chat:nonce:{nonce}         chat-auth 写读                  （防重放）
 *   user:info:{userId}         chat-user 写读                  （用户资料缓存）
 * </pre>
 *
 * <p>命名前缀历史上不太统一（{@code auth:} 和 {@code chat:} 混用），
 * 这里保留原样不动 —— <b>改前缀会让线上已有的 key 全部失效</b>
 * （用户被登出、缓存全部穿透），不值得为了好看去改。新加的 key 统一用 {@code 域:} 开头。
 */
public final class CacheKeys {

    /** 用户连接归属 HASH：instanceId/sessionId/userId，带 TTL。 */
    public static final String CONNECTION_ROUTE_PREFIX = "chat:connection:route:";

    // ==================== 认证相关（chat-auth；token 前缀网关也要用）====================

    /**
     * token → userId。
     *
     * <p><b>⚠️ 网关也用这个前缀</b>（它要拿 token 换 userId）。
     * 以前它被写在 3 个地方（chat-auth 的 TokenService、gateway 的 AuthProperties 默认值、
     * gateway 的 yml），任何一处改错都会导致"登录成功但所有接口都 401"。
     */
    public static final String AUTH_TOKEN_PREFIX = "auth:token:";

    /** userId → token（反向索引，用来"新登录踢掉旧登录"和登出） */
    public static final String AUTH_USER_TOKEN_PREFIX = "auth:user-token:";

    /** phone → 连续登录失败次数（防暴力破解） */
    public static final String AUTH_LOGIN_FAIL_PREFIX = "auth:login-fail:";

    /** phone → 短信验证码 */
    public static final String SMS_CODE_PREFIX = "chat:sms:code:";

    /** phone → 验证码错误次数 */
    public static final String SMS_ATTEMPT_PREFIX = "chat:sms:attempt:";

    /** phone → 发送间隔限流标记（SETNX 实现 60 秒只能发一次） */
    public static final String SMS_LIMIT_PREFIX = "chat:sms:limit:";

    /** nonce → 已使用标记（防重放，同一个密文只能用一次） */
    public static final String NONCE_PREFIX = "chat:nonce:";

    // ==================== 消息投递（chat-message）====================
    //
    // ⚠️ 这两个 key 里的东西【都是可以丢的】：消息本身只以 MySQL 为准，
    //    "实时推送失败"由"上线补拉"兜底（查 message_receipt 里 delivered_at 为空的）。
    //    所以 Redis 和 MySQL 之间不需要强一致，也不会有分布式事务问题。

    /**
     * 待送达队列（ZSET）。
     *
     * <p>score = <b>下次重试的时间戳（毫秒）</b>，member = {@code "messageId:receiverUserId"}。
     *
     * <p><b>为什么用 ZSET 而不是"每条消息起一个定时器"</b>：一条消息一个 Timer 的话，
     * 几千条未确认消息就是几千个定时任务，Redis 没事、JVM 先炸。
     * ZSET 让"哪些该重试了"变成一次查询：{@code ZRANGEBYSCORE 0 现在}。
     */
    public static final String PENDING_DELIVERY_ZSET = "chat:pending:delivery";

    /**
     * 每条待送达消息已经重试了几次（HASH）。
     *
     * <p>field 和上面 ZSET 的 member 是同一个字符串，用来算<b>指数退避</b>的间隔
     * （1s、2s、4s、8s、16s、30s 封顶）。
     * 送达 ack 到达（或放弃重试）时会 HDEL 掉对应的 field。
     */
    public static final String PENDING_ATTEMPT_HASH = "chat:pending:attempt";

    /** 待送达任务处理租约令牌；与 ZSET 的租约到期时间共同防止旧 worker 修改新任务。 */
    public static final String PENDING_CLAIM_HASH = "chat:pending:claim";

    // ==================== 用户资料缓存（只有 chat-user 读写）====================

    /** 用户资料缓存的 key 前缀，完整 key 形如 {@code user:info:1} */
    public static final String USER_INFO_PREFIX = "user:info:";

    /**
     * 用户资料缓存的存活时间（2 小时）。
     *
     * <p><b>为什么是常量，而不是在 yml 里配一份？</b>
     * 因为有两个地方要"同意"这个数字：写入时设的 TTL、读命中后续期时重置回的 TTL。
     * Redis 不会告诉你"这个 key 原本的 TTL 是多少"，所以续期的一方必须自己知道这个值。
     * 两处对不上的话（比如写入设 60 分钟、续期续成 120 分钟），缓存就会越活越久、永不过期，
     * 而<b>没有任何报错</b> —— 放在常量里就只有一个地方可改。
     */
    public static final Duration USER_INFO_TTL = Duration.ofHours(2);

    /** token 有效期。前端 `auth.js` 里的 TOKEN_TTL_DAYS 要和它保持一致（前端只是显示用）。 */
    public static final Duration TOKEN_TTL = Duration.ofDays(7);

    /**
     * 拼出某个用户的资料缓存 key。
     *
     * @param userId 用户 id
     * @return 形如 {@code user:info:1}
     */
    public static String userInfo(Long userId) {
        return USER_INFO_PREFIX + userId;
    }

    /**
     * 用户资料缓存的 HASH 字段名。
     *
     * <p>故意用嵌套类包起来：这几个名字只在"读/写用户资料缓存"时用，
     * 放在顶层会污染 {@code CacheKeys} 的命名空间，也容易和别的缓存的字段名撞概念。
     *
     * <p><b>为什么字段名要和 {@code UserProfileDTO} 的字段一模一样？</b>
     * 这样"读缓存拿到的"和"调接口拿到的"看到的是同一套名字，不会让人对不上。
     */
    public static final class UserInfoField {

        public static final String ID = "userId";
        public static final String USERNAME = "username";

        /**
         * 昵称。
         *
         * <p><b>大小写是 {@code nickName}</b>，和 DTO / JSON / 前端一致；
         * 数据库那一层仍然是下划线的 {@code nick_name}（SQL 惯例），
         * 两者由 MyBatis 的 map-underscore-to-camel-case 对上。
         */
        public static final String NICKNAME = "nickName";
        public static final String GENDER = "gender";
        /** 存 ISO 格式字符串，如 {@code 2000-01-01} */
        public static final String BIRTH_DATE = "birthDate";
        public static final String AVATAR_URL = "avatarUrl";

        /**
         * <b>版本号</b>（存 {@code user.updated_at} 的 ISO 字符串，如 {@code 2026-10-04T21:20:40.123456}）。
         *
         * <p>它不参与"展示"，只用来做 <b>CAS（条件写入）</b>：
         * 写缓存前先比较这个字段，<b>只有自己的版本不比缓存里的旧，才允许写</b>。
         * 这样就不会出现"读线程拿着旧数据、在别人更新之后又把它写回缓存"的脏数据。
         *
         * <p>为什么用 ISO 字符串而不是时间戳数字：ISO 8601 有个很好的性质 ——
         * <b>字典序 = 时间序</b>，所以 Lua 里直接比字符串就能分出新旧，
         * 不用在 Java 里把 {@code LocalDateTime} 转成时间戳（那个转换很容易踩时区的坑）。
         */
        public static final String UPDATED_AT = "updatedAt";

        private UserInfoField() {
        }
    }

    private CacheKeys() {
    }
}
