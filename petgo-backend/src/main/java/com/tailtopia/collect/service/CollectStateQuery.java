package com.tailtopia.collect.service;

import com.tailtopia.collect.repository.ContentCollectionRepository;
import com.tailtopia.collect.repository.PlaceCollectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 详情页的收藏状态只读口（V1.4.0 ④ Story 2.1 · AD-10）。被 {@code ContentDetailService} / {@code PlaceQueryService} 注入。
 *
 * <p>🔴 只依赖两个收藏 repository —— 不能依赖 {@link CollectService}（它依赖 {@code ContentDetailService}，会成 Bean 环）。
 */
@Service
public class CollectStateQuery {

    private final ContentCollectionRepository contentCollections;
    private final PlaceCollectionRepository placeCollections;

    public CollectStateQuery(ContentCollectionRepository contentCollections,
            PlaceCollectionRepository placeCollections) {
        this.contentCollections = contentCollections;
        this.placeCollections = placeCollections;
    }

    @Transactional(readOnly = true)
    public boolean isPostCollected(long userId, long postId) {
        return contentCollections.existsByUserIdAndPostId(userId, postId);
    }

    /** 作者视角收藏数：不计作者自己（PRD AC10）。 */
    @Transactional(readOnly = true)
    public long collectCountExcludingAuthor(long postId, long authorId) {
        return contentCollections.countByPostIdExcludingUser(postId, authorId);
    }

    /** {@code placeId} 为解析后的场所；本人在它或任一并入它的场所上有行即为已收藏（P-6）。 */
    @Transactional(readOnly = true)
    public boolean isPlaceCollected(long userId, long placeId) {
        return placeCollections.existsCovering(userId, placeId);
    }
}
