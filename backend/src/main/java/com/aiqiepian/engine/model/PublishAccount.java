package com.aiqiepian.engine.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 发布账号（本期仅 bilibili，模拟 OAuth 绑定；持久化在 app_setting 的 publish_account 键） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublishAccount {
    private String platform;
    private String nickname;
    /** ISO-8601 字符串，与 mock 一致 */
    private String boundAt;
}
