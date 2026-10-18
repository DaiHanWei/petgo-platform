package com.tailtopia.content.service;

import com.tailtopia.content.repository.ContentPetRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容关联宠物只读口（V1.4.0 multi-pet Story 3.1 · AD-10；③ 送礼与 ① 成长值依赖，<b>签名定死</b>）。
 *
 * <p>顺序 = 宠物创建顺序（「第一只」统一口径，与 {@code content_posts.pet_id} / {@code ContentPublishedEvent.petId} 同一只）。
 * <b>不判帖子可见性</b>，调用方各自判。
 */
@Service
public class ContentPetQuery {

    private final ContentPetRepository contentPets;

    public ContentPetQuery(ContentPetRepository contentPets) {
        this.contentPets = contentPets;
    }

    /** 该帖关联的全部宠物 id，按宠物创建顺序；无关联 → 空表。 */
    @Transactional(readOnly = true)
    public List<Long> petsOf(long postId) {
        return List.copyOf(contentPets.findPetIdsOfPost(postId));
    }

    /** 批量版（一条查询）：{@code postId → 宠物 id 列表}（创建顺序）；没有关联的帖不在 Map 里。 */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> petsOfAll(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = postIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> out = new LinkedHashMap<>();
        for (Object[] row : contentPets.findPetIdsOfPosts(ids)) {
            long postId = ((Number) row[0]).longValue();
            long petId = ((Number) row[1]).longValue();
            out.computeIfAbsent(postId, k -> new java.util.ArrayList<>()).add(petId);
        }
        out.replaceAll((k, v) -> List.copyOf(v));
        return out;
    }
}
