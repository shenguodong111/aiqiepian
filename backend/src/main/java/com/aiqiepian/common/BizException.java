package com.aiqiepian.common;

/**
 * 业务异常：携带 HTTP 状态码，由 GlobalExceptionHandler 转为 { code, data:null, message }。
 */
public class BizException extends RuntimeException {

    private final int status;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static BizException notFound() {
        return new BizException(404, "不存在");
    }
}
