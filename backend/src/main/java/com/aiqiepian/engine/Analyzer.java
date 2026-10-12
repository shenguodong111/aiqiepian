package com.aiqiepian.engine;

import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.RecordingSession;

import java.util.List;

/** 环节 03 分析（需求文档 §7）。真实实现替换：ASR + 弹幕密度 + 场景切分。 */
public interface Analyzer {

    /** 返回候选片段（未落库）；mock 每会话随机 3-8 个、评分 60-98 */
    List<Clip> analyze(RecordingSession session);
}
