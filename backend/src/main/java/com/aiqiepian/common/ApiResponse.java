package com.aiqiepian.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体：{ "code": 0, "data": ..., "message": "ok" }（与 mock-server 一致）。
 * 失败时 code = HTTP 状态码（400/404/500），data 为 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private int code;
    private T data;
    private String message;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, data, "ok");
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, null, message);
    }
}
