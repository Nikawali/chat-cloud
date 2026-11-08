package org.example.common.dto;

/**
 * 用户信息。chat-user 提供，chat-message / chat-auth 等通过 Feign 获取。
 *
 * <p>这是<b>对外契约</b>：只含别的服务需要的字段。
 * 数据库表里的 password / phone / avatar_url 等一律不往外给。
 *
 * <p>字段名统一用 {@code userId}（不是 {@code id}）：全项目一个叫法，
 * 免得"这个 id 是用户的还是群的"要靠上下文猜。
 */
public record UserDTO(Long userId, String username) {
}
