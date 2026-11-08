package org.example.connection.handler;

import org.example.common.exception.BizException;
import org.example.common.result.Result;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException exception) {
        return Result.error(exception.getCode(), exception.getMessage());
    }
}
