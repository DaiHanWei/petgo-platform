package com.tailtopia.topic.repository;

import com.tailtopia.topic.domain.Topic;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 话题读口（V1.4.2 social-topic · Story 4.1）。
 *
 * <p>⚠️ <b>不要</b>用 {@code findByNameNorm} + {@code save} 新建用户话题：并发时第二个事务撞唯一约束、整帖失败。
 * 新建一律走 {@code TopicService.attach} 的 {@code INSERT … ON CONFLICT (name_norm) DO NOTHING}（D-2）。
 */
public interface TopicRepository extends JpaRepository<Topic, Long> {

    Optional<Topic> findByNameNorm(String nameNorm);
}
