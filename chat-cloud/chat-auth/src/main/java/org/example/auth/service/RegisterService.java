package org.example.auth.service;

import org.example.auth.dto.RegisterRequest;
import org.example.auth.dto.SmsCodeResponse;

/**
 * 注册业务接口。
 *
 * <p>接口与实现分离的好处：controller 只依赖这个接口，
 * 以后想换实现（比如加个发短信的外部通道）不用动 controller。
 */
public interface RegisterService {

    /**
     * 发送短信验证码。
     *
     * @param phone 手机号
     * @return 验证码（只有开发环境 {@code chat.sms.expose-code=true} 才带值，生产环境为 null）
     * @throws org.example.common.exception.BizException 手机号格式不对 / 发送太频繁
     */
    SmsCodeResponse sendSmsCode(String phone);

    /**
     * 注册。
     *
     * <p><b>不返回任何业务数据</b> —— 注册的职责就是"创建成功"，
     * 前端拿到成功状态后跳登录页即可，不需要回传用户信息。
     *
     * @param request 前端提交的注册请求（password 是 RSA 密文）
     * @throws org.example.common.exception.BizException 校验不通过 / 手机号已注册 / 用户服务不可用
     */
    void register(RegisterRequest request);
}
