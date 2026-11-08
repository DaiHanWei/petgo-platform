package com.tailtopia.topic.repository;

import com.tailtopia.topic.domain.ContentTopic;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 帖子 ↔ 话题关联读口（V1.4.2 social-topic · Story 4.1）。写入走 {@code TopicService} 的原生 UPSERT；
 * 批量取话题名走 {@code TopicQueryService.topicNamesFor}（整页一次，禁逐条）。
 */
public interface ContentTopicRepository extends JpaRepository<ContentTopic, ContentTopic.Key> {
}
