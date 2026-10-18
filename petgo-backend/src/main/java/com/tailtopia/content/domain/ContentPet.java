package com.tailtopia.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * 内容 ↔ 宠物（V1.4.0 multi-pet Story 3.1 · AD-10）。表 {@code content_pets}（迁移 {@code V20261018_1600}）。
 *
 * <p>一条 Diary（{@code GROWTH_MOMENT}）可关联多只宠物；按宠物的读口一律读本表。
 * {@code content_posts.pet_id} 仅保留为「第一只关联宠物」兼容字段（顺序 = 宠物创建顺序）。
 */
@Entity(name = "ContentPet")
@Table(name = "content_pets")
public class ContentPet {

    @EmbeddedId
    private Key id;

    protected ContentPet() {
    }

    public static ContentPet of(long postId, long petProfileId) {
        ContentPet p = new ContentPet();
        p.id = new Key(postId, petProfileId);
        return p;
    }

    public Long getPostId() {
        return id.postId;
    }

    public Long getPetProfileId() {
        return id.petProfileId;
    }

    /** 复合主键 (post_id, pet_profile_id)。 */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "post_id", nullable = false, updatable = false)
        private Long postId;

        @Column(name = "pet_profile_id", nullable = false, updatable = false)
        private Long petProfileId;

        protected Key() {
        }

        public Key(Long postId, Long petProfileId) {
            this.postId = postId;
            this.petProfileId = petProfileId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(postId, k.postId)
                    && Objects.equals(petProfileId, k.petProfileId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(postId, petProfileId);
        }
    }
}
