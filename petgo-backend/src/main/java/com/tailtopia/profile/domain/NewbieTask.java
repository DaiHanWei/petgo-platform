package com.tailtopia.profile.domain;

import java.util.List;

/**
 * 6 个新手任务（Story 7.3 / FR-47），**独立计数**。全部完成 → 自动解锁聚合里程碑
 * 「Lulus Pemula」（{@link MilestoneCatalog#lulusPemulaCode}）。
 *
 * <p>判定来源：前 5 个复用既有里程碑完成信号（语义后缀 S1–S5，三宠物类型统一）；第 6 个
 * 「录入一条健康记录」取 {@code health_records} 存在性（{@code milestoneSuffix == null} 标记）。
 * key 为稳定对外标识（前端按 key 本地化标签，不渲染后端串）。
 */
public enum NewbieTask {

    /** 创建宠物档案。 */
    CREATE_PROFILE("S1"),
    /** 上传第一张成长日历照片。 */
    FIRST_PHOTO("S2"),
    /** 第一次分享宠物名片。 */
    SHARE_CARD("S3"),
    /** 保存一次兽医问诊结论。 */
    SAVE_CONSULT("S4"),
    /** 发布一条日常分享。 */
    FIRST_DAILY("S5"),
    /** 录入一条健康记录（任一类型，取 {@code health_records} 存在性）。 */
    FIRST_HEALTH_RECORD(null);

    private final String milestoneSuffix;

    NewbieTask(String milestoneSuffix) {
        this.milestoneSuffix = milestoneSuffix;
    }

    /** 对应里程碑语义后缀（S1–S5）；健康记录任务返回 {@code null}（走存在性判定）。 */
    public String milestoneSuffix() {
        return milestoneSuffix;
    }

    /** 是否「录入健康记录」任务（非里程碑节点）。 */
    public boolean isHealthRecord() {
        return milestoneSuffix == null;
    }

    /** 固定展示顺序（枚举声明序）。 */
    public static final List<NewbieTask> ALL = List.of(values());
}
