package org.example.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 前端注册页提交的请求体（chat-auth 与前端之间的契约，只有 auth 用，所以留在 auth 自己这儿）。
 *
 * <p><b>格式类校验直接写在字段上</b>，由 controller 的 {@code @Valid} 触发，
 * 校验不过会在进入 controller 之前就抛出 {@code MethodArgumentNotValidException}，
 * 被全局异常处理器统一转成 Result。这样 service 层就只剩真正的业务规则了。
 *
 * <p>规则要和前端 Register.vue 里的保持一致，否则会出现"前端过了后端不过"的割裂。
 *
 * @param username 用户名（允许重复，只是显示名）
 * @param phone    手机号（唯一，是登录凭证）
 * @param password <b>RSA 加密后的密文</b>，解开后是 {@code 时间戳|nonce|密码}
 * @param code     短信验证码
 */
public record RegisterRequest(

        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 20, message = "用户名长度需为 3~20 个字符")
        String username,

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        // 这是 RSA 密文，长度和形态都不固定，所以只校验非空，不校验格式
        @NotBlank(message = "密码不能为空")
        String password,

        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
        String code
) {
}
