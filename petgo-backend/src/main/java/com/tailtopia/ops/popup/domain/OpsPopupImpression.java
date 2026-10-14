package com.tailtopia.ops.popup.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 运营弹窗曝光（登录用户的频次记录；游客频次只在设备本地，AD-15）。
 *
 * <p>不去重：DAILY 跨日需要多行，同日重复上报只是多一行，判定按「是否存在」不受影响。
 * {@code shownAt} 一律服务端时刻（客户端不传时间，防手机时间被改）。
 * 注销时由 {@code OpsPopupImpressionDeletionService} 物理删除（D1/D2）。
 */
@Entity
@Table(name = "ops_popup_impressions")
public class OpsPopupImpression {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "popup_id", nullable = false, updatable = false)
    private Long popupId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "shown_at", nullable = false, updatable = false)
    private Instant shownAt;

    protected OpsPopupImpression() {
    }

    public static OpsPopupImpression of(long popupId, long userId, Instant shownAt) {
        OpsPopupImpression i = new OpsPopupImpression();
        i.popupId = popupId;
        i.userId = userId;
        i.shownAt = shownAt;
        return i;
    }

    public Long getId() {
        return id;
    }

    public Long getPopupId() {
        return popupId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getShownAt() {
        return shownAt;
    }
}
