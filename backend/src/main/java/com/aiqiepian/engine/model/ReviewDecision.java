package com.aiqiepian.engine.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Reviewer.review 输出：AUTO_PASS / AUTO_REJECT / MANUAL */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewDecision {
    /** AUTO_PASS / AUTO_REJECT / MANUAL */
    private String decision;
    private int confidence;
    private String reason;
}
