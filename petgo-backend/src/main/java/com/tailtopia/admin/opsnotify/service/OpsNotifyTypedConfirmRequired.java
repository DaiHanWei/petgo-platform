package com.tailtopia.admin.opsnotify.service;

/**
 * 大范围发送须手输人数确认（UI 稿 I8 · 后台返工清单 §4）：人群为「全部用户」/ 未选任何条件的「按条件」，
 * 或预计（确认页人数与重算人数取大）超过 {@link AdminOpsNotifyService#TYPED_CONFIRM_THRESHOLD} 人，而表单没带
 * {@code confirmCount}。
 * 不是错误：Controller 接住后渲染输入确认弹层（复用 G4 组件），弹层内带 {@code confirmCount} 再提交一次。
 * 只在其余校验全部通过之后抛出（弹层里不会再冒出字段错误），且抛出时尚未认领一次性提交键。
 */
public class OpsNotifyTypedConfirmRequired extends RuntimeException {

    private final long count;

    public OpsNotifyTypedConfirmRequired(long count) {
        super(null, null, false, false);
        this.count = count;
    }

    /** 须手输的人数（= 确认页上的预计人数 {@code previewCount}）。 */
    public long count() {
        return count;
    }
}
