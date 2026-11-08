package org.example.user.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改用户资料的请求体（前端 → chat-user）。
 *
 * <p><b>为什么放在 chat-user 自己的 dto 包，而不是 chat-common？</b>
 * 因为 chat-common 只放<b>跨服务的契约</b>（比如 {@code UserCreateRequest}、
 * {@code UserVerifyRequest} 是 chat-auth 通过 Feign 发给 chat-user 的）。
 * 这个 DTO 只有"前端经网关调 chat-user"这一条路，没有别的服务用它，
 * 所以属于 chat-user 的私有契约，放自己家里。
 *
 * <p><b>所有字段都是可选的</b>（只传要改的那几个）。校验注解全是"为 null 就跳过"的类型，
 * 所以不传某个字段不会报错。
 *
 * <p><b>为什么不包含下面这些字段？</b>
 * <ul>
 *   <li>{@code phone} —— 手机号是登录凭证，改它等于换账号，要走"验证新号"的独立流程</li>
 *   <li>{@code password} —— 改密码要走"验旧密码 + 新密码"的独立流程（而且明文密码不能这样传）</li>
 *   <li>{@code username} —— 用户名是登录名，改它会影响登录，也要独立流程</li>
 *   <li>{@code id} —— 改的是"当前登录用户"，id 由网关从 token 解析后传进来，
 *       绝不能让前端自己指定（否则谁都能改别人的资料）</li>
 * </ul>
 *
 * @param nickName  新昵称。不传 = 不改
 * @param gender    新性别，只接受 {@code M}（男）或 {@code F}（女）。不传 = 不改
 * @param birthDate 新出生日期，格式 {@code 2000-01-01}。不传 = 不改
 * @param avatarUrl 新头像地址。不传 = 不改
 */
public record UpdateProfileRequest(

        @Size(max = 50, message = "昵称长度不能超过 50 个字符")
        String nickName,

        @Pattern(regexp = "^[MF]$", message = "性别只能是 M 或 F")
        String gender,

        @Past(message = "出生日期必须是过去的日期")
        LocalDate birthDate,

        @Size(max = 255, message = "头像地址长度不能超过 255 个字符")
        String avatarUrl
) {
}
