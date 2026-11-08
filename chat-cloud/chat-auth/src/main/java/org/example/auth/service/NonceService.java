package org.example.auth.service;

import java.time.Duration;

import org.example.common.constant.CacheKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * nonce 防重放服务。
 *
 * <p>原理：前端每次请求都带一个随机 nonce（放在密文内部，改不了）。
 * 后端把用过的 nonce 记下来，同一个 nonce 再来一次就判定为重放，直接拒绝。
 */
@Service
@RequiredArgsConstructor
public class NonceService {

    /** key 前缀统一从 chat-common 取 */
    private static final String NONCE_KEY = CacheKeys.NONCE_PREFIX;

    /**
     * nonce 的存活时间。
     * <b>必须 >= 时间戳容许的最大偏差</b>：只有在这个时间窗口内的密文才可能被重放，
     * 窗口一过，即使 nonce 记录被删了，时间戳校验也会把旧密文挡掉。
     */
    private static final Duration NONCE_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;

    /**
     * 原子地「占用」这个 nonce。
     *
     * <p>用 {@code SETNX}（Spring 里的 setIfAbsent）：key 不存在时才写入并返回 true。
     * 必须是原子的 —— 如果用"先查再写"，两个并发请求可能同时查到"没用过"，就都放行了。
     *
     * @return true = 首次使用（放行）；false = 已经用过（判定为重放，拒绝）
     */
    public boolean tryConsume(String nonce) {
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(NONCE_KEY + nonce, "1", NONCE_TTL);
        return Boolean.TRUE.equals(first);
    }
}
