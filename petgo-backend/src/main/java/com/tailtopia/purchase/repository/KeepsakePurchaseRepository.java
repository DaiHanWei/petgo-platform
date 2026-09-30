package com.tailtopia.purchase.repository;

import com.tailtopia.purchase.domain.KeepsakePurchase;
import com.tailtopia.purchase.domain.KeepsakePurchaseStatus;
import com.tailtopia.purchase.domain.KeepsakeSku;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KeepsakePurchaseRepository extends JpaRepository<KeepsakePurchase, Long> {

    Optional<KeepsakePurchase> findByPaymentIntentId(long paymentIntentId);

    Optional<KeepsakePurchase> findFirstBySkuAndRefIdAndStatus(KeepsakeSku sku, long refId,
            KeepsakePurchaseStatus status);

    Optional<KeepsakePurchase> findByPublicToken(String publicToken);
}
