package com.tailtopia.moderation.domain;

/**
 * 账号举报工单状态（Story 2.1）。落库 varchar + UPPER_SNAKE，仅运营人工流转。
 *
 * <p>与内容举报的 {@link ReportStatus} <b>刻意分成两个枚举</b>：两条工单线的生命周期各自独立演进
 * （账号举报还有「已处置后又被举报 → 翻回待处理」这条内容侧没有的路径），共用一个枚举迟早互相绑架。
 */
public enum AccountReportStatus {

    /** 待处置。<b>已处置的工单再次被举报会翻回这个状态</b>（AC9），历史处置留痕不受影响。 */
    PENDING,

    /** 已处置（警告 / 封号等）。 */
    RESOLVED,

    /**
     * 无需处置。
     *
     * <p>⚠️ <b>运营后台展示为中性词「无需处置」</b>（{@code Tidak Perlu Tindakan} / No Action Needed，
     * 决策 C-103），<b>不叫「已驳回」</b>。改的只是展示层文案 —— 数据层这个值的名字保持 DISMISSED，
     * 别顺手一起改（举报侧的「驳回」动作按钮仍然叫驳回，那是准确的）。
     */
    DISMISSED
}
