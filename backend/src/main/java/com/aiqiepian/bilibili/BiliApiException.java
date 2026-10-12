package com.aiqiepian.bilibili;

/**
 * B 站开放接口返回非 0 code 时抛出的异常。
 * 要求（M3）：不许吞错——code 与 message 原样保留，由上层写入 publish_task.error_msg。
 */
public class BiliApiException extends RuntimeException {

    private final int code;
    private final String biliMessage;

    public BiliApiException(int code, String biliMessage) {
        super("code=" + code + ": " + (biliMessage == null ? "(无 message)" : biliMessage));
        this.code = code;
        this.biliMessage = biliMessage;
    }

    public int getCode() {
        return code;
    }

    public String getBiliMessage() {
        return biliMessage;
    }

    /** 原样落库的失败原因（含真实 code+message，不吞错） */
    public String toErrorMsg() {
        return "code=" + code + ": " + (biliMessage == null ? "(无 message)" : biliMessage);
    }
}
