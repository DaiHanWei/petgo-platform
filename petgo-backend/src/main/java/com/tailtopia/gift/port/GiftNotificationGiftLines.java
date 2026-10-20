package com.tailtopia.gift.port;

import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.domain.GiftOrderStatus;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.notify.port.NotificationGiftLines;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 通知列表「送的是什么」端口 {@link NotificationGiftLines} 的实现（V1.4.0 gift L2 第二轮）。
 *
 * <p>一页一次查询：取本页涉及的（宠物 × 送礼人）在 [最早 {@code since}, 最晚 {@code until}] 内已送达的订单，再在内存里按每条通知的条件归属。
 * 只读订单快照（礼物名 / 数量），不碰别的模块。
 */
@Component
public class GiftNotificationGiftLines implements NotificationGiftLines {

    private final GiftOrderRepository orders;

    public GiftNotificationGiftLines(GiftOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Map<Long, GiftLine> linesOf(Map<Long, Query> queries) {
        if (queries.isEmpty()) {
            return Map.of();
        }
        Set<Long> pets = new HashSet<>();
        Set<Long> senders = new HashSet<>();
        Instant earliest = null;
        Instant latest = null;
        for (Query q : queries.values()) {
            pets.add(q.petId());
            senders.add(q.senderId());
            earliest = earliest == null || q.since().isBefore(earliest) ? q.since() : earliest;
            latest = latest == null || q.until().isAfter(latest) ? q.until() : latest;
        }
        List<GiftOrder> delivered = orders.findDeliveredForNotify(pets, senders, GiftOrderStatus.DELIVERED, earliest,
                latest);
        Map<Long, GiftLine> out = new HashMap<>();
        queries.forEach((notificationId, q) -> {
            GiftLine line = null;
            Long giftId = null;
            for (GiftOrder o : delivered) {
                if (o.getPetId() == null || o.getPetId() != q.petId() || o.getSenderId() != q.senderId()
                        || o.getDeliveredAt() == null || o.getDeliveredAt().isBefore(q.since())
                        || o.getDeliveredAt().isAfter(q.until())) {
                    continue;
                }
                if (giftId == null) {
                    giftId = o.getGiftId();
                    line = new GiftLine(o.getGiftNameEn(), o.getGiftNameId(), o.getQuantity());
                } else if (giftId == o.getGiftId()) {
                    line = new GiftLine(line.nameEn(), line.nameId(), line.quantity() + o.getQuantity());
                } else {
                    return; // 送了不止一种 → 不给，调用方回落「mengirim hadiah」
                }
            }
            if (line != null) {
                out.put(notificationId, line);
            }
        });
        return out;
    }
}
