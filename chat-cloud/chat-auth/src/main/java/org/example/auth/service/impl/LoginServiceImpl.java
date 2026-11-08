package org.example.auth.service.impl;

import org.example.auth.client.UserClient;
import org.example.auth.dto.LoginRequest;
import org.example.auth.dto.SecurePayload;
import org.example.auth.service.LoginService;
import org.example.auth.service.SecurePayloadVerifier;
import org.example.auth.service.TokenService;
import org.example.common.dto.UserVerifyRequest;
import org.example.common.exception.BizException;
import org.example.common.result.Result;
import org.springframework.stereotype.Service;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 登录业务实现。
 *
 * <p>和注册一样，auth <b>不碰数据库</b>：密码校验交给 chat-user，
 * 自己只负责"编排流程 + 签发 token"。
 *
 * <p><b>也不碰用户资料缓存</b>：缓存由 chat-user 在校验通过时自己预热，
 * 这里从 verify 只拿一个 userId。auth 因此完全不需要知道缓存长什么样。
 *
 * <p>防重放（时间戳 + nonce）和注册共用 {@link SecurePayloadVerifier} ——
 * 登录接口同样会被重放攻击，两边必须用同一套规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginServiceImpl implements LoginService {

    private final SecurePayloadVerifier securePayloadVerifier;
    private final TokenService tokenService;
    private final UserClient userClient;

    @Override
    public String login(LoginRequest request) {
        String phone = request.phone();

        // ---------- 1. 先看这个账号是不是被锁了（防暴力破解）----------
        // 放在最前面：锁定期间连 RSA 解密都不做，直接拒绝，省资源
        if (tokenService.isLocked(phone)) {
            long remainSeconds = tokenService.getLockRemainSeconds(phone);
            long minutes = Math.max(1, (remainSeconds + 59) / 60);
            throw new BizException(429, "密码错误次数过多，账号已锁定，请 " + minutes + " 分钟后再试");
        }

        // ---------- 2. 解密 + 时间戳 + nonce（和注册共用同一套，见 SecurePayloadVerifier）----------
        SecurePayload payload = securePayloadVerifier.verifyAndConsume(request.password());

        // ---------- 3. 调 chat-user 校验密码（哈希不出 chat-user）----------
        Result<Long> verifyResult;
        try {
            verifyResult = userClient.verify(new UserVerifyRequest(phone, payload.password()));
        } catch (FeignException e) {
            // 只接 FeignException（传输层失败的公共父类），不接 Exception ——
            // 完整理由见 chat-message 的 MessageServiceImpl#areFriends 上方那段注释。
            log.error("调用 chat-user 校验密码失败", e);
            throw new BizException(503, "用户服务暂时不可用，请稍后重试");
        }
        if (verifyResult == null) {
            throw new BizException(503, "用户服务无响应");
        }

        // ---------- 4. 密码错误：累计失败次数 ----------
        if (!verifyResult.isSuccess()) {
            // ⚠️ 只有 401（"手机号或密码错误"）才算"用户输错了一次"。
            //
            // 为什么必须卡这个码：chat-user 那边的失败原因不止一种 ——
            //   401 = 手机号不存在 / 密码不对（用户的错，该计数）
            //   5xx = 库抖了、Feign 超时后包出来的业务异常（服务的错，不该计数）
            // 如果不分青红皂白都计一次失败，那么 chat-user 一抖，
            // 正常用户重试 5 次就被锁 15 分钟 —— 一次上游故障被放大成一场可用性事故。
            if (verifyResult.getCode() != 401) {
                log.error("chat-user 校验密码返回了非预期的业务码: code={}, message={}",
                        verifyResult.getCode(), verifyResult.getMessage());
                throw new BizException(503, "用户服务暂时不可用，请稍后重试");
            }

            int fails = tokenService.recordLoginFailure(phone);
            int remain = TokenService.MAX_FAIL - fails;
            if (remain > 0) {
                throw new BizException(401, "手机号或密码错误，还可尝试 " + remain + " 次");
            }
            throw new BizException(429, "密码错误次数过多，账号已锁定 15 分钟");
        }

        // ---------- 5. 登录成功：清失败计数 + 签发 token ----------
        tokenService.clearLoginFailure(phone);

        // 只拿 userId：用户资料的 Redis 缓存由 chat-user 在校验通过时自己预热，
        // 本服务不需要（也不该）知道缓存的存在
        Long userId = verifyResult.getData();
        // issue() 内部会先把该用户上一次的 token 删掉 —— 这就是"新登录踢掉旧登录"
        String token = tokenService.issue(userId);

        log.info("登录成功: userId={}, phone={}", userId, phone);
        // 只返回 token：用户信息不该由登录接口负责，前端要的话另开 GET /user/me
        return token;
    }

    @Override
    public void logout(Long userId) {
        tokenService.revokeByUserId(userId);
    }
}
