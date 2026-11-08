package org.example.auth.handler;

import org.example.common.exception.BizException;
import org.example.common.result.Result;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理。
 *
 * <p>把各种异常统一转成 {@link Result}，好处是：
 * <ul>
 *   <li>controller / service 里<b>一个 try-catch 都不需要</b></li>
 *   <li>service 层不用关心 web，业务代码更纯粹</li>
 *   <li>错误格式全站统一，前端只认一套结构</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * DTO 上的格式校验失败（由 controller 的 {@code @Valid} 触发）。
     *
     * <p>取第一条错误信息返回即可 —— 前端一次也只提示一条，
     * 而且字段在 DTO 里是按声明顺序校验的，所以提示顺序是稳定的。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", message);
        return Result.error(400, message);
    }

    /**
     * 【路径不存在 → 404】。
     *
     * <p><b>为什么必须单独处理它</b>：从 Spring Boot 3.2 起，未匹配到 controller 的 GET 请求
     * 会落到"静态资源处理器"上，找不到文件就抛 {@code NoResourceFoundException}。
     * 不单独接的话它会掉进最下面的兜底，变成 <b>"HTTP 200 + code 500 服务器开小差了"</b> ——
     * 明明是 URL 写错了，看起来却像服务器故障（排查时最误导人的一种假象）。
     *
     * <p>返回文案里不带路径：内部地址不该念给用户听，路径只写日志。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNotFound(NoResourceFoundException e) {
        log.warn("路径不存在: {}", e.getResourcePath());
        return Result.error(404, "请求的地址不存在");
    }

    /** 路径不存在（另一种抛法：关掉了静态资源映射时会走这个） */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandler(NoHandlerFoundException e) {
        log.warn("没有处理器能处理该请求: {} {}", e.getHttpMethod(), e.getRequestURL());
        return Result.error(404, "请求的地址不存在");
    }

    /** HTTP 方法不对（比如该 POST 的接口用 GET 调）—— 报 405 而不是 500 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("HTTP 方法不支持: {}", e.getMessage());
        return Result.error(405, "请求方式不正确");
    }

    /** 请求格式不支持（比如接口要 JSON，客户端发了表单） */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Result<Void> handleMediaType(HttpMediaTypeNotSupportedException e) {
        log.warn("请求格式不支持: {}", e.getContentType());
        return Result.error(415, "不支持这种请求格式");
    }

    /** 请求体不是合法 JSON（比如前端少传了引号、传了空 body） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(400, "请求参数格式错误");
    }

    /**
     * 缺少必填参数 / 参数类型不对 —— <b>报 400，而不是掉进兜底变 500</b>。
     *
     * <p>这两个曾经是本服务唯一没接的：另外三个服务都有，只有这里没有。
     * 差别不只是"一致性"：本服务有一个
     * {@code @RequestHeader(value = X-User-Id, required = false) Long userId}，
     * 一旦有人绕过网关直连 8081 并塞个非数字的 {@code X-User-Id}，
     * 类型转换就会失败 —— 没有这两个处理器的话，用户看到的是"服务器开小差了（500）"，
     * 而真相是"你传的参数不对（400）"。排查方向完全不同。
     *
     * <p>文案里不带参数名：字段名属于内部约定，只写日志。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return Result.error(400, "缺少必要的参数");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: name={}, value={}", e.getName(), e.getValue());
        return Result.error(400, "参数格式不正确");
    }

    /** 业务异常：service 主动抛的，属于"预期内的错误"，日志用 warn 就够了 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 依赖不可用：Redis（token / 验证码）连不上，或者 chat-user 没有响应。
     *
     * <p>对用户来说这是同一件事 ——「等会儿再试」。是谁挂了属于运维信息（日志里有），
     * 界面上说太细既看不懂，也等于把内部结构告诉外人。用 503 表示"暂时、可重试"。
     */
    @ExceptionHandler({ DataAccessException.class, FeignException.class })
    public Result<Void> handleDependencyDown(Exception e) {
        log.warn("依赖不可用: {}", e.getClass().getSimpleName());
        return Result.error(503, "服务暂时不可用，请稍后重试");
    }

    /** 兜底：任何没预料到的异常，返回统一提示，绝不把堆栈暴露给前端 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("未预期的异常", e);
        return Result.error(500, "服务器开小差了，请稍后重试");
    }
}
