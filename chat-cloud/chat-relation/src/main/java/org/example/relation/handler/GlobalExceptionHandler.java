package org.example.relation.handler;

import java.util.stream.Collectors;

import org.example.common.exception.BizException;
import org.example.common.result.Result;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
 * 全局异常处理：把异常统一翻译成 {@link Result}。
 *
 * <p><b>为什么每个服务都有一份，而不是抽到 chat-common？</b>
 * <ol>
 *   <li><b>技术上做不到</b>：这个类要 {@code @RestControllerAdvice} 和
 *       {@code MethodArgumentNotValidException}，都在 spring-web（Servlet 那套）里。
 *       而 chat-common 是被 <b>chat-gateway 依赖的</b>，网关是 WebFlux ——
 *       一旦 chat-common 拖进 spring-web，网关直接起不来。</li>
 *   <li><b>职责上也该分开</b>：每个服务要处理哪些异常、错误码怎么定，
 *       是它自己的选择。</li>
 * </ol>
 *
 * <p>现在 chat-relation 还没有业务接口，但<b>迟早要有</b>：
 * 好友申请、分组、黑名单都会抛 {@link BizException}（"对方已经是你的好友了"、
 * "不能加自己"……），没有这个类它们全都会变成 Spring 默认错误页
 * （不是 {@code Result} 结构），前端拿不到 {@code code}。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public Result<Void> handleParameterValidation(jakarta.validation.ConstraintViolationException e) {
        return Result.error(400, "请求参数不合法，请检查群号、搜索内容或分页位置");
    }

    /** 业务异常：service 主动抛的，属于"预期内的错误" */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 唯一索引冲突。
     *
     * <p>关系表一定会用到唯一索引（比如 {@code uk_user_friend} 防重复加好友），
     * 并发下两个请求可能同时通过代码里的查重，这时就是数据库拦下第二个。
     * 数据库原文是 "Duplicate entry '1-2' for key 'uk_user_friend'"，翻译成人话。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突: {}", e.getMessage());
        return Result.error(409, "该记录已存在，请刷新后重试");
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

    /** 请求体不是合法 JSON */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(400, "请求参数格式错误");
    }

    /** {@code @Valid} 校验失败（比如"验证消息"太长）：把所有字段错误拼成一句话 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage() == null
                        ? "参数不合法"
                        : error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败: {}", message);
        return Result.error(400, message.isEmpty() ? "请求参数不合法" : message);
    }

    /**
     * 缺少必填参数 / 参数类型不对 —— 都是"请求本身有问题"，报 400 而不是 500。
     *
     * <p>文案里不带参数名：用户看不懂字段名，那属于内部约定，只写日志。
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

    /**
     * 依赖不可用：数据库连不上，或者 chat-user 没有响应。
     *
     * <p>对用户来说这是同一件事 ——「等会儿再试」。是谁挂了属于运维信息（日志里有），
     * 界面上说太细既看不懂，也等于把内部结构告诉外人。用 503 表示"暂时、可重试"。
     */
    @ExceptionHandler({ DataAccessException.class, FeignException.class })
    public Result<Void> handleDependencyDown(Exception e) {
        log.warn("依赖不可用: {}", e.getClass().getSimpleName());
        return Result.error(503, "服务暂时不可用，请稍后重试");
    }

    /** 兜底：任何没预料到的异常，绝不把堆栈暴露给调用方 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("未预期的异常", e);
        return Result.error(500, "服务器开小差了，请稍后重试");
    }
}
