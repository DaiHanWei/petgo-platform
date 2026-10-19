package com.tailtopia.gift.repository;

import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftCatalogStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 礼物目录仓储（V1.4.0 gift Story 1.1）。 */
public interface GiftCatalogRepository extends JpaRepository<GiftCatalogItem, Long> {

    /** App 目录：按状态取全部（ONLINE），分组与组内排序在 {@code GiftCatalogService} 里做。 */
    List<GiftCatalogItem> findByStatus(GiftCatalogStatus status);

    Optional<GiftCatalogItem> findByPublicToken(String publicToken);
}
