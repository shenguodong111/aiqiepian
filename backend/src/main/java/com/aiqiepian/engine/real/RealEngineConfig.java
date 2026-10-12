package com.aiqiepian.engine.real;

import com.aiqiepian.engine.Publisher;
import com.aiqiepian.engine.Reviewer;
import com.aiqiepian.engine.mock.MockPublisher;
import com.aiqiepian.engine.mock.MockReviewer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 真实引擎装配：监控/录制/分析/成片为 engine.real 下的 *Real Bean（自带 @Component 条件）。
 * 审核环节复用 MockReviewer——它并非随机模拟，而是需求 §3.3 第 05 环节的规则审核实现
 * （敏感词黑名单 + 自动通过/拒绝阈值 + 不确定项交人工），real 模式同样适用。
 * 发布在此注册 MockPublisher 仅为保证六环节调用链完整，实际注入被
 * {@link RealPublisher} 的 @Primary 覆盖。
 */
@Configuration
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class RealEngineConfig {

    @Bean
    public Reviewer reviewer() {
        return new MockReviewer();
    }

    @Bean
    public Publisher publisher() {
        return new MockPublisher();
    }
}
