package org.example.gateway.support;

import java.nio.charset.StandardCharsets;

import org.example.common.result.Result;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 在网关里直接写出一个 {@link Result} 形状的响应（目前是鉴权失败的 401）。
 *
 * <p><b>响应体必须和 Result 的字段结构完全一致</b>（code / message / data），
 * 因为前端 api 层是按业务码判断的：
 * <pre>
 *   if (data.code === 401) clearLogin()
 * </pre>
 * 如果这里返回一个裸的 401 空响应体，前端就走不到"清除本地凭证"这个分支，
 * 用户会卡在"已登录但所有请求都失败"的状态 —— 这是最难查的对接问题之一。
 *
 * <p>手写 JSON 而不用 ObjectMapper：一是结构固定（三个字段），
 * 二是避免依赖 Jackson 的自动配置，少一个启动期的不确定因素。
 *
 * <p>抽成工具类是<b>为将来做准备</b>：网关里凡是"自己生成错误响应"的过滤器
 * （限流 429、熔断 503……）都要写同一种结构。现在只有鉴权一处用它，
 * 但把这段 JSON 拼接 + 转义留在这里，比散在各个过滤器里各写一遍好。
 */
public final class JsonResponses {

    /**
     * 写一个 {@code {code, message, data:null}} 的响应。
     *
     * @param exchange 当前交换
     * @param status   HTTP 状态码（401 / 429……）
     * @param code     业务码，通常和 status 一致
     * @param message  提示文案
     */
    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status,
                                   int code, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        // 显式带上 UTF-8：不带的话中文提示在部分客户端会显示成乱码
        response.getHeaders().setContentType(
                new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));

        String body = "{\"code\":" + code
                + ",\"message\":\"" + escapeJson(message)
                + "\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 把字符串转义成能安全放进 JSON 双引号里的形式。
     *
     * <p>当前 message 都是我们自己写的常量，但仍然写成通用实现 ——
     * 以后如果拼了用户输入（比如把请求路径带回提示里），不至于突然出现 JSON 注入。
     */
    private static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private JsonResponses() {
    }
}
