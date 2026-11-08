package org.example.common.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.example.common.exception.BizException;
import org.example.common.result.Result;

/** 内部接口的调用凭证校验。生产部署同时需要内网访问控制及安全传输。 */
public final class InternalAuth {
    public static final String HEADER = "X-Internal-Token";

    private InternalAuth() {
    }

    public static void requireToken(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        actual.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(403, "内部接口访问被拒绝");
        }
    }

    /** HTTP 成功不一定代表业务成功，内部调用必须检查 Result。 */
    public static <T> T requireSuccess(Result<T> result) {
        if (result == null || !result.isSuccess()) {
            throw new BizException(503, "内部服务处理失败");
        }
        return result.getData();
    }
}
