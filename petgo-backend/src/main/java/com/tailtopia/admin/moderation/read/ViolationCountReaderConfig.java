package com.tailtopia.admin.moderation.read;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link ViolationCountReader} 占位实现装配（story 8，R1 端口占位方案）。
 *
 * <p>{@code @ConditionalOnMissingBean}：仅当容器中<b>没有</b>其它 {@link ViolationCountReader} 实现时才注册空实现。
 * story 9 合并后提供由 {@code violation_counts} 支撑的 {@code @Component} 实现，占位自动退场（AC8 届时验收真实计数）。
 */
@Configuration
public class ViolationCountReaderConfig {

    /** 空实现：全 0（各类型缺省），页面展示「—」。story 9 未接入时的独立可交付兜底。 */
    @Bean
    @ConditionalOnMissingBean(ViolationCountReader.class)
    public ViolationCountReader emptyViolationCountReader() {
        return accountRef -> Map.of();
    }
}
