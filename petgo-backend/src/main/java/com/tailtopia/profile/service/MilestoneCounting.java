package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.AccountMilestoneFlag;
import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.MilestoneDefinition;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.UserMilestoneFlag;
import com.tailtopia.profile.dto.MilestoneGroupResponse;
import com.tailtopia.profile.dto.MilestoneItemResponse;
import com.tailtopia.profile.dto.MilestoneListResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 里程碑计数口径的<b>唯一入口</b>（V1.5.0 onboarding-recall Story 2.3 · FR-178 · OR-11）。
 *
 * <p>新口径：账号级 3 项（Teman pertama / Simpan pertama / Lulus Pemula，{@link AccountMilestoneFlag}）
 * 单独成「账号成就」区，<b>不计入</b>宠物的里程碑进度（猫狗 35 → 32、其他 20 → 17）。
 * 用到「宠物里程碑完成数 / 总数」的地方——列表页（≥ 1.5.0）、作者态 Diary 统计（≥ 1.5.0）、访客视图与 H5 名片
 * （一律）——都经这里判「是不是账号级」，不各写一套。
 */
public final class MilestoneCounting {

    /** 账号成就组的 {@code level} 值。 */
    public static final String ACCOUNT_LEVEL = "ACCOUNT";

    private MilestoneCounting() {
    }

    /** 该完整 code 是否账号级（新口径下不计入宠物进度）。 */
    public static boolean isAccountLevel(String code) {
        return AccountMilestoneFlag.isAccountLevel(code);
    }

    /** 该物种清单里账号级编码的条数（= 3）。 */
    public static int accountLevelTotal(PetType petType) {
        return (int) MilestoneCatalog.forType(petType).stream().filter(d -> isAccountLevel(d.code())).count();
    }

    /** 新口径下该物种宠物里程碑总数（清单总数 − 账号级）。 */
    public static int petTotal(PetType petType) {
        return MilestoneCatalog.forType(petType).size() - accountLevelTotal(petType);
    }

    /**
     * 把宠物里程碑列表（旧形状：账号级 3 项在 S 组里）转成 ≥ 1.5.0 新形状：S 组与计数去掉账号级，另出 {@code accountGroup}。
     *
     * <p>账号组的完成态<b>以 flag 为准</b>（账号事实；某只宠物 roster 缺行 / 完成行时也一致）：
     * flag 存在 → completed；{@code completedAt} 取该宠物完成行，没有则取 flag 的 {@code occurred_at}；
     * {@code celebratedAt} 取完成行，没有完成行时 → Lulus 取 flag 的 {@code celebrated_at}，另两个取 {@code occurred_at}
     * （1.4.2 规则：它们一律视为已庆祝，不补弹）。
     *
     * @param flags 该账号已有的 flag 行
     */
    public static MilestoneListResponse withAccountGroup(MilestoneListResponse r, PetType petType,
            List<UserMilestoneFlag> flags) {
        if (r == null || r.groups() == null) {
            return r;
        }
        Map<String, UserMilestoneFlag> byKey = new HashMap<>();
        for (UserMilestoneFlag f : flags) {
            byKey.put(f.getFlagKey(), f);
        }
        Map<String, MilestoneItemResponse> removed = new HashMap<>();
        int removedTotal = 0;
        int removedDone = 0;
        List<MilestoneGroupResponse> groups = new ArrayList<>(r.groups().size());
        for (MilestoneGroupResponse g : r.groups()) {
            if (g.items() == null) {
                groups.add(g);
                continue;
            }
            List<MilestoneItemResponse> kept = new ArrayList<>(g.items().size());
            int gRemoved = 0;
            int gRemovedDone = 0;
            for (MilestoneItemResponse i : g.items()) {
                if (isAccountLevel(i.code())) {
                    removed.put(i.code(), i);
                    gRemoved++;
                    if (i.completed()) {
                        gRemovedDone++;
                    }
                } else {
                    kept.add(i);
                }
            }
            removedTotal += gRemoved;
            removedDone += gRemovedDone;
            groups.add(new MilestoneGroupResponse(g.level(), g.completedCount() - gRemovedDone,
                    g.totalCount() - gRemoved, kept));
        }

        List<MilestoneItemResponse> accountItems = new ArrayList<>(AccountMilestoneFlag.values().length);
        int accountDone = 0;
        for (AccountMilestoneFlag flag : AccountMilestoneFlag.values()) {
            String code = flag.codeOf(petType);
            UserMilestoneFlag row = byKey.get(flag.name());
            MilestoneItemResponse fromRoster = removed.get(code);
            accountItems.add(accountItem(flag, code, row, fromRoster));
            if (row != null) {
                accountDone++;
            }
        }
        MilestoneGroupResponse accountGroup =
                new MilestoneGroupResponse(ACCOUNT_LEVEL, accountDone, accountItems.size(), accountItems);
        return new MilestoneListResponse(r.petName(), r.petAvatarUrl(), r.completedCount() - removedDone,
                r.totalCount() - removedTotal, groups, accountGroup);
    }

    private static MilestoneItemResponse accountItem(AccountMilestoneFlag flag, String code, UserMilestoneFlag row,
            MilestoneItemResponse fromRoster) {
        MilestoneDefinition def = MilestoneCatalog.byCode(code);
        String title = fromRoster != null ? fromRoster.title() : (def != null ? def.titleZh() : code);
        String level = fromRoster != null ? fromRoster.level() : (def != null ? def.level().name() : "S");
        String trigger = fromRoster != null ? fromRoster.triggerType()
                : (def != null ? def.trigger().name() : "SYSTEM_AUTO");
        if (row == null) {
            return new MilestoneItemResponse(code, title, level, trigger, false, null, null);
        }
        boolean rosterDone = fromRoster != null && fromRoster.completed();
        Instant completedAt = rosterDone ? fromRoster.completedAt() : row.getOccurredAt();
        Instant celebratedAt;
        if (rosterDone) {
            celebratedAt = fromRoster.celebratedAt();
        } else if (flag == AccountMilestoneFlag.LULUS_PEMULA) {
            celebratedAt = row.getCelebratedAt();
        } else {
            celebratedAt = row.getOccurredAt();
        }
        return new MilestoneItemResponse(code, title, level, trigger, true, completedAt, celebratedAt);
    }
}
