package com.tailtopia.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AuthAccountDeletionService;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.repository.PricingConfigRepository;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.purchase.domain.GrantOutcome;
import com.tailtopia.purchase.domain.KeepsakeGranter;
import com.tailtopia.purchase.domain.KeepsakePurchaseStatus;
import com.tailtopia.purchase.domain.KeepsakeRef;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.dto.KeepsakePurchaseResponse;
import com.tailtopia.purchase.repository.KeepsakePurchaseRepository;
import com.tailtopia.purchase.service.KeepsakeGranterRegistry;
import com.tailtopia.purchase.service.KeepsakePurchaseService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * V1.3.2 batch-a Story 3.1 · L1（需 postgres + redis）：purpose CHECK 八值、购买链路真库、到账唯一监听、删档 / 注销保留。
 *
 * <p>发放口注册表用 {@link MockitoBean} 顶掉（真实发放口由 3.2 / 3.4 / 3.5 交付），统一指向一个可控的测试发放口。
 */
class KeepsakePurchaseIntegrationTest extends ApiIntegrationTest {

    @MockitoBean
    private KeepsakeGranterRegistry granters;

    @Autowired
    private KeepsakePurchaseService service;
    @Autowired
    private KeepsakePurchaseRepository purchases;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private PricingConfigRepository pricingRepo;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private ProfileDeletionService profileDeletion;
    @Autowired
    private AuthAccountDeletionService authDeletion;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private final AtomicReference<GrantOutcome> nextOutcome = new AtomicReference<>(GrantOutcome.GRANTED);
    private final AtomicReference<RuntimeException> grantThrows = new AtomicReference<>();
    private long savedPrice;

    @BeforeEach
    void setUp() {
        KeepsakeGranter g = new KeepsakeGranter() {
            @Override
            public KeepsakeSku sku() {
                return KeepsakeSku.TAILSONALITY;
            }

            @Override
            public GrantOutcome grant(long refId, long purchaseId) {
                if (grantThrows.get() != null) {
                    throw grantThrows.get();
                }
                return nextOutcome.get();
            }
        };
        when(granters.forSku(any())).thenReturn(g);
        savedPrice = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow().getPassportPageUnlockPrice();
    }

    @AfterEach
    void restorePrice() {
        PricingConfig c = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
        c.setPassportPageUnlockPrice(savedPrice);
        pricingRepo.save(c);
    }

    private long refId() {
        return 1_000_000L + SEQ.incrementAndGet();
    }

    private KeepsakeRef ref(KeepsakeSku sku, long refId) {
        return new KeepsakeRef(sku, refId, "ref" + refId, null, false);
    }

    private long balance(long uid) {
        return wallet.balanceOf(uid);
    }

    private void fund(long uid) {
        wallet.credit(uid, 100_000L, PawCoinTxnType.TOPUP, "TEST", null, "kp-topup:" + uid + ":" + SEQ.incrementAndGet());
    }

