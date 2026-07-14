package com.tailtopia.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.order.dto.OrderDetailView;
import com.tailtopia.order.service.OrderCenterService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.refund.domain.RefundRequest;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.triage.domain.AiConsultOrder;
import com.tailtopia.triage.repository.AiConsultOrderRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（需 Docker postgres+redis）。Story 5.3 订单详情各态与失效态。
 *
 * <p>核心：3 源分支详情 + 退款子阶段（by approval_status）+ 宠物已删→200 petDeleted 占位（FR-54D，非 500）+
 * owner/不存在 404。
 */
class OrderDetailIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private OrderCenterService orderCenter;
    @Autowired
    private ConsultOrderRepository consultOrders;
    @Autowired
    private AiConsultOrderRepository aiOrders;
    @Autowired
    private PaymentIntentRepository intents;
    @Autowired
    private RefundRequestRepository refunds;
    @Autowired
    private PetProfileRepository pets;
    @Autowired
    private CardTokenGenerator tokens;
    @Autowired
    private JdbcTemplate jdbc;

    private long n() {
        return SEQ.incrementAndGet();
    }

    private PetProfile seedPet(long ownerId) {
        return pets.save(PetProfile.create(ownerId, PetType.DOG, "旺财", "http://a/x.jpg", "柴犬",
                null, null, tokens.generate()));
    }

    private ConsultOrder seedVet(long userId, long petId) {
        ConsultOrder o = ConsultOrder.inProgress("ord-v-" + n(), userId, 1L, petId, 50000,
                PayChannel.QRIS, null, 30000, 60, 50000, Instant.now());
        o.markCompleted(Instant.now());
        return consultOrders.save(o);
    }

    @Test
    void vetDetail_completed_withPet() {
        long userId = newUser().getId();
        PetProfile pet = seedPet(userId);
        ConsultOrder o = seedVet(userId, pet.getId());

        OrderDetailView d = orderCenter.getDetail(userId, o.getOrderToken());
        assertThat(d.orderType()).isEqualTo("VET_CONSULT");
        assertThat(d.statusCode()).isEqualTo("COMPLETED");
        assertThat(d.statusColor()).isEqualTo("SUCCESS");
        assertThat(d.petName()).isEqualTo("旺财");
        assertThat(d.petType()).isEqualTo("DOG");
        assertThat(d.petDeleted()).isFalse();
        assertThat(d.refundStage()).isNull();
    }

    @Test
    void vetDetail_refunding_derivesRefundStage() {
        long userId = newUser().getId();
        PetProfile pet = seedPet(userId);
        ConsultOrder o = seedVet(userId, pet.getId());
        jdbc.update("UPDATE consult_orders SET status='REFUNDING' WHERE id=?", o.getId());
        // 退款请求：approval_status 空 → 待选方式
        RefundRequest r = refunds.save(RefundRequest.create(o.getId(), null, userId, tokens.generate(), 50000));

        OrderDetailView d1 = orderCenter.getDetail(userId, o.getOrderToken());
        assertThat(d1.statusCode()).isEqualTo("REFUNDING");
        assertThat(d1.statusColor()).isEqualTo("INFO"); // 退款中蓝非红
        assertThat(d1.refundStage()).isEqualTo("AWAITING_METHOD");

        // 推进到 PENDING_APPROVAL（待主管）
        jdbc.update("UPDATE refund_requests SET approval_status='PENDING_APPROVAL' WHERE id=?", r.getId());
        assertThat(orderCenter.getDetail(userId, o.getOrderToken()).refundStage())
                .isEqualTo("AWAITING_APPROVAL");

        // APPROVED（待财务）
        jdbc.update("UPDATE refund_requests SET approval_status='APPROVED' WHERE id=?", r.getId());
        assertThat(orderCenter.getDetail(userId, o.getOrderToken()).refundStage())
                .isEqualTo("AWAITING_PAYOUT");
    }

    @Test
    void vetDetail_refundRejected_variant() {
        long userId = newUser().getId();
        PetProfile pet = seedPet(userId);
        ConsultOrder o = seedVet(userId, pet.getId());
        jdbc.update("UPDATE consult_orders SET refund_rejected=true WHERE id=?", o.getId());

        OrderDetailView d = orderCenter.getDetail(userId, o.getOrderToken());
        assertThat(d.statusCode()).isEqualTo("COMPLETED_REFUND_REJECTED");
        assertThat(d.refundStage()).isEqualTo("REJECTED");
    }

    @Test
    void vetDetail_petDeleted_returns200Placeholder() {
        long userId = newUser().getId();
        PetProfile pet = seedPet(userId);
        ConsultOrder o = seedVet(userId, pet.getId());
        pets.delete(pet); // 宠物硬删（FR-54D）

        OrderDetailView d = orderCenter.getDetail(userId, o.getOrderToken());
        assertThat(d.petDeleted()).isTrue();
        assertThat(d.petName()).isNull();
        assertThat(d.petAvatarUrl()).isNull();
        // 订单金额/状态照常（失效仅作用 pet 区块）
        assertThat(d.amount()).isEqualTo(50000);
        assertThat(d.statusCode()).isEqualTo("COMPLETED");
    }

    @Test
    void aiDetail_withTriageTaskId() {
        long userId = newUser().getId();
        AiConsultOrder o = aiOrders.save(AiConsultOrder.completedPawCoin("ord-a-" + n(), userId, 777L, 5000));

        OrderDetailView d = orderCenter.getDetail(userId, o.getOrderToken());
        assertThat(d.orderType()).isEqualTo("AI_UNLOCK");
        assertThat(d.triageTaskId()).isEqualTo(777L);
        assertThat(d.petDeleted()).isFalse();
    }

    @Test
    void topupDetail_withCoins() {
        long userId = newUser().getId();
        PaymentIntent i = PaymentIntent.create(userId, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS,
                25000, "IDR", "ord-t-" + n());
        i.markPaid(java.util.Map.of());
        intents.save(i);

        OrderDetailView d = orderCenter.getDetail(userId, i.getPublicToken());
        assertThat(d.orderType()).isEqualTo("PAWCOIN_TOPUP");
        assertThat(d.coins()).isEqualTo(25000);
    }

    @Test
    void ownerAndMissing_404() {
        long owner = newUser().getId();
        long attacker = newUser().getId();
        ConsultOrder o = seedVet(owner, seedPet(owner).getId());
        assertThatThrownBy(() -> orderCenter.getDetail(attacker, o.getOrderToken()))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> orderCenter.getDetail(owner, "nope-" + n()))
                .isInstanceOf(AppException.class);
    }
}
