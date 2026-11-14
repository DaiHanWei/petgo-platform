package com.tailtopia.gift.repository;

import com.tailtopia.gift.domain.GiftCategory;
import com.tailtopia.gift.domain.GiftCategoryStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 礼物分类仓储（V1.5.0 gift-pack Story 1-1；后台管理用的查询在 Story 1-2 加）。 */
public interface GiftCategoryRepository extends JpaRepository<GiftCategory, Long> {

    /** 在线分类，按 {@code sort_order ASC, id ASC}（App 目录 / 礼物页筛选胶囊）。 */
    List<GiftCategory> findAllByStatusOrderBySortOrderAscIdAsc(GiftCategoryStatus status);

    /** 后台分类页签 / 筛选下拉：全部（含已下线），按 {@code sort_order ASC, id ASC}。 */
    List<GiftCategory> findAllByOrderBySortOrderAscIdAsc();
}
