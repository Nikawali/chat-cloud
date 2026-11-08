package org.example.gateway.handler;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import org.example.gateway.support.JsonResponses;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 网关的"最后一跳"异常处理：<b>把网关自己的失败也回答成 {@code {code,message,data}} 的形状</b>。
 *
 * <p><b>为什么各微服务有 {@code @RestControllerAdvice}，网关还要单独一个？</b>
 * 因为网关是 WebFlux，而且它出错的地方在 <b>filter / 路由</b>里，不在 controller 里 ——
 * {@code @RestControllerAdvice} 只接 controller 抛出来的异常，接不到
 * "lb://chat-message 一个实例都没有"这类路由期的失败。
 *
 * <p>没有这个类会怎样（实测过）：网关返回的是 Spring 的默认错误 JSON
 * （{@code {timestamp,status,error,path}}），<b>没有 {@code code} 字段</b> ——
 * 前端 api 层是按 {@code code} 判断的，只能走"响应不是本后端的 Result 结构"那条兜底分支，
 * 用户看到的是"报了个看不懂的错"，而且<b>区分不出 401（该重新登录）和 503（该稍后重试）</b>。
 *
 * <p><b>为什么是"替换"而不是"叠加"</b>：Spring Boot 自己的
 * {@code ErrorWebFluxAutoConfiguration#errorWebExceptionHandler} 带
 * {@code @ConditionalOnMissingBean(ErrorWebExceptionHandler.class)} ——
 * 本类一旦注册成 bean，Boot 那个默认实现就自动退让，所有 WebFlux 异常都走这里。
 * （所以不需要靠 {@code @Order} 去抢优先级；{@code @Order} 只是显式表达"我要在最前面"。）
 *
 * <p><b>和业务服务的一个刻意差异</b>：这里返回的是<b>真正的 HTTP 状态码</b>
 * （401 就 401、503 就 503），而不是像下游那样"HTTP 200 + code != 200"。
 * 因为网关是流量的入口，让运维和 curl 一眼看出问题比"统一 200"重要；
 * 而 {@code JsonResponses} 会把 {@code code} 填成和状态码一致的值，前端两种读法都对得上。
 */
@Slf4j
@Order(-1)
@Component
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    /**
     * 处理一个异常。
     *
     * <p>三段判断，按"信息量"从高到低：
     * <ol>
     *   <li>{@link ResponseStatusException}：Spring 全家（含 Spring Cloud Gateway）表达
     *       "我知道这是 HTTP 层面的什么错"的标准方式，<b>直接用它带的状态码</b>。
     *       两个我们真正会遇到的例子：
     *       <ul>
     *         <li>{@code NotFoundException}（{@code lb://chat-message} 没有可用实例）——
     *             它继承 {@code ResponseStatusException} 且状态就是 <b>503</b>，
     *             于是"下游没起来"自动变成 503 而不是 500；</li>
     *         <li>路由匹配不上 / WebSocket 握手被拒 —— 4xx，原样透出。</li>
     *       </ul>
     *   <li>原因链里有连接失败 / 超时：说明是<b>下游不可达</b>，报 503（可重试）。</li>
     *   <li>其余：网关自己的问题，报 500，并且<b>日志打全栈</b> —— 这是唯一该被我们修的类别。</li>
     * </ol>
     *
     * <p>响应已经提交（比如 WebSocket 已经升级成功、字节已经开始往下发）时<b>不能再写</b>：
     * 这时只能把异常继续往上抛，让容器去关连接。
     */
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status = resolveStatus(ex);
        String message = resolveMessage(status, ex);

        if (status.is5xxServerError()) {
            // 5xx 是"我们的问题"，必须有完整堆栈（带 path 才有排查价值）
            log.error("网关处理失败: status={}, path={}", status.value(),
                    exchange.getRequest().getURI().getRawPath(), ex);
        } else {
            // 4xx 是"请求方的问题"（鉴权失败、路由不存在、握手被拒……），
            // 打 warn + 原因就够，堆栈只会淹没日志
            log.warn("网关拒绝了请求: status={}, path={}, 原因={}", status.value(),
                    exchange.getRequest().getURI().getRawPath(), rootMessage(ex));
        }

        return JsonResponses.write(exchange, status, status.value(), message);
    }

    /** 异常 → HTTP 状态码 */
    private static HttpStatus resolveStatus(Throwable ex) {
        for (Throwable t = ex; t != null; t = next(t)) {
            if (t instanceof ResponseStatusException rse) {
                HttpStatus resolved = HttpStatus.resolve(rse.getStatusCode().value());
                return resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
            }
            if (looksLikeDependencyFailure(t)) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** 状态码 → 给用户看的那句话 */
    private static String resolveMessage(HttpStatus status, Throwable ex) {
        if (status.is5xxServerError()) {
            // 5xx 的原始 message 往往是英文的技术细节（"Unable to find instance for chat-message"），
            // 不适合直接给终端用户看；它已经进日志了。
            // 两句都不提"网关""下游"这类词：用户不需要知道我们内部有几个服务，
            // 他只需要知道"现在用不了，等会儿再试"。
            return HttpStatus.SERVICE_UNAVAILABLE.equals(status)
                    ? "服务暂时不可用，请稍后重试"
                    : "服务器开小差了，请稍后重试";
        }
        // 4xx：优先用异常自带的 reason（比如"缺少 Authorization 头"这种我们自己写的文案）
        for (Throwable t = ex; t != null; t = next(t)) {
            if (t instanceof ResponseStatusException rse && StringUtils.hasText(rse.getReason())) {
                return rse.getReason();
            }
        }
        // 兜底不再拼状态码：状态码对用户没有意义
        return "请求无法处理，请稍后重试";
    }

    /**
     * 原因链里有没有"连不上下游"的特征。
     *
     * <p>为什么不是一个精确的类型判断：各家底层抛的超时异常各不相同
     * （{@code java.util.concurrent.TimeoutException}、Netty 的
     * {@code io.netty.handler.timeout.TimeoutException}……），逐个 import 会把
     * 网关和具体传输实现绑死。这里用"是不是 IOException / 是不是超时家族"来概括 ——
     * 判错了最多是一个 500 显示成 503，代价很小。
     */
    private static boolean looksLikeDependencyFailure(Throwable t) {
        // ConnectException / SocketException / "连接被重置" 全都是 IOException 的子类
        if (t instanceof IOException || t instanceof TimeoutException) {
            return true;
        }
        return t.getClass().getSimpleName().contains("Timeout");
    }

    /**
     * 往下一层原因走。
     *
     * <p>{@code t == t.getCause()} 这种自引用的异常理论上不存在，但异常处理器自己死循环
     * 会比"没处理好一个异常"严重得多，所以顺手挡一下。
     */
    private static Throwable next(Throwable t) {
        Throwable cause = t.getCause();
        return cause == t ? null : cause;
    }

    /** 取最内层的原因信息，只用于日志 */
    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (next(t) != null) {
            t = next(t);
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
