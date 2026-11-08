package org.example.relation.handler;

import org.example.common.result.Result;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 兜底错误端点：把"没走到 {@code @RestControllerAdvice}"的失败也回答成 {@link Result} 形状。
 *
 * <p><b>为什么还缺这一块。</b>{@code @RestControllerAdvice} 只接 <b>controller 里抛出来</b>的异常。
 * 但请求失败不一定发生在那里 —— 参数还没进 controller 就被容器挡掉、异步请求超时、
 * 响应流中途出错…… 这些会被 Servlet 容器交给 {@code /error}，由 Spring Boot 自己的
 * {@code BasicErrorController} 处理，返回的是
 * <b>{@code {timestamp,status,error,path}}</b> —— 没有 {@code code}、也没有 {@code message}。
 *
 * <p>后果是前端那一层读不到任何文案，只能说一句"请求失败（HTTP 500）"：
 * 用户看不懂，我们也拿不到线索。所以这里实现 {@link ErrorController}（Boot 4 里包名
 * 从 {@code boot.web.servlet.error} 挪到了 {@code boot.webmvc.error}）把默认实现顶掉，
 * 统一回 {@code Result}，并把<b>真正的异常类型</b>打进日志 —— 那个才是排查的线索，
 * 用户界面只要一句人话。
 *
 * <p><b>和 {@code GlobalExceptionHandler} 的分工</b>：能进 controller 的走那边；
 * 进不了 controller 的走这里。两处都必须存在，否则总有一条路会漏出"看不懂的错"。
 */
@Slf4j
@RestController
public class ApiErrorController implements ErrorController {

    /**
     * 处理容器转过来的错误请求。
     *
     * <p>这里<b>刻意保留真实的 HTTP 状态码</b>（而不是像业务层那样"HTTP 200 + code"）：
     * 走到这里的已经是不正常的失败了，让运维和 curl 一眼看出是 4xx 还是 5xx 更有价值；
     * 前端两种读法都支持（见 api/index.js）。
     */
    @RequestMapping("/error")
    public ResponseEntity<Result<Void>> handleError(HttpServletRequest request) {
        int status = resolveStatus(request);
        Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        log.warn("请求在 controller 之外失败: status={}, path={}, exception={}",
                status,
                request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
                exception == null ? "-" : exception.getClass().getName());

        HttpStatus httpStatus = HttpStatus.resolve(status);
        if (httpStatus == null) {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(httpStatus).body(Result.error(status, messageFor(status)));
    }

    /** 容器把状态码放在 request 属性里；拿不到就按 500 算（宁可视作服务端故障，也不假装成功） */
    private static int resolveStatus(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        return value instanceof Integer code ? code : 500;
    }

    /**
     * 状态码 → 一句人话。
     *
     * <p>和网关、以及各服务 {@code GlobalExceptionHandler} 里的措辞保持一致：
     * 用户在哪儿看到这个错，说的都该是同一句话。
     */
    private static String messageFor(int status) {
        return switch (status) {
            case 400 -> "请求有误，请稍后重试";
            case 401 -> "登录已过期，请重新登录";
            case 403 -> "没有权限访问";
            case 404 -> "请求的地址不存在";
            case 405 -> "请求方式不正确";
            case 408 -> "请求超时，请重试";
            case 413 -> "内容太大，请选择小一点的文件";
            case 415 -> "不支持这种请求格式";
            case 429 -> "操作太频繁，请稍后再试";
            case 503 -> "服务暂时不可用，请稍后重试";
            default -> status >= 500 ? "服务器开小差了，请稍后重试" : "请求失败，请稍后重试";
        };
    }
}