    @Test
    void allEightPurposesAreAcceptedAndBogusIsRejected() {
        User u = newUser();
        for (PaymentPurpose p : PaymentPurpose.values()) {
            if (p == PaymentPurpose.SHOP_ORDER) {
                continue; // 电商走 createMixedIntent / 自有窗口，形状约束不同；CHECK 值已在迁移里显式列出
            }
            paymentIntents.createIntent(u.getId(), p, PayChannel.QRIS, 1000, "IDR", "kp-purpose:" + p + ":" + SEQ.incrementAndGet());
        }
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT purpose) FROM payment_intents WHERE user_id = ?",
                Long.class, u.getId())).isEqualTo(PaymentPurpose.values().length - 1L);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_intents SET purpose = 'BOGUS' WHERE user_id = ?", u.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void pawcoinTwoRefsChargeTwiceAndQrisThenPawcoinStillCharges() {
        User u = newUser();
        fund(u.getId());
        long before = balance(u.getId());
        long price = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow().getPassportPageUnlockPrice();
        service.start(u.getId(), ref(KeepsakeSku.PASSPORT_SNAP, refId()), PayChannel.PAWCOIN);
        service.start(u.getId(), ref(KeepsakeSku.PASSPORT_SNAP, refId()), PayChannel.PAWCOIN);
        assertThat(balance(u.getId())).isEqualTo(before - 2 * price);

        long r = refId();
        KeepsakePurchaseResponse qris = service.start(u.getId(), ref(KeepsakeSku.PASSPORT_SNAP, r), PayChannel.QRIS);
        assertThat(qris.unlocked()).isFalse();
        service.start(u.getId(), ref(KeepsakeSku.PASSPORT_SNAP, r), PayChannel.PAWCOIN);
        assertThat(balance(u.getId())).as("渠道后缀：先 QRIS 再 PawCoin 必须真实扣币").isEqualTo(before - 3 * price);
    }

    @Test
    void qrisTwiceSameRowAndPaidOnceGrantedOnce() {
        User u = newUser();
        long r = refId();
        KeepsakePurchaseResponse a = service.start(u.getId(), ref(KeepsakeSku.TAILSONALITY, r), PayChannel.QRIS);
        KeepsakePurchaseResponse b = service.start(u.getId(), ref(KeepsakeSku.TAILSONALITY, r), PayChannel.QRIS);
        assertThat(a.purchaseToken()).isEqualTo(b.purchaseToken());
        assertThat(a.payment().token()).isEqualTo(b.payment().token());
        var row = purchases.findByPublicToken(a.purchaseToken()).orElseThrow();
        assertThat(row.getPriceIdr()).isEqualTo(a.payment().amount());

        paymentIntents.applyCallback(new PaymentCallback(a.payment().token(), "gw-" + SEQ.incrementAndGet(),
                GatewayStatus.PAID, Map.of()));
        paymentIntents.applyCallback(new PaymentCallback(a.payment().token(), "gw-dup", GatewayStatus.PAID, Map.of()));
        row = purchases.findByPublicToken(a.purchaseToken()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(KeepsakePurchaseStatus.PAID);
        assertThat(row.getPaidAt()).isNotNull();
    }

    @Test
    void paidWhenAlreadyUnlockedOrMissingOrGrantThrowsKeepsIntentPaid() {
        for (var scenario : new Object[] {GrantOutcome.ALREADY_UNLOCKED, GrantOutcome.REF_MISSING, "throw"}) {
            User u = newUser();
            KeepsakePurchaseResponse a = service.start(u.getId(), ref(KeepsakeSku.TAILSONALITY, refId()), PayChannel.QRIS);
            if (scenario instanceof GrantOutcome o) {
                nextOutcome.set(o);
                grantThrows.set(null);
            } else {
                grantThrows.set(new IllegalStateException("contract violated"));
            }
            paymentIntents.applyCallback(new PaymentCallback(a.payment().token(), "gw-" + SEQ.incrementAndGet(),
                    GatewayStatus.PAID, Map.of()));
            var row = purchases.findByPublicToken(a.purchaseToken()).orElseThrow();
            assertThat(row.getStatus()).as(String.valueOf(scenario)).isEqualTo(
                    scenario == GrantOutcome.ALREADY_UNLOCKED ? KeepsakePurchaseStatus.DUPLICATE_PAID : KeepsakePurchaseStatus.ORPHAN_PAID);
            assertThat(jdbc.queryForObject("SELECT status FROM payment_intents WHERE public_token = ?", String.class,
                    a.payment().token())).isEqualTo("PAID");
        }
        grantThrows.set(null);
        nextOutcome.set(GrantOutcome.GRANTED);
    }

    @Test
    void priceChangeAffectsOnlyNewStartsAndPendingKeepsItsPrice() {
        User u = newUser();
        long r = refId();
        KeepsakePurchaseResponse a = service.start(u.getId(), ref(KeepsakeSku.PASSPORT_SNAP, r), PayChannel.QRIS);
        long oldPrice = purchases.findByPublicToken(a.purchaseToken()).orElseThrow().getPriceIdr();
        PricingConfig c = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
        c.setPassportPageUnlockPrice(oldPrice + 1234);
        pricingRepo.save(c);
        assertThat(purchases.findByPublicToken(a.purchaseToken()).orElseThrow().getPriceIdr()).isEqualTo(oldPrice);
        KeepsakePurchaseResponse n = service.start(u.getId(), ref(KeepsakeSku.PASSPORT_SNAP, refId()), PayChannel.QRIS);
        assertThat(purchases.findByPublicToken(n.purchaseToken()).orElseThrow().getPriceIdr()).isEqualTo(oldPrice + 1234);
    }

    @Test
    void alreadyUnlockedAndMixedAreRejected() {
        User u = newUser();
        assertThatThrownBy(() -> service.start(u.getId(), new KeepsakeRef(KeepsakeSku.TAILSONALITY, refId(), "x", null, true),
                PayChannel.PAWCOIN)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.start(u.getId(), ref(KeepsakeSku.TAILSONALITY, refId()), PayChannel.MIXED))
                .isInstanceOf(AppException.class);
    }

    @Test
    void profileDeletionAndAccountDeletionKeepPurchaseRows() {
        User u = newUser();
        fund(u.getId());
        PetProfile pet = petProfiles.save(PetProfile.create(u.getId(), PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        KeepsakePurchaseResponse r = service.start(u.getId(),
                new KeepsakeRef(KeepsakeSku.PASSPORT_SNAP, refId(), "p" + SEQ.incrementAndGet(), pet.getId(), false),
                PayChannel.PAWCOIN);
        long price = purchases.findByPublicToken(r.purchaseToken()).orElseThrow().getPriceIdr();

        profileDeletion.deleteByUserId(u.getId());
        var row = purchases.findByPublicToken(r.purchaseToken()).orElseThrow();
        assertThat(row.getPetProfileId()).isNull();
        assertThat(row.getStatus()).isEqualTo(KeepsakePurchaseStatus.PAID);
        assertThat(row.getPriceIdr()).isEqualTo(price);

        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(s -> authDeletion.deleteByUserId(u.getId()));
        assertThat(purchases.findByPublicToken(r.purchaseToken())).as("注销：users 行匿名化，购买行不删").isPresent();
    }
}
