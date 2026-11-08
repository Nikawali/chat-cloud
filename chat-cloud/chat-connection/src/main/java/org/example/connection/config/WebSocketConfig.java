package org.example.connection.config;

import org.example.connection.ws.ChatHandshakeInterceptor;
import org.example.connection.ws.ChatWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import lombok.RequiredArgsConstructor;

/**
 * WebSocket 配置：把处理器挂到 {@code /message/ws} 上。
 *
 * <p><b>路径为什么是 {@code /message/ws}</b>：网关那条路由是 {@code Path=/api/message/ws} +
 * {@code StripPrefix=1}，去掉 {@code /api} 之后就是 {@code /message/ws} —— 和 HTTP 接口
 * 一个路径规律。只有 WS 路径进入连接服务，其他消息 HTTP 接口仍进入消息服务。
 *
 * <p>{@code setAllowedOriginPatterns("*")} 是给握手做跨域放行的：
 * Electron 渲染进程的页面来自 {@code file://}（Origin 是 {@code null}）或者
 * 开发时的 {@code http://localhost:5173}，这两个都不能用精确 Origin 匹配。
 * <b>生产环境要收紧成白名单</b>（和网关那份 CORS 配置一起改）。
 *
 * <p>注意这里<b>不做鉴权</b>：鉴权在 {@link ChatHandshakeInterceptor} 里
 * （读网关注入的 {@code X-User-Id}，没有就拒绝握手）。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final ChatHandshakeInterceptor chatHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/message/ws")
                .addInterceptors(chatHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
