package com.aiqiepian.engine.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 审核规则（阈值与黑名单来自 app_setting，修改即时生效） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRules {
    private int autoPassScore;
    private int autoRejectScore;
    /** FR-P6-03 敏感词黑名单：作品标题/简介命中任一 → AUTO_REJECT */
    private List<String> sensitiveWords;
    /** FR-P6-03 不确定项交人工：关闭时人工区间自动通过 */
    private boolean manualOnUncertain;

    public ReviewRules(int autoPassScore, int autoRejectScore) {
        this(autoPassScore, autoRejectScore, List.of(), true);
    }
}
