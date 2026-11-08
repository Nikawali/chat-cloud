package org.example.user.handler;

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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理。
 *
 * <p><b>这个类对 chat-user 特别重要</b>：它是被 Feign 调用的服务，
 * 如果不把异常转成 {@link Result}，Spring 会直接返回 HTTP 500，
 * 调用方（chat-auth）只能看到"请求失败"，拿不到真实原因，
 * 最后前端就只会显示"用户服务暂时不可用"——把"该手机号已注册"这种明确提示吞掉了。
 *
 * <p><b>一个原则：能用 400 表达的，别让它掉进兜底变成 500。</b>
 * 下面这几个"框架层异常"就属于这一类 —— 它们都是"请求本身有问题"，
 * 不是服务器故障。不单独处理的话全都会变成「服务器开小差了，请稍后重试」，
 * 用户完全不知道自己哪里填错了。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：service 主动抛的，属于"预期内的错误" */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 唯一索引冲突（并发下两个请求同时注册同一个手机号）。
     *
     * <p>现在唯一的业务唯一索引是 {@code uk_user_phone}（用户名允许重复，没有唯一索引），
     * 所以冲突只可能是手机号。数据库原文是 "Duplicate entry '138...' for key 'uk_user_phone'"，太原始。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突: {}", e.getMessage());
        return Result.error(409, "该手机号已注册");
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

    /**
     * {@code @Valid} 校验失败（DTO 上的字段校验没过）。
     *
     * <p>把每条字段错误拼成一句人话：前端只弹一个 message，
     * 一次把所有问题都告诉用户，比让他改一个提交一次好。
     */
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
     * 缺少必填的查询参数 —— 比如 {@code GET /user/check-phone} 没带 {@code ?phone=}。
     *
     * <p>不处理的话会掉进兜底变成 500「服务器开小差了」，而其实是前端少传了参数。
     * 文案里不带参数名：用户看不懂字段名，参数名属于内部约定。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return Result.error(400, "缺少必要的参数");
    }

    /**
     * 参数类型不对 —— 比如 {@code GET /user/abc}（{@code id} 要的是数字）。
     *
     * <p>不处理的话也会变成 500。这类问题很多是前端拼 URL 拼错了，
     * 报 400 才能让人一眼看出"是我传的东西不对"，而不是"服务器挂了"。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: name={}, value={}", e.getName(), e.getValue());
        return Result.error(400, "参数格式不正确");
    }

    /**
     * 【上传文件太大 → 413】。
     *
     * <p>这个异常是 <b>Spring 在进 controller 之前</b>抛的（{@code spring.servlet.multipart.max-file-size}
     * 那一层闸），所以我们自己的 {@code AvatarStorageService} 根本没机会说话。
     * 不单独接的话它会掉进兜底变成 500「服务器开小差了」—— 而真相是"你选的图太大了"，
     * 用户重试一百次也是一样的结果。
     *
     * <p>用 413（Payload Too Large）而不是 400：语义更准，前端也能据此单独提示。
     * 提示里带上上限（换算成 MB），用户才知道该压到多少。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        long maxMb = Math.max(1, e.getMaxUploadSize() / 1024 / 1024);
        log.warn("上传文件超过限制: maxUploadSize={}", e.getMaxUploadSize());
        return Result.error(413, "图片太大，请压缩到 " + maxMb + "MB 以内");
    }

    /**
     * 依赖不可用：MySQL / Redis 连不上。
     *
     * <p>对用户来说这就是"服务暂时不可用、等会儿再试"，具体是哪一层挂了属于运维信息，
     * 日志里有；界面上说太细既看不懂，也等于把内部结构告诉外人。
     * 用 503 而不是 500：语义是"暂时、可重试"。
     */
    @ExceptionHandler(DataAccessException.class)
    public Result<Void> handleDependencyDown(DataAccessException e) {
        log.warn("数据依赖不可用: {}", e.getClass().getSimpleName());
        return Result.error(503, "服务暂时不可用，请稍后重试");
    }

    /** 兜底：任何没预料到的异常，绝不把堆栈暴露给调用方 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("未预期的异常", e);
        return Result.error(500, "服务器开小差了，请稍后重试");
    }
}
