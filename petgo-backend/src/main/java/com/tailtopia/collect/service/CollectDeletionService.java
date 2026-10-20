package com.tailtopia.collect.service;

import com.tailtopia.collect.repository.ContentCollectionRepository;
import com.tailtopia.collect.repository.PlaceCollectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 收藏的注销清理（V1.4.0 ④ Story 2.1 · AD-13，CLAUDE.md 安全攸关 D1）。
 *
 * <p>收藏是「仅本人可见」的私人数据，随注销<b>物理删除</b>本人两张表的全部行。幂等可重跑。
 * 🔴 users 行只匿名化不删，{@code ON DELETE CASCADE} 在注销路径不会触发 —— 必须显式删。
 *
 * <p>⚠️ <b>他人</b>收藏的、指向注销作者帖子的行<b>不删</b>：帖子被置 {@code AUTHOR_DEACTIVATED}，
 * 列表读时显示不可见，用户可自行移除。
 */
@Service
public class CollectDeletionService {

    private final ContentCollectionRepository contentCollections;
    private final PlaceCollectionRepository placeCollections;

    public CollectDeletionService(ContentCollectionRepository contentCollections,
            PlaceCollectionRepository placeCollections) {
        this.contentCollections = contentCollections;
        this.placeCollections = placeCollections;
    }

    @Transactional
    public void deleteByUserId(long userId) {
        contentCollections.deleteByUserId(userId);
        placeCollections.deleteByUserId(userId);
    }
}
