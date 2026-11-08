package org.example.auth.service.impl;

import org.example.auth.client.UserClient;
import org.example.auth.dto.RegisterRequest;
import org.example.auth.dto.SecurePayload;
import org.example.auth.dto.SmsCodeResponse;
import org.example.auth.service.RegisterService;
import org.example.auth.service.SecurePayloadVerifier;
import org.example.auth.service.SmsCodeService;
import org.example.common.dto.UserCreateRequest;
import org.example.common.dto.UserDTO;
import org.example.common.exception.BizException;
import org.example.common.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import feign.FeignException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 注册业务实现 —— 整个注册流程的真正编排者。
 *
 * <p><b>注意这里没有任何格式校验</b>（非空、长度、手机号正则都提到 DTO 注解上去了）。
 * 留下的全是"业务规则"——也就是那些<b>需要外部信息才能判断</b>的规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterServiceImpl implements RegisterService {

    private final SecurePayloadVerifier securePayloadVerifier;
    private final SmsCodeService smsCodeService;
    private final UserClient userClient;

    /**
     * 是否把验证码直接返回给前端。
     * 只做开发便利用，生产环境必须为 false（见 application.yml 里的说明）。
     */
    @Value("${chat.sms.expose-code:false}")
    private boolean exposeCode;

    /** 启动时如果这个开关是开的，大声提醒一次，避免上线忘了关 */
    @PostConstruct
    void warnIfExposingCode() {
        if (exposeCode) {
            log.warn("========================================================================");
            log.warn("  ⚠️ chat.sms.expose-code=true：短信验证码会【直接返回给前端】");
            log.warn("  这只适合开发环境。生产环境必须设为 false，否则任何人都能给任意手机号注册！");
            log.warn("========================================================================");
        }
    }

    @Override
    public SmsCodeResponse sendSmsCode(String phone) {
        // 手机号格式已经由 SmsCodeRequest 上的 @Pattern 校验过了，这里只做限流
        if (!smsCodeService.canSend(phone)) {
            throw new BizException(429, "发送太频繁，请 60 秒后再试");
        }

        String code = smsCodeService.send(phone);

        // 只有开发环境才把验证码给前端
        return exposeCode ? SmsCodeResponse.exposed(code) : SmsCodeResponse.hidden();
    }

    @Override
    public void register(RegisterRequest request) {
        // 格式校验已由 @Valid 在进入 controller 前完成，这里直接进入业务规则

        // ---------- 1. 校验短信验证码 ----------
        if (!smsCodeService.verify(request.phone(), request.code())) {
            throw new BizException("验证码错误或已过期");
        }

        // ---------- 2. 解密 + 时间戳 + nonce（和登录共用同一套，见 SecurePayloadVerifier）----------
        SecurePayload payload = securePayloadVerifier.verifyAndConsume(request.password());

        // ---------- 3. 调 chat-user 落库（手机号查重 + BCrypt 加密都在那边）----------
        Result<UserDTO> created;
        try {
            created = userClient.create(
                    new UserCreateRequest(request.username(), request.phone(), payload.password()));
        } catch (FeignException e) {
            // 只接 FeignException（传输层失败的公共父类），不接 Exception ——
            // 完整理由见 chat-message 的 MessageServiceImpl#areFriends 上方那段注释。
            log.error("调用 chat-user 创建用户失败", e);
            throw new BizException(503, "用户服务暂时不可用，请稍后重试");
        }

        // 把下游服务的业务错误原样抛出去（比如"该手机号已注册"）
        if (created == null) {
            throw new BizException(503, "用户服务无响应");
        }
        if (!created.isSuccess()) {
            throw new BizException(created.getCode(), created.getMessage());
        }

        log.info("注册成功: userId={}, username={}", created.getData().userId(), created.getData().username());
        // 不返回业务数据：注册的职责只是"创建成功"，前端拿到成功状态后跳登录页
    }
}
