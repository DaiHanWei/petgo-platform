package com.tailtopia.gift.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.domain.GiftUndeliveredReason;
import com.tailtopia.gift.event.GiftSentEvent;
import com.tailtopia.gift.event.GiftUndeliveredEvent;
import com.tailtopia.gift.port.GiftPetDirectory;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.gift.repository.GiftPetSummaryRepository;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 送礼交付（V1.4.0 gift Story 1.1 · AD-7 · AC8）：PawCoin 发起与 QRIS 到账两条路径共用的<b>唯一</b>交付口。
 *
 * <p><b>在调用方事务里执行</b>（PawCoin = 发起事务；QRIS = {@code applyCallback} 事务）。刻意<b>不加</b>
 * {@code @Transactional}：经事务代理抛出的异常会把外层（支付回调）事务标成 rollback-only、连 {@code markPaid} 一起回滚；
 * 改为入口处断言「当前必须有事务」（同 {@code KeepsakeGrantRunner} 的取舍）。
 *
 * <ol>
 *   <li>只接受 PENDING；交付前<b>再校验可送达</b>，顺序：主人已注销 → {@code OWNER_DELETED}；宠物已删除 →
 *       {@code PET_DELETED}；送礼人拉黑了主人 → {@code SENDER_BLOCKED}。</li>
 *   <li>可送达 → 条件更新 PENDING → DELIVERED（0 行 = 已被并发交付，直接返回）→ 同事务 upsert 礼物墙与粉丝榜 →
 *       发布<b>一次</b> {@link GiftSentEvent}（① 成长值与 1.2 通知都 AFTER_COMMIT 监听）。</li>
 *   <li>不可送达 → 条件更新为 UNDELIVERED_REFUND_PENDING + 原因，<b>不写汇总、不发 {@link GiftSentEvent}</b>，
 *       发布 {@link GiftUndeliveredEvent}（退回由 Story 1.2 做）。</li>
 * </ol>
 */
@Service
public class GiftDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(GiftDeliveryService.class);

    /** 交付结果。 */
    public enum Result {
        /** 本次交付成功（已写汇总、已发 {@link GiftSentEvent}）。 */
        DELIVERED,
        /** 不可送达，已置 UNDELIVERED_REFUND_PENDING（已发 {@link GiftUndeliveredEvent}）。 */
        UNDELIVERED,
        /** 订单已非 PENDING（重放 / 并发已处理），什么都没做。 */
        NOT_PENDING
    }

    private final GiftOrderRepository orders;
    private final GiftPetSummaryRepository summaries;
    private final GiftPetDirectory pets;
    private final AccountQueryService accounts;
    private final UserHideRelationReader hides;
    private final ApplicationEventPublisher events;

    public GiftDeliveryService(GiftOrderRepository orders, GiftPetSummaryRepository summaries, GiftPetDirectory pets,
            AccountQueryService accounts, UserHideRelationReader hides, ApplicationEventPublisher events) {
        this.orders = orders;
        this.summaries = summaries;
        this.pets = pets;
        this.accounts = accounts;
        this.hides = hides;
        this.events = events;
    }

    /** 交付一笔 PENDING 订单（见类注释）。必须在调用方事务内调用。 */
    public Result deliver(GiftOrder order) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("送礼交付必须在调用方事务内执行");
        }
        if (!order.isPending()) {
            return Result.NOT_PENDING;
        }
        GiftUndeliveredReason reason = undeliverableReason(order);
        if (reason != null) {
            if (orders.markUndeliveredIfPending(order.getId(), reason.name()) == 0) {
                return Result.NOT_PENDING;
            }
            log.info("gift order undelivered orderId={} reason={}", order.getId(), reason);
            events.publishEvent(new GiftUndeliveredEvent(order.getId()));
            return Result.UNDELIVERED;
        }
        if (orders.markDeliveredIfPending(order.getId()) == 0) {
            return Result.NOT_PENDING; // 已被并发交付：不再写汇总、不发事件
        }
        long petId = order.getPetId();
        summaries.addToWall(petId, order.getGiftId(), order.getUnitPrice(), order.getGiftNameEn(),
                order.getGiftNameId(), order.getGiftIconUrl(), order.getQuantity());
        summaries.addToSupporters(petId, order.getSenderId(), order.getTotal(), order.getQuantity());
        events.publishEvent(new GiftSentEvent(order.getId(), order.getSenderId(), order.getOwnerId(), petId,
                order.getTotal()));
        log.info("gift order delivered orderId={} total={} channel={}", order.getId(), order.getTotal(),
                order.getChannel());
        return Result.DELIVERED;
    }

    /** 交付前二次校验；可送达返回 null。顺序固定：主人注销 → 宠物删除 → 送礼人拉黑主人。 */
    private GiftUndeliveredReason undeliverableReason(GiftOrder order) {
        boolean ownerDeleted = accounts.findAccountById(order.getOwnerId())
                .map(u -> u.getDeletedAt() != null)
                .orElse(true);
        Optional<GiftPet> pet = order.getPetId() == null ? Optional.empty() : pets.pet(order.getPetId());
        if (ownerDeleted || pet.map(GiftPet::ownerDeleted).orElse(false)) {
            return GiftUndeliveredReason.OWNER_DELETED;
        }
        if (pet.isEmpty()) {
            return GiftUndeliveredReason.PET_DELETED;
        }
        if (hides.isBlocked(order.getSenderId(), order.getOwnerId())) {
            return GiftUndeliveredReason.SENDER_BLOCKED;
        }
        return null;
    }
}
