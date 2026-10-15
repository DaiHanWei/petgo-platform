package com.tailtopia.ops.popup.domain;

/**
 * 弹窗人群（{@code ck_ops_popups_audience}）。
 *
 * <p>ALL 全部（含游客）；NEW 注册时长 ≤ {@code new_user_days} × 24h（恰好 N×24h 算 NEW）；
 * OLD 注册时长 &gt; N×24h。游客只匹配 ALL。
 */
public enum OpsPopupAudience {
    ALL,
    NEW,
    OLD
}
