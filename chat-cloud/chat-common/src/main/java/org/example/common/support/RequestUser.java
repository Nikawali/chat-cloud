package org.example.common.support;

import org.example.common.constant.AuthHeaders;
import org.example.common.exception.BizException;

/**
 * "当前请求是谁" —— 各服务 controller 里那段完全一样的取身份逻辑。
 *
 * <p><b>为什么抽出来</b>：每个对外服务的第一行都要做同一件事 —— 把网关注入的
 * {@link AuthHeaders#USER_ID} 取出来，取不到就 401。这段代码以前在 4 个 controller 里
 * 各写了一遍（连文案都要求逐字一致），改文案时漏一处就会出现"同一个错误两种提示"。
 *
 * <p><b>为什么能放进 chat-common</b>：它只依赖 {@link BizException}，是纯 Java。
 * 这一点是这个模块的红线 —— chat-common 被 WebFlux 的 chat-gateway 依赖，
 * 凡是会拖进 spring-web / spring-webmvc 的东西（比如 {@code @RestControllerAdvice}、
 * {@code MethodArgumentNotValidException}）都不能放这里，否则网关起不来。
 */
public final class RequestUser {

    /**
     * 取不到身份信息时的统一文案。
     *
     * <p>它同时是给排查者的一条线索：出现这句只有两种可能 ——
     * 绕过网关直连了服务端口，或者这个路径被错误地加进了网关白名单
     * （白名单请求网关不校验 token，自然也就不会注入这个头）。
     */
    private static final String MISSING_MESSAGE = "未携带用户身份信息，请通过网关访问";

    /**
     * 取当前用户 id。
     *
     * @param userId controller 里 {@code @RequestHeader(AuthHeaders.USER_ID)} 拿到的值
     * @return 一定不为 null
     * @throws BizException 401 —— 没携带（见 {@link #MISSING_MESSAGE} 的说明）
     */
    public static Long requireId(Long userId) {
        if (userId == null) {
            throw new BizException(401, MISSING_MESSAGE);
        }
        return userId;
    }

    private RequestUser() {
    }
}
