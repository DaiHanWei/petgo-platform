package com.tailtopia.growth.repository;

import com.tailtopia.growth.domain.GrowthRule;
import org.springframework.data.jpa.repository.JpaRepository;

/** 成长值规则配置仓储（V1.4.0 ① Story 1.1）。量很小，每次入账读库，不加缓存（护栏）。 */
public interface GrowthRuleRepository extends JpaRepository<GrowthRule, String> {
}
