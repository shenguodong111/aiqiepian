package com.aiqiepian.bilibili;

/**
 * nav() 校验结果（M3 §1）。
 * code=0 且 login=true 视为 Cookie 有效；-101 表示未登录/Cookie 无效。
 */
public record NavInfo(int code, String message, boolean login, String uname, Long mid) {

    public static NavInfo notLoggedIn(int code, String message) {
        return new NavInfo(code, message, false, null, null);
    }
}
