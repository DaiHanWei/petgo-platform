package com.tailtopia.shop.order.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 运单集合判定（v1.4.2 shop Story 5.1）：后台逐包裹送达与 Biteship 回传签收<b>共用</b>，别写两份。
 */
public final class Shipments {

    private Shipments() {
    }

    /** 活动运单（未被取代、未取消）。 */
    public static List<Shipment> active(Collection<Shipment> all) {
        return all.stream().filter(Shipment::isActive).toList();
    }

    /** 「全部包裹都送达」= 至少一个活动运单，且全部活动运单都已送达（S-2，v1.4.2 只看活动运单）。 */
    public static boolean allActiveDelivered(Collection<Shipment> all) {
        List<Shipment> active = active(all);
        return !active.isEmpty() && active.stream().allMatch(Shipment::isDelivered);
    }

    /** 签收时刻 = 活动运单里最晚的送达时刻（S-2 口径）。 */
    public static Optional<Instant> lastActiveDeliveredAt(Collection<Shipment> all) {
        return active(all).stream().map(Shipment::getDeliveredAt)
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder());
    }

    /** 是否存在活动的 Biteship 运单。 */
    public static boolean hasActiveBiteship(Collection<Shipment> all) {
        return all.stream().anyMatch(s -> s.isActive() && s.isBiteship());
    }
}
