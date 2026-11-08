package org.example.auth.service;

import java.security.SecureRandom;
import java.time.Duration;

import org.example.common.constant.CacheKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 短信验证码服务（当前是「模拟发送」，没有接真实短信通道）。
 *
 * <p>验证码存在 Redis 里，不是一个永久 Map —— 这样天然带过期，且多个 auth 实例共享。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsCodeService {

    // key 前缀统一从 chat-common 取，避免同一个字符串在项目里出现多遍
    private static final String CODE_KEY = CacheKeys.SMS_CODE_PREFIX;
    private static final String ATTEMPT_KEY = CacheKeys.SMS_ATTEMPT_PREFIX;
    private static final String LIMIT_KEY = CacheKeys.SMS_LIMIT_PREFIX;

    /** 验证码有效期 */
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    /** 同一手机号两次发送的最小间隔（和前端 60 秒倒计时对齐） */
    private static final Duration SEND_INTERVAL = Duration.ofSeconds(60);
    /** 最多允许输错几次，超过就作废验证码 */
    private static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    /**
     * 是否允许发送。用 SETNX 做 60 秒间隔限制 —— 原子操作，
     * 并发点两次也不会发出两条短信。
     *
     * @return true = 可以发送
     */
    public boolean canSend(String phone) {
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(LIMIT_KEY + phone, "1", SEND_INTERVAL);
        return Boolean.TRUE.equals(first);
    }

    /**
     * 生成并「发送」验证码。
     *
     * <p>⚠️ 这里是<b>模拟发送</b>：验证码直接打到日志里。
     * 接真实短信时，把下面那行 log 换成阿里云/腾讯云短信 SDK 的调用即可，
     * 其余逻辑（存 Redis、限流、校验）完全不用动。
     *
     * @return 生成的验证码（调用方决定要不要返回给前端 —— 只有开发环境才该返回）
     */
    public String send(String phone) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));

        redisTemplate.opsForValue().set(CODE_KEY + phone, code, CODE_TTL);
        redisTemplate.delete(ATTEMPT_KEY + phone);

        log.warn("【模拟短信】手机号 {} 的验证码 = {}（{} 分钟内有效）",
                phone, code, CODE_TTL.toMinutes());

        return code;
    }

    /**
     * 校验验证码。校验通过后<b>立即作废</b>，保证一个验证码只能用一次。
     *
     * <p><b>必须用 {@code getAndDelete} 而不是"先 get 再 delete"</b>：
     * 后者是两个独立的命令，中间有窗口 —— 两个并发请求可以都读到同一个验证码、
     * 于是<b>同一个验证码被用了两次</b>（并发注册、并发登录都可能撞上）。
     * {@code GETDEL} 在 Redis 里是原子的：读出即删除，只有一个请求能拿到值。
     *
     * <p>这个方法做到这个程度就够了：真正的兜底是数据库的唯一索引
     * （就算验证码被并发用两次，手机号也只会注册成功一次）。
     *
     * @return true = 验证通过
     */
    public boolean verify(String phone, String code) {
        String key = CODE_KEY + phone;

        // 原子地"取出并删除"：拿到值的那个请求才算用到了验证码
        String saved = redisTemplate.opsForValue().getAndDelete(key);
        if (saved == null) {
            // 没发送过 / 已过期 / 已经用掉了
            return false;
        }

        if (saved.equals(code)) {
            redisTemplate.delete(ATTEMPT_KEY + phone);
            return true;
        }

        // 验证码错误：累计错误次数，超过上限就直接拒绝，防止有人暴力猜 6 位数字
        // （验证码已经被上面的 getAndDelete 删掉了，所以这里不用再作废一次）
        String attemptKey = ATTEMPT_KEY + phone;
        Long attempts = redisTemplate.opsForValue().increment(attemptKey);
        redisTemplate.expire(attemptKey, CODE_TTL);
        if (attempts != null && attempts >= MAX_ATTEMPTS) {
            log.warn("手机号 {} 验证码错误次数达上限", phone);
        }
        return false;
    }
}
