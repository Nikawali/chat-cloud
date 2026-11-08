package org.example.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 前端登录页提交的请求体（chat-auth 与前端之间的契约，只有 auth 用，所以留在 auth 自己这儿）。
 *
 * <p>登录凭证是<b>手机号</b>：用户名允许重复，没法唯一定位一个人。
 *
 * @param phone    手机号
 * @param password <b>RSA 加密后的密文</b>，解开后是 {@code 时间戳|nonce|密码}
 */
public record LoginRequest(

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        // RSA 密文，长度形态不固定，只校验非空
        @NotBlank(message = "密码不能为空")
        String password
) {
}
