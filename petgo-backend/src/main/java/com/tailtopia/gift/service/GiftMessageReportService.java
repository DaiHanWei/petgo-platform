package com.tailtopia.gift.service;

import com.tailtopia.moderation.domain.ReportReason;
import com.tailtopia.shared.error.AppException;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 送礼祝福语举报（V1.5.0 gift-pack Story 1-5 · D-8）：用户举报 + 后台两个处置动作（隐藏 / 驳回）的<b>写入单点</b>。
 * {@code gift_message_reports} 与 {@code gift_orders.message_hidden_at} 只经本类写。日志不记祝福语正文。
 */
@Service
public class GiftMessageReportService {

    private final NamedParameterJdbcTemplate jdbc;
    private final GiftOrderQuery orders;
    private final GiftShowcaseAccess access;

    public GiftMessageReportService(NamedParameterJdbcTemplate jdbc, GiftOrderQuery orders,
            GiftShowcaseAccess access) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.access = access;
    }

    /**
     * 举报一条祝福语。订单不存在 / 非已送达 / 宠物已删 / 对举报人不可见 → 404；无祝福语、已隐藏、举报人是送礼人 → 422；
     * 同人同单重复举报幂等。
     */
    @Transactional
    public void report(long reporterId, String orderToken, ReportReason reason) {
        if (reason == null) {
            throw AppException.validation("请选择举报类型");
        }
        GiftOrderRow r = orders.deliveredByToken(orderToken)
                .filter(o -> o.petId() != null)
                .orElseThrow(() -> AppException.notFound("礼物不存在"));
        access.requireVisible(reporterId, r.petId());
        if (!r.hasVisibleMessage() || r.senderId() == reporterId) {
            throw AppException.giftMessageNotReportable("这条祝福语不能举报");
        }
        jdbc.update("""
                INSERT INTO gift_message_reports (gift_order_id, reporter_user_id, reason_type)
                VALUES (:oid, :uid, :reason)
                ON CONFLICT (gift_order_id, reporter_user_id) DO NOTHING
                """, Map.of("oid", r.id(), "uid", reporterId, "reason", reason.name()));
    }

    /**
     * 后台「隐藏祝福语」：该订单全部 PENDING → ACTIONED，并隐藏祝福语（幂等）。
     *
     * @return 本次处置的举报条数
     */
    @Transactional
    public int hide(long orderId, long adminId) {
        int n = closePending(orderId, adminId, "ACTIONED");
        jdbc.update("UPDATE gift_orders SET message_hidden_at = now() WHERE id = :id AND message_hidden_at IS NULL",
                Map.of("id", orderId));
        return n;
    }

    /** 后台「驳回」：该订单全部 PENDING → DISMISSED。返回条数。 */
    @Transactional
    public int dismiss(long orderId, long adminId) {
        return closePending(orderId, adminId, "DISMISSED");
    }

    private int closePending(long orderId, long adminId, String target) {
        return jdbc.update("""
                UPDATE gift_message_reports
                   SET status = :target, handled_by = :admin, handled_at = now(), updated_at = now()
                 WHERE gift_order_id = :oid AND status = 'PENDING'
                """, new MapSqlParameterSource().addValue("target", target).addValue("admin", adminId)
                .addValue("oid", orderId));
    }
}
