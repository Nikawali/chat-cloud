package org.example.common.exception;

/**
 * 业务异常。
 *
 * <p>service 层遇到「预期内的错误」（手机号格式不对、验证码过期、用户名被占用……）时抛它，
 * 由各服务的全局异常处理器统一转成 {@code Result} 返回给前端。
 *
 * <p>这样 service 层<b>永远不用关心 web 那一套</b>，不需要返回 Result、也不需要 try-catch 到处包。
 *
 * <p>放在 chat-common 是因为它是纯 Java 类（不依赖任何 starter），所有服务都能复用。
 */
public class BizException extends RuntimeException {

    /** 业务状态码，默认 400（参数/业务校验失败） */
    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
