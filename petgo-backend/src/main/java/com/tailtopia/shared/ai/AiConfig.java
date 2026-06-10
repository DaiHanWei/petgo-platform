package com.petgo.shared.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 基础设施装配（Story 4.1）。绑定 {@link GeminiProperties}（前缀 {@code petgo.ai.gemini}）并按
 * {@code mode} 选择 {@link GeminiClient} 实现：
 * <ul>
 *   <li>{@code mode=live} → {@link GeminiDeveloperApiClient}（真实 Gemini，L2，需 {@code GEMINI_API_KEY}）</li>
 *   <li>否则（默认 {@code stub}）→ {@link StubGeminiClient}（免凭证，L0/L1 验状态机）</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(GeminiProperties.class)
public class AiConfig {

    @Bean
    public GeminiClient geminiClient(GeminiProperties props) {
        if ("live".equalsIgnoreCase(props.getMode())) {
            return new GeminiDeveloperApiClient(props);
        }
        return new StubGeminiClient();
    }
}
