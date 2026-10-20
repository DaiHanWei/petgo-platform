package com.tailtopia.profile.slot;

import java.util.List;

/**
 * 一次 reconcile 的结果（V1.4.0 multi-pet Story 2.1 · AD-4）：被转只读 / 被恢复正常的宠物，顺序 = reconcile 顺序键。
 * Story 2.3 用 {@link #demoted} 发 {@code PET_SLOT_EXPIRED} 通知（含宠物名）。
 *
 * <p>🔒 {@link PetRef#name} 是宠物名：只给通知文案用，<b>不得</b>写日志。
 */
public record ReconcileResult(List<PetRef> demoted, List<PetRef> restored) {

    public ReconcileResult {
        demoted = List.copyOf(demoted);
        restored = List.copyOf(restored);
    }

    public static ReconcileResult none() {
        return new ReconcileResult(List.of(), List.of());
    }

    public boolean changed() {
        return !demoted.isEmpty() || !restored.isEmpty();
    }

    /** 宠物引用（id + 名字）。 */
    public record PetRef(long id, String name) {
    }
}
