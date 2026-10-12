package com.aiqiepian.engine.real;

import com.aiqiepian.engine.Publisher;
import com.aiqiepian.engine.Reviewer;
import com.aiqiepian.engine.mock.MockPublisher;
import com.aiqiepian.engine.mock.MockReviewer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 真实引擎装配：监控/录制/成片为 engine.real 下的 *Real Bean（自带 @Component 条件），
 * 分析环节 M2 起由 RealAnalyzer（@Component + 同条件）接管，不再注册 mock 占位；
 * 审核/发布仍复用 mock 实现作为占位（不修改 engine/mock 源码，仅在 real 模式下补充注册），
 * 保证六环节接口调用链在 real 模式下完整可跑。
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
