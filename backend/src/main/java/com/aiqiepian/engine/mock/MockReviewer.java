package com.aiqiepian.engine.mock;

import com.aiqiepian.entity.Work;
import com.aiqiepian.engine.Reviewer;
import com.aiqiepian.engine.model.ReviewDecision;
import com.aiqiepian.engine.model.ReviewRules;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.aiqiepian.engine.mock.MockSupport.rnd;

/**
 * 规则审核（mock/real 共用，FR-P6-03）：敏感词命中 → 自动拒绝；
 * 评分 ≥ auto_pass 自动过，< auto_reject 自动拒，其间 MANUAL（关闭"不确定项交人工"则自动通过）。
 */
@Component
@ConditionalOnProperty(name = "engine.mode", havingValue = "mock", matchIfMissing = true)
public class MockReviewer implements Reviewer {

    @Override
    public ReviewDecision review(Work work, ReviewRules rules) {
        int score = work.getScore() == null ? 0 : work.getScore();
        int confidence = Math.min(99, score + rnd(-5, 5));

        String hit = firstSensitiveHit(work, rules.getSensitiveWords());
        if (hit != null) {
            return new ReviewDecision("AUTO_REJECT", 99, "命中敏感词黑名单「" + hit + "」，自动拒绝");
        }

        if (score >= rules.getAutoPassScore()) {
            return new ReviewDecision("AUTO_PASS", confidence,
                    "评分 " + score + " 达到自动通过线 " + rules.getAutoPassScore());
        } else if (score < rules.getAutoRejectScore()) {
            return new ReviewDecision("AUTO_REJECT", confidence,
                    "评分 " + score + " 低于自动拒绝线 " + rules.getAutoRejectScore());
        } else if (!rules.isManualOnUncertain()) {
            return new ReviewDecision("AUTO_PASS", confidence,
                    "评分 " + score + " 处于人工区间，但\"不确定项交人工\"已关闭，自动通过");
        } else {
            return new ReviewDecision("MANUAL", confidence,
                    "评分 " + score + " 处于人工区间 [" + rules.getAutoRejectScore() + ", "
                            + rules.getAutoPassScore() + ")，交人工处理");
        }
    }

    private static String firstSensitiveHit(Work work, List<String> words) {
        if (words == null || words.isEmpty()) return null;
        String text = (work.getTitle() == null ? "" : work.getTitle())
                + "\n" + (work.getDescription() == null ? "" : work.getDescription());
        for (String w : words) {
            if (!w.isEmpty() && text.contains(w)) return w;
        }
        return null;
    }
}
