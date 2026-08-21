package com.tailtopia.shop.repurchase.repository;

import com.tailtopia.shop.repurchase.domain.RepurchaseTrigger;
import com.tailtopia.shop.repurchase.domain.RepurchaseTriggerStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 复购触发仓储（Story 6.3）。 */
public interface RepurchaseTriggerRepository extends JpaRepository<RepurchaseTrigger, Long> {

    Optional<RepurchaseTrigger> findByUserIdAndSkuIdAndStatus(long userId, long skuId,
            RepurchaseTriggerStatus status);

    /**
     * 首页区域① 读这个：进行中的触发，按耗尽日升序（越快没粮的越靠前）。
     *
     * <p>⚠️ <b>SPEC-16 剩余部分</b>：多个 Makanan SKU 同时见底时区域① 只放 2 张，
     * 排序规则未定。这里按耗尽日升序是<b>实现侧的合理默认</b>，产品拍板后可能要改。
     */
    List<RepurchaseTrigger> findByUserIdAndStatusOrderByEstimatedDepletionDateAsc(long userId,
            RepurchaseTriggerStatus status);

    List<RepurchaseTrigger> findByUserIdAndSkuIdIn(long userId, List<Long> skuIds);

    /** AB-13B 看板取数：按类型统计（本版本 DEWORM/VACCINE 恒为 0，那是范围决策）。 */
    @Query("""
            SELECT t.triggerType, t.status, COUNT(t) FROM RepurchaseTrigger t
            GROUP BY t.triggerType, t.status
            """)
    List<Object[]> countByTypeAndStatus();

    @Query("""
            SELECT COUNT(DISTINCT t.userId) FROM RepurchaseTrigger t
            WHERE t.estimatedDepletionDate >= :from
            """)
    long countDistinctTriggeredUsersSince(@Param("from") LocalDate from);
}
