package com.aiqiepian.engine.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Publisher.publish 输出（mock：90% 成功；失败带原因） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublishResult {
    private boolean success;
    private String errorMsg;
}
