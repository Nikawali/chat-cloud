package org.example.connection.ws;

import java.util.Map;

import org.example.common.constant.AuthHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket 握手的<b>身份校验</b> —— 整个实时推送的身份根基。
 *
 * <p>WebSocket 握手就是一次 HTTP GET，所以它能被网关的鉴权过滤器处理：
 * 客户端带 {@code Authorization}，网关验完 token 之后把 userId 写进 {@code X-User-Id}。
 * 这里只读那个头，<b>不重新实现一遍鉴权</b>（token 前缀、Redis key 都只该有一份）。
 *
 * <p><b>拿不到 {@code X-User-Id} 必须拒绝连接</b>，只有两种可能：
 * <ol>
 *   <li>绕过网关直连了 8085；</li>
 *   <li>{@code /api/message/ws} 被错加进了网关白名单（白名单请求网关不注入这个头）。</li>
 * </ol>
 * 两种情况都必须 401 —— <b>绝不能接受一条没有身份的连接</b>：
 * 连接一旦建立，后面所有帧（ack、已读）的归属都靠它，
 * 收下一条匿名连接等于把"我是谁"交给客户端自己说。
 *
 * <p>连接建立之后，身份就存在<b>会话属性</b>里（{@link #ATTR_USER_ID}），
 * 后续每一帧都从会话里取 userId —— <b>永远不要相信帧里带的 userId</b>。
 */
@Component
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ChatHandshakeInterceptor.class);

    /** 会话属性名：这条连接属于谁 */
    public static final String ATTR_USER_ID = "userId";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String raw = request.getHeaders().getFirst(AuthHeaders.USER_ID);
        if (raw == null || raw.isBlank()) {
            log.warn("WebSocket 握手被拒：缺少 {} 请求头（绕过网关直连？或该路径被加进了白名单？）path={}",
                    AuthHeaders.USER_ID, request.getURI().getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        long userId;
        try {
            userId = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            // 头在但内容不是数字：网关不会注入这种值，说明有人直接连了本服务
            log.warn("WebSocket 握手被拒：{} 不是合法数字: {}", AuthHeaders.USER_ID, raw);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put(ATTR_USER_ID, userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 不需要做事。留个空实现只是为了满足接口。
    }

    /** 从会话里取 userId；握手时一定放进去了，取不到就是编程错误 */
    public static Long userIdOf(org.springframework.web.socket.WebSocketSession session) {
        Object value = session.getAttributes().get(ATTR_USER_ID);
        if (value instanceof Long userId) {
            return userId;
        }
        throw new IllegalStateException("会话里没有 userId —— 握手拦截器没生效？");
    }
}
