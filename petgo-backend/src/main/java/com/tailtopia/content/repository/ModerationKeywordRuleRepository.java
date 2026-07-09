package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ModerationKeywordRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 审核词库读写（内容审核 Story 1）。词库进程内缓存由 {@code KeywordRuleEngine} 持有，
 * 本仓库仅负责从库加载启用中的规则（{@code @PostConstruct} + 手动刷新）；V1 不上缓存中间件（护栏）。
 */
public interface ModerationKeywordRuleRepository extends JpaRepository<ModerationKeywordRule, Long> {

    /** 加载全部启用规则（供引擎装配进程内缓存）。 */
    List<ModerationKeywordRule> findByEnabledTrue();
}
