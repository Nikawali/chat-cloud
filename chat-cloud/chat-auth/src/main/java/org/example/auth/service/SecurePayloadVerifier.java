package org.example.auth.service;

import org.example.auth.dto.SecurePayload;
import org.example.auth.util.RsaUtils;
import org.example.common.exception.BizException;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 解析并校验前端传来的密码载荷：<b>RSA 解密 → 时间戳校验 → nonce 防重放</b>。
 *
 * <p><b>为什么单独抽出来？</b> 登录和注册都要做这三步，以前是<b>两份逐行相同的代码</b>，
 * 连 {@code TIMESTAMP_WINDOW_MS} 都各定义了一遍。重复的代价不只是啰嗦：
 * <ul>
 *   <li>改一处忘一处 → 两个接口的防重放策略悄悄不一致</li>
 *   <li>{@code TIMESTAMP_WINDOW_MS} 和 {@link NonceService} 的 NONCE_TTL 必须匹配
 *       （时间窗口一过，nonce 记录被删掉也没关系，因为时间戳会先把它挡掉）。
 *       常量有两个副本时，这个约束就只能靠注释来维持</li>
 * </ul>
 *
 * <p>现在它们都在这里，<b>规则只有一份</b>。
 *
 * <p>前端加密的明文格式是 {@code 时间戳|nonce|密码}，由 {@link SecurePayload} 负责拆分。
 * 三者必须一起进密文：nonce 和时间戳一旦裸露在外面，攻击者重放时顺手改一下就能绕过校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurePayloadVerifier {

    /**
     * 时间戳容许的最大偏差。
     *
     * <p><b>必须 &lt;= {@link NonceService} 的 NONCE_TTL</b>：
     * nonce 记录的存活时间只要覆盖住时间戳窗口就够了 ——
     * 窗口一过，即使 nonce 记录已经过期被删，时间戳校验也会把旧密文挡掉。
     */
    private static final long TIMESTAMP_WINDOW_MS = 60_000L;

    private final RsaUtils rsaUtils;
    private final NonceService nonceService;

    /**
     * 解密并校验载荷。三步全过才返回 <b>明文密码</b>。
     *
     * @param cipherBase64 前端用公钥加密的 Base64 密文
     * @return 解析出来的载荷（可以取 {@code password()} 拿明文密码）
     * @throws BizException 解密失败 / 请求过期 / 重复提交
     */
    public SecurePayload verifyAndConsume(String cipherBase64) {
        // ---------- ① RSA 解密 + 解析载荷 ----------
        SecurePayload payload;
        try {
            payload = SecurePayload.parse(rsaUtils.decrypt(cipherBase64));
        } catch (Exception e) {
            // 不把 e.getMessage() 往前端抛：那可能是"解密失败"这种内部细节。
            // 但日志里要留，方便区分"密钥不匹配"和"前端格式不对"。
            log.warn("密码载荷解析失败: {}", e.getMessage());
            throw new BizException("密码解密失败，请刷新页面后重试");
        }

        // ---------- ② 时间戳校验：防旧密文被翻出来重放 ----------
        if (Math.abs(System.currentTimeMillis() - payload.timestamp()) > TIMESTAMP_WINDOW_MS) {
            throw new BizException("请求已过期，请重新提交");
        }

        // ---------- ③ nonce 校验：同一个密文只能用一次 ----------
        if (!nonceService.tryConsume(payload.nonce())) {
            throw new BizException("请求重复提交，请勿重复点击");
        }

        return payload;
    }
}
