package com.tailtopia.content.moderation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内容审核基础设施装配（内容审核 Story 1）。绑定 {@link ModerationProperties}（前缀 {@code app.moderation}）
 * 并按 {@code mode} 选择 {@link ContentSafetyClient} 实现：
 * <ul>
 *   <li>{@code mode=live} → {@link AliyunContentSafetyClient}（真连阿里云 green20220302，L2；当前 fail-closed 占位）</li>
 *   <li>否则（默认 {@code stub}）→ {@link StubContentSafetyClient}（免凭证，L0/L1 验评分/降级状态机）</li>
 * </ul>
 * 熔断器为进程内自研（不引入 Resilience4j/Hystrix，护栏）。
 */
@Configuration
@EnableConfigurationProperties(ModerationProperties.class)
public class ModerationConfig {

    @Bean
    public ContentSafetyClient contentSafetyClient(ModerationProperties props) {
        if ("live".equalsIgnoreCase(props.getMode())) {
            return new AliyunContentSafetyClient(props);
        }
        return new StubContentSafetyClient();
    }

    @Bean
    public ModerationCircuitBreaker moderationCircuitBreaker() {
        return new ModerationCircuitBreaker();
    }
}
