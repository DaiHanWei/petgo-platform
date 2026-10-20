package com.tailtopia.gift.listener;

import com.tailtopia.auth.dto.ActorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.event.GiftRefundedEvent;
import com.tailtopia.gift.event.GiftSentEvent;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.InteractionCopyRenderer;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.social.read.UserHideRelationReader;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 送礼通知（V1.4.0 gift Story 1.2 · AC4 / AC6 · AD-8）。两个监听都是 AFTER_COMMIT、同步（写法照 {@code ContentNotifyListener}），
 * 任何异常只记 warn（订单 id + 异常类名）、不向上抛 —— 送达 / 退回已在前一事务提交，通知失败不影响它们。
 *
 * <ul>
 *   <li>{@link #onGiftSent}：收礼宠物主人收 {@code PET_GIFT_RECEIVED}（按宠物未读即合并、每份都推、单笔文案）。</li>
 *   <li>{@link #onRefunded}：送礼人收 {@code GIFT_UNDELIVERED_REFUND}（含宠物名、金额、原因；按账号语言渲染后落库 title / body）。</li>
 * </ul>
 *
 * <p>🔴 拉黑 / 隐藏抑制<b>只在本监听器</b>（「压根不调 send」），不写进 {@code NotificationService}：
 * 主人隐藏了送礼人（{@code isHidden(owner, sender)}，拉黑与举报隐藏都算）→ 不通知，礼物照常送达。
 *
 * <p>🔴 日志不记昵称、宠物名、通知文案、推送正文。
 */
@Component
public class GiftNotifyListener {

    private static final Logger log = LoggerFactory.getLogger(GiftNotifyListener.class);

    /** 退回通知的文案键后缀（{@code notify.GIFT_UNDELIVERED_REFUND.title / body}）。 */
    static final String REFUND_COPY_KEY = "GIFT_UNDELIVERED_REFUND";

    private final GiftOrderRepository orders;
    private final NotificationService notifications;
    private final InteractionCopyRenderer copy;
    private final UserHideRelationReader hideRelations;
    private final AccountQueryService accounts;
    private final MessageSource messages;

    public GiftNotifyListener(GiftOrderRepository orders, NotificationService notifications,
            InteractionCopyRenderer copy, UserHideRelationReader hideRelations, AccountQueryService accounts,
            MessageSource messages) {
        this.orders = orders;
        this.notifications = notifications;
        this.copy = copy;
        this.hideRelations = hideRelations;
        this.accounts = accounts;
        this.messages = messages;
    }

    /** 收礼通知（AC6）。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGiftSent(GiftSentEvent event) {
        try {
            long owner = event.receiverOwnerId();
            long sender = event.senderId();
            if (owner == sender) {
                return; // 防御：发起时已拒绝给自己的宠物送礼
            }
            if (hideRelations.isHidden(owner, sender)) {
                return; // AC9：主人隐藏了送礼人 → 不通知（礼物照常送达）
            }
            GiftOrder order = orders.findById(event.giftOrderId()).orElse(null);
            if (order == null) {
                log.warn("gift notify skipped: order missing orderId={}", event.giftOrderId());
                return;
            }
            notifications.sendGiftReceived(owner, sender, event.petId(), pushTitle(order, owner, sender));
        } catch (RuntimeException e) {
            log.warn("gift received notify failed orderId={} cause={}", event.giftOrderId(),
                    e.getClass().getSimpleName());
        }
    }

    /** 退回通知（AC4）。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefunded(GiftRefundedEvent event) {
        try {
            GiftOrder order = orders.findById(event.giftOrderId()).orElse(null);
            if (order == null) {
                log.warn("gift refund notify skipped: order missing orderId={}", event.giftOrderId());
                return;
            }
            long sender = order.getSenderId();
            Locale locale = accounts.localeOf(sender);
            Object[] args = {order.getPetName(), formatAmount(order.getTotal(), locale),
                    messages.getMessage("notify.giftRefund.reason", null, locale)};
            String title = messages.getMessage("notify." + REFUND_COPY_KEY + ".title", args, locale);
            String body = messages.getMessage("notify." + REFUND_COPY_KEY + ".body", args, locale);
            notifications.sendWithCopy(sender, NotificationType.GIFT_UNDELIVERED_REFUND, REFUND_COPY_KEY, args, title,
                    body, order.getPublicToken());
        } catch (RuntimeException e) {
            log.warn("gift refund notify failed orderId={} cause={}", event.giftOrderId(),
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 单笔推送标题（按主人语言）。送礼人取不到可显示的昵称（已注销 / 封号）→ null（不推、仍落库，交给列表过滤）。
     */
    private String pushTitle(GiftOrder order, long owner, long sender) {
        ActorView actor = accounts.findActorViews(List.of(sender)).get(sender);
        if (actor == null || !actor.active() || actor.nickname() == null) {
            return null;
        }
        Locale locale = accounts.localeOf(owner);
        return copy.giftPushTitle(actor.nickname(), giftLabel(order, locale), order.getPetName(), locale);
    }

    /** 礼物名按语言取订单快照（{@code en} → EN 名；其余含 null → ID 名）；数量 ≥ 2 时加「 ×N」，1 时省略（UI 稿 G4）。 */
    static String giftLabel(GiftOrder order, Locale locale) {
        // 与通知列表同一份口径（InteractionCopyRenderer.giftLabel，L2 第二轮起列表单人条目也带礼物）。
        return InteractionCopyRenderer.giftLabel(order.getGiftNameEn(), order.getGiftNameId(), order.getQuantity(),
                locale);
    }

    /** 金额按收件人语言千分位（id「25.000」/ en「25,000」），以字符串传入文案。 */
    static String formatAmount(long amount, Locale locale) {
        return NumberFormat.getIntegerInstance(locale == null ? Locale.forLanguageTag("id") : locale).format(amount);
    }
}
