package org.example.common.dto;

/**
 * 内部调用参数：chat-auth 收到前端注册请求、校验完验证码之后，
 * 通过 Feign 把这个对象传给 chat-user，由 chat-user 落库。
 *
 * <p>放在 chat-common 是因为它【跨服务传输】——auth 和 user 都要用同一个类，
 * 序列化/反序列化才能对上。而前端传来的 RegisterRequest 只有 auth 用，就留在 auth 自己那儿。
 *
 * @param username 用户名
 * @param phone    手机号
 * @param password 明文密码（内网传输；chat-user 收到后立刻 BCrypt 加密再存库）
 */
public record UserCreateRequest(String username, String phone, String password) {
}
