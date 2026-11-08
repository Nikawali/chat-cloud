package org.example.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 发送短信验证码的请求体。
 *
 * @param phone 手机号
 */
public record SmsCodeRequest(

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone
) {
}
