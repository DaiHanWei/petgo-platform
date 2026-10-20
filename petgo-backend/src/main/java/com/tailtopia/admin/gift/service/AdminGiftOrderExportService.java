package com.tailtopia.admin.gift.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.gift.dto.GiftOrderAdminRow;
import com.tailtopia.admin.shared.export.AdminExportWriter;
import com.tailtopia.admin.shared.time.AdminTime;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 送礼记录导出 xlsx（V1.4.0 ③ gift Story 3.2 · AC4，照 {@code AdminPaymentExportService}）。
 *
 * <ul>
 *   <li>🔴 <b>跟随屏幕上的筛选条件</b>：参数与列表逐个对齐；列 = 当前视图的列（全站 / 异常单），
 *       另把送礼人 / 主人拆成「ID」「昵称」两列方便透视。</li>
 *   <li>🔴 <b>不静默截断</b>：超过 {@link #EXPORT_MAX_ROWS} 在表尾第一列写说明、审计记 {@code truncated=true}。</li>
 *   <li>数量 / 单价 / 总价为数字单元格；表头随界面语言；时间列带「WIB」。</li>
 *   <li>门同查看码 {@code gift.order_view}（PRD 只定义一个只读码，Story 3.2「待确认」1），每次导出记审计。</li>
 * </ul>
 */
@Service
public class AdminGiftOrderExportService {

    /** 导出一次最多带出多少行。到顶时在表尾与审计里都写明，绝不静默截断。 */
    static final int EXPORT_MAX_ROWS = 5000;

    private static final String P = "admin.v140.giftOrders.";

    /** 全站视图表头 key（⚠️ 顺序 = 列顺序 = {@link #exportXlsx} 的取值顺序）。 */
    static final List<String> HEADER_KEYS = List.of(
            P + "col.time", P + "col.senderId", P + "col.sender", P + "col.pet", P + "col.ownerId", P + "col.owner",
            P + "col.gift", P + "col.qty", P + "col.unitPrice", P + "col.total", P + "col.channel", P + "col.status",
            P + "col.intent");

    /** 异常单视图表头 key：去掉 payment_intent，加「原因」「PawCoin 退回」（AC3.1）。 */
    static final List<String> ANOMALY_HEADER_KEYS = List.of(
            P + "col.time", P + "col.senderId", P + "col.sender", P + "col.pet", P + "col.ownerId", P + "col.owner",
            P + "col.gift", P + "col.qty", P + "col.unitPrice", P + "col.total", P + "col.channel", P + "col.status",
            P + "col.reason", P + "col.refund");

    private static final DateTimeFormatter WIB = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(AdminTime.ZONE);

    private final AdminGiftOrderQueryService query;
    private final AdminAuditService audit;
    private final Messages msg;

    public AdminGiftOrderExportService(AdminGiftOrderQueryService query, AdminAuditService audit, Messages msg) {
        this.query = query;
        this.audit = audit;
        this.msg = msg;
    }

    /** 按筛选条件导出 .xlsx 字节流。⚠️ 不能标 readOnly：审计是一条 INSERT。 */
    @Transactional
    public byte[] exportXlsx(long actorAccountId, AdminGiftOrderQueryService.Filter f) {
        // 多取一行判断「是不是还有更多」，不额外发一次 count。
        List<GiftOrderAdminRow> rows = query.searchAll(f, EXPORT_MAX_ROWS + 1);
        boolean truncated = rows.size() > EXPORT_MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, EXPORT_MAX_ROWS);
        }
        boolean anomaly = f.anomalyView();
        List<String> headers = (anomaly ? ANOMALY_HEADER_KEYS : HEADER_KEYS).stream().map(msg::get).toList();
        Locale locale = LocaleContextHolder.getLocale();
        List<List<Object>> data = new ArrayList<>(rows.size() + 1);
        for (GiftOrderAdminRow r : rows) {
            List<Object> line = new ArrayList<>(Arrays.asList(
                    r.createdAt() == null ? "" : WIB.format(r.createdAt()) + " WIB",
                    r.senderId(),
                    r.senderDeleted() ? msg.get(P + "deletedUser", r.senderIdText()) : nz(r.senderName()),
                    r.petDeleted() ? msg.get(P + "deletedPet", r.petName()) : r.petName(),
                    r.ownerId(),
                    r.ownerDeleted() ? msg.get(P + "deletedUser", r.ownerIdText()) : nz(r.ownerName()),
                    r.giftName(locale),
                    r.quantity(), // Number → 数字单元格
                    r.unitPrice(),
                    r.total(),
                    msg.get(P + "channel." + r.channel()),
                    msg.get(P + "status." + r.statusGroup())));
            if (anomaly) {
                line.add(reasonText(r.undeliveredReason()));
                line.add(refundText(r));
            } else {
                // 导出给全值（页面上是缩写）：文件里要能回查；PawCoin 单留空。
                line.add(r.intentToken() == null ? "" : r.intentToken());
            }
            data.add(line);
        }
        if (truncated) {
            // 🔴 截断说明留在表尾第一列：文件被单独转发时，这是唯一能说明「这份表不全」的地方。
            //    ⚠️ 传 String：int 会被 MessageFormat 格式化成「5,000」。
            data.add(List.of(msg.get(P + "export.truncated", String.valueOf(EXPORT_MAX_ROWS))));
        }
        byte[] body;
        try {
            body = AdminExportWriter.xlsx("gift-orders", headers, data);
        } catch (java.io.UncheckedIOException e) {
            throw AppException.serviceUnavailable("Excel 导出生成失败").code("admin.err.giftOrders.exportFailed");
        }
        audit.record(actorAccountId, AuditActions.GIFT_ORDER_LIST_EXPORTED, "gift_order", "-",
                "rows=" + rows.size() + " truncated=" + truncated
                        + " senderId=" + f.senderId() + " ownerId=" + f.ownerId() + " channel=" + f.channel()
                        + " status=" + f.status() + " from=" + f.from() + " to=" + f.to());
        return body;
    }

    /** 未送达原因文案；未知值原样显示代码（AC3.1）。 */
    String reasonText(String reason) {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case "PET_DELETED", "OWNER_DELETED", "SENDER_BLOCKED" -> msg.get(P + "reason." + reason);
            default -> reason;
        };
    }

    /** PawCoin 退回文案（AC3.1）：已退回带金额与时间；待重试 / 需人工带已重试次数。不渲染 refund_error。 */
    String refundText(GiftOrderAdminRow r) {
        return switch (r.status()) {
            case "UNDELIVERED_REFUNDED" -> msg.get(P + "refund.done", r.totalText(),
                    r.refundedAt() == null ? "" : WIB.format(r.refundedAt()) + " WIB");
            case "UNDELIVERED_REFUND_PENDING" -> msg.get(P + "refund.pending") + " · "
                    + msg.get(P + "refund.attempts", r.refundAttemptsText());
            case "REFUND_MANUAL" -> msg.get(P + "refund.manual") + " · "
                    + msg.get(P + "refund.attempts", r.refundAttemptsText());
            default -> "";
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
