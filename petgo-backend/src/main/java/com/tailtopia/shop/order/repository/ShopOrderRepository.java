package com.tailtopia.shop.order.repository;

import com.tailtopia.shop.order.domain.ShopOrder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 电商订单仓储（Story 3.2）。 */
public interface ShopOrderRepository extends JpaRepository<ShopOrder, Long> {

    /**
     * 🔴 <b>按 (token, userId) 双条件查</b> —— 与地址簿同理：
     * 让「不是你的」和「不存在」在代码路径上就是同一件事，天然 404，不泄露 token 是否存在。
     */
    Optional<ShopOrder> findByPublicTokenAndUserId(String publicToken, long userId);

    Optional<ShopOrder> findByPublicToken(String publicToken);

    List<ShopOrder> findByUserIdOrderByCreatedAtDescIdDesc(long userId);
}
