package com.aiqiepian.engine;

import com.aiqiepian.engine.model.PublishAccount;
import com.aiqiepian.engine.model.PublishResult;
import com.aiqiepian.entity.Work;

/** 环节 06 发布（需求文档 §7）。真实实现替换：Bilibili OAuth + 投稿 API 回读。 */
public interface Publisher {

    PublishResult publish(Work work, PublishAccount account);
}
