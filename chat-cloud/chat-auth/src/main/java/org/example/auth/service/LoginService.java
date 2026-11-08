package org.example.auth.service;

import org.example.auth.dto.LoginRequest;

/**
 * 登录业务接口。
 */
public interface LoginService {

    /**
     * 登录。
     *
     * <p>流程：检查锁定 → RSA 解密 → 时间戳/nonce → 调 chat-user 校验密码
     * → 清失败计数 → 签发 token（同时踢掉该用户的旧登录）。
     *
     * <p>只返回 token —— 用户信息不该由登录接口负责，要的话另开 {@code GET /user/me}。
     *
     * @param request 前端提交的登录请求（password 是 RSA 密文）
     * @return 登录凭证 token
     * @throws org.example.common.exception.BizException 密码错误 / 账号锁定 / 用户服务不可用
     */
    String login(LoginRequest request);

    /**
     * 登出：让当前用户的 token 立即失效。
     *
     * <p>当前用户身份由<b>网关</b>提供：网关验过 token 后把它换成 userId，
     * 写进 {@code X-User-Id} 请求头传下来。所以这里收的是 userId，
     * 不再收 token —— 解析 {@code Authorization} 头已经是网关的职责。
     *
     * @param userId 当前登录用户 id
     */
    void logout(Long userId);
}
