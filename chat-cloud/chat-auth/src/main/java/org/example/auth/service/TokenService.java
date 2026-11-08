package org.example.auth.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.example.common.constant.CacheKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Token 与登录失败次数的 Redis 管理。
 *
 * <p>Redis key 设计（<b>双向索引</b>）：
 * <pre>
 *   auth:token:{token}          → userId    给校验用：拿 token 换 userId
 *   auth:user-token:{userId}    → token     给踢人用：拿 userId 找它当前的 token
 *   auth:login-fail:{phone}     → 失败次数   给防暴力破解用
 * </pre>
 *
 * <p><b>为什么必须双向？</b> 只有 {@code token → userId} 的话，
 * "新登录踢掉旧登录"就得遍历所有 token 找这个人的旧 token，几万个 key 时性能极差。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    // key 前缀统一从 chat-common 取 —— 尤其是 AUTH_TOKEN_PREFIX，
    // 网关也要用同一个（它拿 token 换 userId），两边写两遍迟早会对不上
    private static final String TOKEN_KEY = CacheKeys.AUTH_TOKEN_PREFIX;
    private static final String USER_TOKEN_KEY = CacheKeys.AUTH_USER_TOKEN_PREFIX;
    private static final String LOGIN_FAIL_KEY = CacheKeys.AUTH_LOGIN_FAIL_PREFIX;

    /** token 有效期（和前端 auth.js 里显示的 TOKEN_TTL_DAYS 对应，见 CacheKeys 的说明） */
    private static final Duration TOKEN_TTL = CacheKeys.TOKEN_TTL;
    /** 失败计数的统计窗口；错满 MAX_FAIL 次后锁这么久 */
    private static final Duration LOCK_TTL = Duration.ofMinutes(15);
    /** 允许的连续失败次数 */
    public static final int MAX_FAIL = 5;

    /**
     * "失败次数 +1，并在第一次时给它设过期"的 Lua 脚本。
     *
     * <p><b>为什么必须是 Lua（而不是 INCR 完再 EXPIRE）</b>：那是两条命令，
     * 中间任何一次失败（进程被杀、网络抖、Redis 报错）都会留下一个
     * <b>没有过期时间</b>的计数 key；而 {@link #isLocked} 只看数值、不看 TTL ——
     * 那个手机号就被<b>永久锁定</b>了，只能人工去 Redis 里删 key 才能解开。
     * Redis 的 Lua 脚本原子执行，不会被打断，这个空隙就不存在了。
     *
     * <p>只在 {@code count == 1} 时设 TTL，语义是"<b>15 分钟内</b>累计错 5 次就锁定"，
     * 而不是"一直错就一直续期"（后者意味着攻击者可以永远试下去、被锁的用户永远解不开）。
     *
     * <pre>
     * KEYS[1] = auth:login-fail:{phone}
     * ARGV[1] = TTL（秒）
     * 返回累计失败次数
     * </pre>
     */
    private static final RedisScript<Long> RECORD_FAILURE = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 签发 token，同时<b>踢掉该用户之前的登录</b>（单点登录）。
     *
     * @param userId 用户 ID
     * @return 新 token
     */
    public String issue(Long userId) {
        // ① 先把这个用户上一次的 token 删掉（有的话）
        String oldToken = redisTemplate.opsForValue().get(USER_TOKEN_KEY + userId);
        if (oldToken != null) {
            redisTemplate.delete(TOKEN_KEY + oldToken);
            log.info("用户 {} 在其他地方登录，旧 token 已失效", userId);
        }

        // ② 生成新 token（UUID 去掉横线 = 32 位十六进制）
        String token = UUID.randomUUID().toString().replace("-", "");

        // ③ 双向写入
        redisTemplate.opsForValue().set(TOKEN_KEY + token, String.valueOf(userId), TOKEN_TTL);
        redisTemplate.opsForValue().set(USER_TOKEN_KEY + userId, token, TOKEN_TTL);

        return token;
    }

    /**
     * 登出：让当前用户的 token 立即失效。
     *
     * <p>从"按 token 撤销"改成"按 userId 撤销"，是因为登出请求现在<b>经过网关</b>：
     * 网关已经用 token 换出了 userId 并写进 {@code X-User-Id} 请求头，
     * chat-auth 拿到的是 userId，不再需要自己解析 {@code Authorization} 里的 token。
     *
     * <p>实现走反向索引 {@code auth:user-token:{userId} → token}：
     * 拿到该用户当前有效的 token 后，把正反两个索引一起删掉。
     * 语义上更贴合"登出 = 让这个用户下线"，而不是"让某个特定 token 失效"。
     */
    public void revokeByUserId(Long userId) {
        String token = redisTemplate.opsForValue().get(USER_TOKEN_KEY + userId);
        if (token == null) {
            // 理论上走不到这里：网关能验过 token 说明正索引存在，反索引一般也在。
            // 保留这个判断做兜底，避免对 null token 拼 key 或误删。
            log.warn("登出时反向索引不存在: userId={}", userId);
            return;
        }
        redisTemplate.delete(TOKEN_KEY + token);
        redisTemplate.delete(USER_TOKEN_KEY + userId);
    }

    // ---------------- 登录失败次数（防暴力破解） ----------------

    /** 账号是否已被锁定 */
    public boolean isLocked(String phone) {
        String key = LOGIN_FAIL_KEY + phone;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return false;
        }
        try {
            if (Integer.parseInt(value) < MAX_FAIL) {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }

        // 【自愈】走到这里说明"次数够了，该锁了"。顺手确认这个 key 真的有过期时间：
        // 万一它是修复之前留下的"没有 TTL"的计数 key（或者被谁手工 SET 过），
        // 那这个账号就永远解不开了。发现没有 TTL 就补一个，把"永久锁定"降级成"再锁 15 分钟"。
        // （-1 = 存在但没有过期时间；-2 = key 已经没了，这时 expire 会返回 false，无害）
        Long ttl = redisTemplate.getExpire(key);
        if (ttl != null && ttl < 0) {
            log.warn("登录失败计数没有过期时间，补一个（否则该账号会被永久锁定）: phone={}", phone);
            redisTemplate.expire(key, LOCK_TTL);
        }
        return true;
    }

    /** 锁定还剩多少秒（用于给用户提示） */
    public long getLockRemainSeconds(String phone) {
        Long ttl = redisTemplate.getExpire(LOGIN_FAIL_KEY + phone);
        return ttl == null || ttl < 0 ? 0 : ttl;
    }

    /**
     * 记录一次登录失败。
     *
     * <p>只在<b>第一次</b>失败时设置过期时间：这样语义是
     * "15 分钟内累计错 5 次就锁定"，而不是"一直失败就一直续期"。
     * 这一步用 Lua 做成原子的（见 {@link #RECORD_FAILURE} 的注释）——
     * 否则"加次数"和"设过期"之间的任何失败都会留下一个永不过期的 key，把账号永久锁死。
     *
     * @return 累计失败次数
     */
    public int recordLoginFailure(String phone) {
        String key = LOGIN_FAIL_KEY + phone;
        Long count = redisTemplate.execute(
                RECORD_FAILURE, List.of(key), String.valueOf(LOCK_TTL.toSeconds()));
        return count == null ? 0 : count.intValue();
    }

    /** 登录成功后清空失败计数 */
    public void clearLoginFailure(String phone) {
        redisTemplate.delete(LOGIN_FAIL_KEY + phone);
    }
}
