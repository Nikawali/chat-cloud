package org.example.auth.dto;

/**
 * RSA 密文解开后的真实载荷。
 *
 * <p>前端加密的明文格式是：<b>{@code timestamp|nonce|password}</b>
 *
 * <p>为什么用分隔符而不是 JSON？因为 Spring Boot 4 把默认 JSON 库从 Jackson 2
 * 换成了 Jackson 3（包名从 {@code com.fasterxml.jackson} 变成 {@code tools.jackson}），
 * 为了解析一个字符串再引一次 JSON 库不划算，用分隔符零依赖。
 *
 * <p>为什么密码放在<b>最后</b>？这样用 {@code split("\\|", 3)} 切出来，
 * 即使密码自身含有 {@code |} 也不会被切错（前两段是纯数字和十六进制，不可能含 {@code |}）。
 *
 * @param password  真实密码（明文，只在内存里出现，绝不落库）
 * @param timestamp 前端生成密文时的毫秒时间戳，用于过期校验
 * @param nonce     一次性随机数，用于防重放
 */
public record SecurePayload(String password, long timestamp, String nonce) {

    /**
     * 解析解密后的明文。
     *
     * @param plain 形如 {@code 1758931200000|a7f3k9d2...|123456}
     * @throws IllegalArgumentException 格式不对时抛出
     */
    public static SecurePayload parse(String plain) {
        if (plain == null) {
            throw new IllegalArgumentException("载荷为空");
        }
        String[] parts = plain.split("\\|", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("载荷格式错误，应为 timestamp|nonce|password");
        }
        try {
            return new SecurePayload(parts[2], Long.parseLong(parts[0].trim()), parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("载荷中的时间戳不是合法数字");
        }
    }
}
