package org.example.common.result;

import lombok.Data;

/**
 * 统一返回体。所有微服务对外返回的数据都包一层，前端/调用方解析规则一致。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> {

    /** 业务状态码，200 表示成功 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public static <T> Result<T> error(String message) {
        return error(500, message);
    }

    /**
     * 判断是否成功。调用方（尤其是 Feign 拿到返回值后）用它比直接比 code 更清晰。
     * 注意：不能叫 success()，会和上面的静态 success() 冲突。
     */
    public boolean isSuccess() {
        return code == 200;
    }
}
