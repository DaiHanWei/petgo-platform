package com.tailtopia.shop.address.repository;

import com.tailtopia.shop.address.domain.ShippingAddress;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 收货地址仓储（Story 2.1）。 */
public interface ShippingAddressRepository extends JpaRepository<ShippingAddress, Long> {

    List<ShippingAddress> findByUserIdOrderByIsDefaultDescIdDesc(long userId);

    /**
     * 🔴 <b>按 (token, userId) 双条件查</b>，不是先按 token 查再比 userId。
     * 前者查不到就是查不到 → 天然 404；后者容易写成「查到了但不是你的 → 403」，
     * 而 403 等于告诉攻击者<b>这个 token 存在</b>，可用来枚举他人地址是否存在。
     */
    Optional<ShippingAddress> findByPublicTokenAndUserId(String publicToken, long userId);

    long countByUserId(long userId);

    Optional<ShippingAddress> findByUserIdAndIsDefaultTrue(long userId);

    /** 账号注销级联（Story 7.3）：地址簿是纯个人 PII，整簿物理删除。幂等（删空即无行可删）。 */
    void deleteByUserId(long userId);
}
