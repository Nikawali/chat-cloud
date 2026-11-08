package org.example.auth.controller;

import org.example.auth.dto.LoginRequest;
import org.example.auth.dto.RegisterRequest;
import org.example.auth.dto.SmsCodeRequest;
import org.example.auth.dto.SmsCodeResponse;
import org.example.auth.service.LoginService;
import org.example.auth.service.RegisterService;
import org.example.auth.util.RsaUtils;
import org.example.common.constant.AuthHeaders;
import org.example.common.result.Result;
import org.example.common.support.RequestUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 认证接口。
 *
 * <p>controller 只做三件事：<b>接收参数 → 调 service → 包装 Result</b>。
 * 格式校验由 DTO 注解 + {@code @Valid} 负责，业务规则在 service，抛出的异常由全局处理器统一转 Result。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RsaUtils rsaUtils;
    private final RegisterService registerService;
    private final LoginService loginService;

    /**
     * 下发 RSA 公钥。前端启动时拉一次并缓存。
     *
     * <p>公钥由后端下发（而不是硬编码在前端），将来换密钥就不用重新打包前端了 ——
     * 对 Electron 桌面端尤其重要。
     */
    @GetMapping("/public-key")
    public Result<String> publicKey() {
        return Result.success(rsaUtils.getPublicKeyPem());
    }

    /**
     * 发送短信验证码。
     *
     * <p>返回值里的 {@code code} 字段：<b>只有开发环境（chat.sms.expose-code=true）才有值</b>，
     * 方便前端直接显示，不用去翻后端日志。生产环境为 null。
     */
    @PostMapping("/sms-code")
    public Result<SmsCodeResponse> sendSmsCode(@Valid @RequestBody SmsCodeRequest request) {
        return Result.success(registerService.sendSmsCode(request.phone()));
    }

    /**
     * 注册。
     *
     * <p>成功后只返回状态码和 message，<b>没有任何业务数据</b> —— 前端直接跳登录页即可。
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request) {
        registerService.register(request);
        return Result.success();
    }

    /**
     * 登录。
     *
     * <p>成功后 <b>只返回 token</b>（放在 {@code data} 里），用户信息不在这里给 ——
     * 需要的话另开一个 {@code GET /user/me}，从 token 解析出身份再查。
     *
     * <p>注意：<b>新登录会让该用户之前的 token 立即失效</b>（单点登录）。
     */
    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(loginService.login(request));
    }

    /**
     * 登出：让当前用户的 token 立即失效。
     *
     * <p><b>当前用户由网关提供，这里不再解析 token。</b>
     * 网关已经验过 {@code Authorization} 头、把 token 换成 userId 并写进
     * {@code X-User-Id} 头转发下来，所以这里直接读这个头即可。
     * 解析 Authorization / 查 Redis 换 userId 这些事，都已经是网关的职责。
     *
     * <p>这也意味着：如果绕过网关直连 8081，会拿不到 X-User-Id，直接 401。
     */
    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId) {
        // 取不到身份就 401：说明请求没经过网关（直连 8081），
        // 或者 /api/auth/logout 被错误地加进了网关白名单（白名单请求不带 X-User-Id）。
        // 判断逻辑和四个服务共用同一份实现，见 chat-common 的 RequestUser#requireId
        loginService.logout(RequestUser.requireId(userId));
        return Result.success();
    }
}
