package org.example.auth.dto;

/**
 * 发送验证码的响应。
 *
 * @param code 验证码。**只有开发环境（{@code chat.sms.expose-code=true}）才有值**，
 *             生产环境为 {@code null} —— 因为把验证码返回给前端等于任何人都能给任意手机号注册。
 */
public record SmsCodeResponse(String code) {

    /** 生产环境：不返回验证码 */
    public static SmsCodeResponse hidden() {
        return new SmsCodeResponse(null);
    }

    /** 开发环境：直接把验证码返回，省得去翻后端日志 */
    public static SmsCodeResponse exposed(String code) {
        return new SmsCodeResponse(code);
    }
}
