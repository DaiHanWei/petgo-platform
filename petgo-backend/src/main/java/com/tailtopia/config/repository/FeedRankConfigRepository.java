package com.tailtopia.config.repository;

import com.tailtopia.config.domain.FeedRankConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/** 推荐算法参数（单行 id=1，V1.1.6 Story 16.4）。 */
public interface FeedRankConfigRepository extends JpaRepository<FeedRankConfig, Long> {
}
