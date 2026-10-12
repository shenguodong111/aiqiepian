package com.aiqiepian.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：与 mock-server 一致，HTTP 状态码 = 响应体 code。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatus()) != null
                ? HttpStatus.valueOf(e.getStatus()) : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiResponse.fail(e.getStatus(), e.getMessage()));
    }

    /** 未定义路由：对齐 mock 的 `no route: METHOD path` 404 响应 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoRoute(NoResourceFoundException e) {
        return ResponseEntity.status(404)
                .body(ApiResponse.fail(404, "no route: " + e.getHttpMethod() + " /" + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("unhandled error", e);
        return ResponseEntity.status(500).body(ApiResponse.fail(500, "服务器内部错误：" + e.getMessage()));
    }
}
