package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentPet;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code content_pets} 仓储（V1.4.0 multi-pet Story 3.1 · AD-10）。
 *
 * <p>「第一只」统一口径（全仓只此一个）：关联宠物顺序 = 宠物创建顺序 {@code pet_profiles.created_at ASC, id ASC}。
 * 两个读口 join {@code pet_profiles} 只为取这个顺序，不读任何宠物字段。
 */
public interface ContentPetRepository extends JpaRepository<ContentPet, ContentPet.Key> {

    /** 某帖关联的全部宠物 id，按宠物创建顺序；无关联 → 空表。 */
    @Query(value = """
            SELECT cp.pet_profile_id
              FROM content_pets cp
              JOIN pet_profiles pp ON pp.id = cp.pet_profile_id
             WHERE cp.post_id = :postId
             ORDER BY pp.created_at, pp.id
            """, nativeQuery = true)
    List<Long> findPetIdsOfPost(@Param("postId") long postId);

    /**
     * 批量版（一条查询，禁 N+1）：每行 {@code [postId, petId]}，同帖内按宠物创建顺序。
     */
    @Query(value = """
            SELECT cp.post_id, cp.pet_profile_id
              FROM content_pets cp
              JOIN pet_profiles pp ON pp.id = cp.pet_profile_id
             WHERE cp.post_id IN (:postIds)
             ORDER BY cp.post_id, pp.created_at, pp.id
            """, nativeQuery = true)
    List<Object[]> findPetIdsOfPosts(@Param("postIds") Collection<Long> postIds);
}
