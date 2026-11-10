package com.tailtopia.profile.service;

import com.tailtopia.profile.dto.MilestoneGroupResponse;
import com.tailtopia.profile.dto.MilestoneItemResponse;
import com.tailtopia.profile.dto.MilestoneListResponse;
import com.tailtopia.profile.dto.TimelineItemResponse;
import com.tailtopia.profile.dto.TimelinePageResponse;
import com.tailtopia.profile.visitor.VisitorTimelineItem;
import java.util.List;
import java.util.Set;

/**
 * 旧版 App（&lt; 1.4.2）里程碑兼容（1.4.2 上线前旧版兼容检查）：V1.4.2 新增的账号级里程碑「第一次关注一个人」
 * 「第一次收藏内容」旧包没有文案，会把 code（如 {@code C-S19}）原样显示。对旧包把它们从里程碑列表与时间线里拿掉，
 * 列表计数同步扣减。数据照常落库，升级到 1.4.2 后自然出现。
 *
 * <p>未覆盖：通知中心的 {@code MILESTONE_SM_NODE} 通知（过滤会让未读角标与列表对不上，量小，接受）。
 */
public final class LegacyMilestoneCompat {

    /** V1.4.2 新增的账号级里程碑 code（猫 / 狗 / 通用；见 {@code MilestoneCatalog}，单测核对标题）。 */
    public static final Set<String> V142_CODES = Set.of("C-S19", "C-S20", "D-S19", "D-S20", "G-S12", "G-S13");

    private LegacyMilestoneCompat() {
    }

    public static boolean hiddenForLegacy(String code) {
        return code != null && V142_CODES.contains(code);
    }

    /** 新版原样返回；旧版去掉新里程碑并重算各级与总计数。 */
    public static MilestoneListResponse forClient(MilestoneListResponse r, String appVersion) {
        if (r == null || ClientAppVersion.isV142OrLater(appVersion) || r.groups() == null) {
            return r;
        }
        int hiddenTotal = 0;
        int hiddenDone = 0;
        List<MilestoneGroupResponse> groups = new java.util.ArrayList<>(r.groups().size());
        for (MilestoneGroupResponse g : r.groups()) {
            List<MilestoneItemResponse> items = g.items() == null ? null
                    : g.items().stream().filter(i -> !hiddenForLegacy(i.code())).toList();
            int removed = g.items() == null ? 0 : g.items().size() - items.size();
            int removedDone = g.items() == null ? 0
                    : (int) g.items().stream().filter(i -> hiddenForLegacy(i.code()) && i.completed()).count();
            hiddenTotal += removed;
            hiddenDone += removedDone;
            groups.add(new MilestoneGroupResponse(g.level(), g.completedCount() - removedDone,
                    g.totalCount() - removed, items));
        }
        return new MilestoneListResponse(r.petName(), r.petAvatarUrl(), r.completedCount() - hiddenDone,
                r.totalCount() - hiddenTotal, groups);
    }

    /** 主人时间线（游标分页）：旧版去掉新里程碑条目；游标不变。 */
    public static TimelinePageResponse forClient(TimelinePageResponse r, String appVersion) {
        if (r == null || ClientAppVersion.isV142OrLater(appVersion) || r.items() == null) {
            return r;
        }
        List<TimelineItemResponse> items = r.items().stream()
                .filter(i -> !hiddenForLegacy(i.milestoneCode())).toList();
        return new TimelinePageResponse(items, r.nextCursor(), r.hasMore());
    }

    /** 访客时间线（不分页）。 */
    public static List<VisitorTimelineItem> visitorForClient(List<VisitorTimelineItem> items, String appVersion) {
        if (items == null || ClientAppVersion.isV142OrLater(appVersion)) {
            return items;
        }
        return items.stream().filter(i -> !hiddenForLegacy(i.milestoneCode())).toList();
    }
}
