package com.tailtopia.healthreminder.rules;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 整宠重建的对账（V1.5.0 Story 2.1，架构增量 §4.3 第 1 ~ 4 步）。纯函数：只算「保留 / 取消 / 插入 / 补满足」，不写库。
 *
 * <ol>
 *   <li>期望集合里键相同的已有行 → 保留（不动 send_date / deferred_days）；</li>
 *   <li>已有行不在期望集合 → 取消，原因：宠物只读 → read_only；因已绝育去掉的 → neutered；来源记录被删（本次删除）→
 *       record_deleted；来源所在组出现更新 / 覆盖记录 → satisfied（带 by_record_id）；其余 → rebuilt；</li>
 *   <li>期望里的新键 → 插入，但同键已有 PENDING / SENT 行（任一 origin，唯一索引）→ 跳过（最多送一次）；</li>
 *   <li>已发（SENT）、记录类、尚未标满足的行，若其组现已被满足 → 标 satisfied_at（SM-7）。</li>
 * </ol>
 */
public final class ReminderReconciler {

    /** 取消原因的判定上下文。 */
    public record CancelContext(boolean readOnly, boolean neutered, Long deletedRecordId,
            SatisfactionLookup satisfaction) {
    }

    /** 一条取消决定。 */
    public record Cancel(HealthReminder row, CancelReason reason, Long satisfiedByRecordId) {
    }

    /** 一条「已发行补满足」决定。 */
    public record MarkSatisfied(HealthReminder row, long byRecordId) {
    }

    /** 对账结果。 */
    public record Result(List<HealthReminder> kept, List<Cancel> cancels, List<DesiredReminder> inserts,
            List<MarkSatisfied> satisfiedSent) {
    }

    private ReminderReconciler() {
    }

    /**
     * @param managed  参与对账的行：该宠物 PENDING、origin ∈ {RECORD, PROFILE, EVENT}、send_date ≥ fromDate
     * @param occupied 其余占着唯一键的行（全部 SENT 行、窗口外或排队 / 补发的 PENDING 行）
     */
    public static Result diff(List<HealthReminder> managed, List<HealthReminder> occupied,
            List<DesiredReminder> desired, CancelContext ctx) {
        Map<DesiredReminder.Key, DesiredReminder> want = new LinkedHashMap<>();
        for (DesiredReminder d : desired) {
            want.putIfAbsent(d.key(), d);
        }
        List<HealthReminder> kept = new ArrayList<>();
        List<Cancel> cancels = new ArrayList<>();
        Set<DesiredReminder.Key> have = new HashSet<>();
        for (HealthReminder row : managed) {
            DesiredReminder.Key k = keyOf(row);
            if (want.containsKey(k) && have.add(k)) {
                kept.add(row);
            } else {
                cancels.add(cancelOf(row, ctx));
            }
        }
        Set<DesiredReminder.Key> taken = new HashSet<>(have);
        for (HealthReminder row : occupied) {
            if (row.getStatus() != ReminderStatus.CANCELLED) {
                taken.add(keyOf(row));
            }
        }
        List<DesiredReminder> inserts = new ArrayList<>();
        for (Map.Entry<DesiredReminder.Key, DesiredReminder> e : want.entrySet()) {
            if (!taken.contains(e.getKey())) {
                inserts.add(e.getValue());
            }
        }
        List<MarkSatisfied> satisfiedSent = new ArrayList<>();
        for (HealthReminder row : occupied) {
            if (row.getStatus() == ReminderStatus.SENT && row.getSatisfiedAt() == null
                    && row.getOrigin() == ReminderOrigin.RECORD) {
                ctx.satisfaction().satisfiedBy(row.getSourceRecordId())
                        .ifPresent(by -> satisfiedSent.add(new MarkSatisfied(row, by)));
            }
        }
        return new Result(kept, cancels, inserts, satisfiedSent);
    }

    public static DesiredReminder.Key keyOf(HealthReminder r) {
        return new DesiredReminder.Key(r.getSourceRecordId() == null ? 0L : r.getSourceRecordId(),
                r.getReminderKind(), r.getPushPoint(), r.getDueDate());
    }

    private static Cancel cancelOf(HealthReminder row, CancelContext ctx) {
        if (ctx.readOnly()) {
            return new Cancel(row, CancelReason.READ_ONLY, null);
        }
        if (ctx.neutered() && row.getReminderKind().droppedWhenNeutered()) {
            return new Cancel(row, CancelReason.NEUTERED, null);
        }
        Long source = row.getSourceRecordId();
        if (source != null && source.equals(ctx.deletedRecordId())) {
            return new Cancel(row, CancelReason.RECORD_DELETED, null);
        }
        Optional<Long> by = ctx.satisfaction().satisfiedBy(source);
        if (by.isPresent()) {
            return new Cancel(row, CancelReason.SATISFIED, by.get());
        }
        return new Cancel(row, CancelReason.REBUILT, null);
    }
}
