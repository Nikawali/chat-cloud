package org.example.common.dto;

/**
 * 内部调用参数：chat-auth 校验登录密码时传给 chat-user。
 *
 * <p>放在 chat-common 是因为它【跨服务传输】—— auth 和 user 必须用同一个类，
 * JSON 序列化/反序列化才能对上。
 *
 * <p><b>为什么传明文密码而不传密码哈希？</b>
 * 密码哈希是 chat-user 的私有数据（和 phone、avatar_url 一样），
 * 让 auth 拿到它就等于扩大了泄露面。所以让 chat-user 拿着明文自己比对，
 * 只把"对不对"这一个结果返回回来。
 *
 * @param phone    手机号（登录凭证）
 * @param password 明文密码（内网传输；chat-user 收到后立刻 BCrypt.matches 比对，不落库不打印）
 */
public record UserVerifyRequest(String phone, String password) {
}
