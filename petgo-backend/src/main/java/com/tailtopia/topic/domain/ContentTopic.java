package com.tailtopia.topic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 帖子 ↔ 话题关联（V1.4.2 social-topic · Story 4.1 · D-2）。表 {@code content_topics}（迁移 {@code V20261104_2050}）。
 *
 * <p>写入一律走 {@code TopicService.attach} 的原生 {@code INSERT … ON CONFLICT DO NOTHING}；实体只用于读
 * （4.2 话题页 JPQL 会 join 它，所以必须是 JPA 实体）。审核中的帖也写关联，读时按帖子状态过滤。
 */
@Entity
@Table(name = "content_topics")
@IdClass(ContentTopic.Key.class)
public class ContentTopic {

    @Id
    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Id
    @Column(name = "topic_id", nullable = false)
    private Long topicId;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected ContentTopic() {
    }

    public Long getPostId() {
        return postId;
    }

    public Long getTopicId() {
        return topicId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 复合主键 {@code (post_id, topic_id)}。 */
    public static class Key implements Serializable {

        private Long postId;
        private Long topicId;

        public Key() {
        }

        public Key(Long postId, Long topicId) {
            this.postId = postId;
            this.topicId = topicId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key k)) {
                return false;
            }
            return Objects.equals(postId, k.postId) && Objects.equals(topicId, k.topicId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(postId, topicId);
        }
    }
}
