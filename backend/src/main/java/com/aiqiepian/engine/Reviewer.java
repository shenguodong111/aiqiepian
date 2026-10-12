package com.aiqiepian.engine;

import com.aiqiepian.entity.Work;
import com.aiqiepian.engine.model.ReviewDecision;
import com.aiqiepian.engine.model.ReviewRules;

/** 环节 05 审核（需求文档 §7）。真实实现替换：内容安全模型判定。 */
public interface Reviewer {

    ReviewDecision review(Work work, ReviewRules rules);
}
