package com.tailtopia.profile.service;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Diary 时间线的客户端能力声明（V1.3.2 Story 1.6 · 架构 delta AD-9）。
 *
 * <p>时间线 / 日详情 / 日历三处接口统一接受 {@code supports} 参数；<b>未声明的新类型三处都不下发</b>——
 * 老 App 遇未知 {@code itemType} 会回落成照片卡（{@code timeline_item.dart} 兜底），所以这是必需的闸，不是锦上添花。
 *
 * <p>Story 3.3 追加 {@code tailsonality}；<b>不要</b>写成 {@code boolean includeCheckins} 这类单开关。
 */
public final class TimelineCapabilities {

    /** 能力值（wire = 小写，大小写敏感比对）。 */
    public enum Capability {
        PLACE_CHECKIN("place_checkin"),
        /** V1.3.2 Story 3.3：Tailsonality 解锁条目（{@code TAILSONALITY_BANNER}）。 */
        TAILSONALITY("tailsonality");

        private final String wire;

        Capability(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private static final TimelineCapabilities NONE = new TimelineCapabilities(EnumSet.noneOf(Capability.class));

    private final Set<Capability> values;

    private TimelineCapabilities(Set<Capability> values) {
        this.values = Set.copyOf(values);
    }

    /** 老 App：什么都不声明。 */
    public static TimelineCapabilities none() {
        return NONE;
    }

    public static TimelineCapabilities of(Capability... caps) {
        EnumSet<Capability> s = EnumSet.noneOf(Capability.class);
        s.addAll(java.util.Arrays.asList(caps));
        return new TimelineCapabilities(s);
    }

    /**
     * 解析 {@code supports} 参数：兼容 {@code supports=a&supports=b} 与 {@code supports=a,b}；未知值忽略。
     */
    public static TimelineCapabilities parse(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return NONE;
        }
        EnumSet<Capability> s = EnumSet.noneOf(Capability.class);
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            for (String part : entry.split(",")) {
                String v = part.trim();
                for (Capability c : Capability.values()) {
                    if (c.wire.equals(v)) {
                        s.add(c);
                    }
                }
            }
        }
        return s.isEmpty() ? NONE : new TimelineCapabilities(s);
    }

    public boolean has(Capability c) {
        return values.contains(c);
    }

    public boolean placeCheckin() {
        return has(Capability.PLACE_CHECKIN);
    }

    public boolean tailsonality() {
        return has(Capability.TAILSONALITY);
    }
}
