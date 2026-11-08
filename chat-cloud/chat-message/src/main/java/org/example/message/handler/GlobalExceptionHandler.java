package org.example.message.handler;

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

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理：把异常统一翻译成 {@link Result}，controller / service 里就不用写 try-catch。
 *
 * <p><b>为什么每个服务都有一份，而不是抽到 chat-common？</b>
 * 两个原因：
 * <ol>
 *   <li><b>技术上做不到</b>：这个类要 {@code @RestControllerAdvice} 和
 *       {@code MethodArgumentNotValidException}，都在 spring-web（Servlet 那套）里。
 *       而 chat-common 是被 <b>chat-gateway 依赖的</b>，网关是 WebFlux ——
 *       一旦 chat-common 拖进 spring-web，网关直接起不来。</li>
 *   <li><b>职责上也该分开</b>：每个服务要处理哪些异常、错误码怎么定、文案怎么写，
 *       是它自己的选择（chat-user 要处理唯一索引冲突，chat-message 就没有数据库）。</li>
 * </ol>
 *
 * <p>没有这个类会怎样：任何异常都会变成 Spring 的默认错误页
 * （{@code {timestamp, status, error, path}}），<b>不是 Result 结构</b> ——
 * 前端 api 层是按 {@code code} 字段判断的，拿不到就会走进兜底分支，
 * 用户体验是"报了个看不懂的错"。
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

    /** 请求体不是合法 JSON */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(400, "请求参数格式错误");
    }

    /**
     * 【路径不存在 → 404】。
     *
     * <p><b>为什么必须单独处理它</b>：从 Spring Boot 3.2 起，未匹配到 controller 的 GET 请求
     * 会落到"静态资源处理器"上，它找不到文件就抛 {@code NoResourceFoundException}。
     * 这个异常如果不单独接，就会掉进下面的兜底变成
     * <b>"HTTP 200 + code 500 服务器开小差了"</b> —— 排查时最要命的一种假象：
     * 明明是 URL 写错了（或者 WebSocket 端点没生效），看起来却像服务器故障。
     * （实测踩过：一个握手请求打过来，返回 200 而不是 404，让人以为是鉴权问题。）
     *
     * <p>返回给用户的文案里<b>不带路径</b>：真实软件不会把内部地址念给用户听，
     * 而且路径里可能带 id 之类的信息。路径只写进日志。
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

    /**
     * 唯一索引冲突。
     *
     * <p>消息服务里有两处唯一索引会撞：{@code uk_message_sender_client}（消息幂等）和
     * {@code uk_conversation_single}（单聊会话唯一）—— 这两处<b>代码里都主动 catch 了</b>
     * （撞上属于正常并发，应该返回已存在的那条而不是报错）。
     * 所以走到这里的，是没预料到的冲突，给个明确的 409 而不是 500。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("未预期的唯一约束冲突: {}", e.getMessage());
        return Result.error(409, "该记录已存在，请刷新后重试");
    }

    /** {@code @Valid} 校验失败：把所有字段错误拼成一句话返回 */
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
     * 缺少必填参数 / 参数类型不对。
     *
     * <p>这两个不单独处理的话会掉进兜底变成 500「服务器开小差了」——
     * 但它们其实是"请求本身有问题"，报 400 才能让人一眼看出是调用方传错了，
     * 而不是服务器挂了。
     *
     * <p>文案里<b>不带参数名</b>：用户看不懂 {@code peerUserId} 这种字段，
     * 而参数名属于内部约定，具体是哪个值不对只写日志。
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
     * 上传的文件超过 multipart 限额（{@code spring.servlet.multipart.max-file-size}）。
     *
     * <p>这个异常在<b>请求还没进 controller</b> 时就抛了，属于"请求本身太大"。
     * 不单独处理的话会掉进兜底变成 500「服务器开小差了」—— 用户看到"服务器挂了"，
     * 实际上只是他选了一个 60MB 的视频。413 才是准确的语义。
     *
     * <p>文案里给出上限：用户知道该压到多少，才不会反复试同一个文件。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过限额: {}", e.getMessage());
        return Result.error(413, "文件太大，请选择小一点的文件（最大 50MB）");
    }

    /**
     * 依赖不可用：数据库 / Redis 连不上，或者下游服务（Feign）没有响应。
     *
     * <p>为什么合成一个处理器、用同一句话：对用户来说这些是同一件事 ——「等会儿再试」。
     * 到底是哪个依赖挂了属于运维信息（日志里有），说太细反而泄露内部结构，
     * 也不会帮用户做任何决定。
     *
     * <p>用 503 而不是 500：语义是"暂时不可用、可重试"，监控和前端都能据此区分
     * "这次是运气不好"和"这次是代码出错"。
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
