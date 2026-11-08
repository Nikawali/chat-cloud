package org.example.gateway.filter;

import java.util.List;

import org.example.common.constant.AuthHeaders;
import org.example.gateway.config.AuthProperties;
import org.example.gateway.support.JsonResponses;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 网关统一鉴权过滤器 —— <b>整个系统"我是谁"的唯一入口</b>。
 *
 * <p>做四件事，顺序不能变：
 * <ol>
 *   <li><b>剥离客户端伪造的内部头</b>（{@code X-User-*}）。
 *       这一步是整套方案的安全根基：网关是唯一有权写入 {@code X-User-Id} 的地方，
 *       如果不先剥干净，任何人拿 Postman 手写一个 {@code X-User-Id: 1}
 *       就能冒充用户 1。</li>
 *   <li>白名单直接放行（注册/登录/发验证码/取公钥这些"用户还没证明自己"的接口）。</li>
 *   <li>从 {@code Authorization: Bearer xxx} 取 token，
 *       去 Redis 查 {@code auth:token:{token}} 拿到 userId（查不到 = 无效/过期/被新登录顶掉）。</li>
 *   <li>把 userId 写进 {@code X-User-Id} 请求头，转发给下游服务。</li>
 * </ol>
 *
 * <p><b>为什么放在网关而不是各服务自己做？</b>
 * 认证是所有请求都要做的一件事，而网关是所有请求都必须经过的唯一地方。
 * 放这里只连一次 Redis、只维护一份白名单、只写一份 401 处理；
 * 否则 4 个服务要各写一遍，将来换方案要改 4 处，漏一处就是漏洞。
 *
 * <p><b>注意这里只做"认证"（你是谁），不做"授权"（你能不能干这件事）。</b>
 * 比如"只能删自己的好友"，属于业务规则，应该在 chat-relation 里判断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /**
     * 执行顺序：数字越小越先执行。
     * 取 -100 是为了排在 {@code NettyRoutingFilter}（约 Integer.MAX_VALUE）以及
     * Spring Cloud Gateway 其它内置过滤器之前 —— 未通过鉴权的请求<b>绝不能</b>被转发出去。
     */
    private static final int ORDER = -100;

    private final AuthProperties authProperties;
    private final ReactiveStringRedisTemplate redisTemplate;

    /**
     * 需要从客户端请求里剥离的内部头名字。
     *
     * <p><b>以后新增内部头，记得往这里加一个</b> —— 否则它就能被客户端伪造。
     * 下面的静态代码块会校验每个名字都符合内部头命名约定（{@code X-User-} 前缀），
     * 加错了在类加载时就直接炸，不会静默漏掉。
     */
    private static final List<String> INTERNAL_HEADER_NAMES = List.of(
            AuthHeaders.USER_ID);

    static {
        for (String name : INTERNAL_HEADER_NAMES) {
            if (!AuthHeaders.isInternal(name)) {
                throw new IllegalStateException(
                        "内部头命名必须符合 " + AuthHeaders.INTERNAL_PREFIX + " 约定: " + name);
            }
        }
    }

    /** Ant 风格路径匹配（{@code /api/user/**} 这种）。无状态，可以安全复用 */
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // ---- ① 先剥离客户端可能伪造的内部头。必须在任何判断之前 ----
        ServerHttpRequest cleaned = stripInternalHeaders(request);

        // ---- ② 白名单放行 ----
        if (isWhitelisted(path)) {
            log.debug("白名单路径，直接放行: {}", path);
            return chain.filter(exchange.mutate().request(cleaned).build());
        }

        // ---- ③ 取 token ----
        String token = extractBearerToken(cleaned);
        if (token == null) {
            return unauthorized(exchange, "未登录或登录已过期，请先登录");
        }

        // ---- ④ 查 Redis 换 userId，并写入 X-User-Id ----
        // 用 flatMap 而不是 block()：Gateway 是响应式的，
        // 一旦在过滤器里阻塞，事件循环线程被占住，整个网关就卡死了。
        //
        // ⚠️ 这里【不能】在 flatMap 后面挂 switchIfEmpty，原因很容易踩：
        //    chain.filter(...) 返回的是 Mono<Void>，它是"完成但不发任何数据"的。
        //    switchIfEmpty 的判据正是"上游完成却没发数据" —— 所以转发成功之后
        //    它照样会触发，再往一个已经写完的响应里写 401，整个请求就废了。
        //    正确做法是用 defaultIfEmpty 把"空"提前变成一个可判断的值，
        //    让 flatMap 只进入一次、chain.filter 只执行一次。
        return redisTemplate.opsForValue()
                .get(authProperties.getTokenKeyPrefix() + token)
                .defaultIfEmpty("")
                .flatMap(userId -> {
                    if (userId.isEmpty()) {
                        // Redis 里没有这个 key：token 不存在、已过 7 天、或已被新登录顶掉
                        return unauthorized(exchange, "未登录或登录已过期，请先登录");
                    }
                    ServerHttpRequest authed = cleaned.mutate()
                            .headers(headers -> headers.set(AuthHeaders.USER_ID, userId))
                            .build();
                    log.debug("鉴权通过: path={}, userId={}", path, userId);
                    return chain.filter(exchange.mutate().request(authed).build());
                });
    }

    /**
     * 把客户端传来的内部头全部删掉。
     *
     * <p>名单就是上面的 {@link #INTERNAL_HEADER_NAMES}，并且静态校验过它们都符合
     * {@link AuthHeaders#INTERNAL_PREFIX} 命名约定。
     *
     * <p><b>为什么不遍历 {@code headers.keySet()} 再 removeIf？</b>
     * Spring Framework 7 的 {@code HttpHeaders} 是<b>破坏性变更</b>：
     * 它不再实现 {@code MultiValueMap}（改成 {@code implements Serializable}），
     * {@code keySet()} 已被删除，替代品是 {@code headerNames()}；
     * 而且 {@code remove(String)} 的返回类型也从 {@code boolean} 变成了 {@code List<String>}。
     * 这里改成对可能的名字直接调 {@code remove(name)} ——
     * {@code getFirst} / {@code remove} 只依赖"按名字取/删"这个稳定契约，
     * 不受键集合类型变化影响，最稳。名单很短，逐个清的成本可以忽略。
     */
    private ServerHttpRequest stripInternalHeaders(ServerHttpRequest request) {
        ServerHttpRequest.Builder builder = null;
        for (String name : INTERNAL_HEADER_NAMES) {
            if (request.getHeaders().getFirst(name) == null) {
                continue;
            }
            if (builder == null) {
                builder = request.mutate();
            }
            builder.headers(headers -> headers.remove(name));
            log.warn("检测到客户端伪造的内部请求头，已剥离: name={}, path={}", name,
                    request.getPath().value());
        }
        return builder == null ? request : builder.build();
    }

    private boolean isWhitelisted(String path) {
        List<String> whitelist = authProperties.getWhitelist();
        if (whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        for (String pattern : whitelist) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 {@code Authorization} 头里取出 token，兼容带不带 {@code Bearer } 前缀两种写法。
     *
     * <p>前端有时图省事直接塞裸 token，没必要因此报 401，所以这里宽松处理。
     *
     * @return 取不到返回 null
     */
    private String extractBearerToken(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(AuthHeaders.AUTHORIZATION);//获取请求头
        if (!StringUtils.hasText(authorization)) {
            return null;
        }
        String token = authorization;
        if (authorization.regionMatches(true, 0, AuthHeaders.BEARER_PREFIX, 0,
                AuthHeaders.BEARER_PREFIX.length())) //比较请求头是否含AuthHeaders.BEARER_PREFIX的值 即（Bearer）
        {
            token = authorization.substring(AuthHeaders.BEARER_PREFIX.length());//从Bearer开始获取后续字符串
        }
        token = token.trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 返回 401（响应体是 {@code Result} 形状）。
     *
     * <p>具体为什么必须和 Result 同构、为什么手写 JSON，见 {@link JsonResponses} 的类注释。
     * 这里只是转发一下 —— 限流过滤器也要写 429，那份逻辑就共用在那边的工具类里。
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        return JsonResponses.write(exchange, HttpStatus.UNAUTHORIZED, 401, message);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
