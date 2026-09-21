package com.tailtopia.admin.payment.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.payment.dto.AdminPaymentRow;
import com.tailtopia.admin.shared.export.AdminExportWriter;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付记录导出为 Excel（2026-08-31）。
 *
 * <p>与内容列表导出同一套纪律：
 * <ul>
 *   <li>🔴 <b>独立权限</b> {@code payment.list_export}，与查看分开 —— 导出是把支付数据
 *       批量带出系统。导出动作<b>记审计</b>（操作人 / 条数 / 筛选条件）。</li>
 *   <li>🔴 <b>跟随屏幕上的筛选条件</b>：导出的就是运营正在看的那一份，少一个参数
 *       就会导出一份跟屏幕对不上的表。</li>
 *   <li>🔴 <b>不静默截断</b>：超过 {@link #EXPORT_MAX_ROWS} 时在表尾追加一行说明、
 *       审计里也记 truncated。</li>
 * </ul>
 *
 * <p>产物是真正的 .xlsx（POI 已是既有依赖，种子批量模板在用）而不是内容列表那样的
 * CSV+BOM —— 金额列是数字单元格，运营拿去做透视/求和不用先转格式。
 *
 * <p>⚠️ 列里刻意<b>不带汇总</b>（订单数/现金收入）：那几个数口径特殊（未支付不计现金、
 * PawCoin 不是现金、混合只计现金段），离开页面上那段说明文字单独出现在文件里，
 * 就是一个看着权威的错数。要汇总看屏幕上那张卡。
 */
@Service
public class AdminPaymentExportService {

    /** 导出一次最多带出多少行。到顶时在表尾与审计里都写明，绝不静默截断。 */
    static final int EXPORT_MAX_ROWS = 5000;

    /**
     * 表头 key（V1.3.0 Story 8.5：由写死英文标识符改为按界面语言解析）。
     *
     * <p>⚠️ 顺序 = 列顺序 = 下面 {@code row(...)} 的取值顺序，三处必须同步改。
     */
    private static final List<String> HEADER_KEYS = List.of(
            "admin.v130.payments.export.userId",
            "admin.v130.payments.export.paymentNo",
            "admin.v130.payments.export.purpose",
            "admin.v130.payments.export.channel",
            "admin.v130.payments.export.amount",
            "admin.v130.payments.export.currency",
            "admin.v130.payments.export.status",
            "admin.v130.payments.export.createdAt");

    private final AdminPaymentQueryService query;
    private final AdminAuditService audit;

    /** 表头与截断说明按当前界面语言输出（V1.3.0 Story 2.3a 规则 12）。 */
    private final Messages msg;

    public AdminPaymentExportService(AdminPaymentQueryService query, AdminAuditService audit,
            Messages msg) {
        this.query = query;
        this.audit = audit;
        this.msg = msg;
    }

    /**
     * 按筛选条件导出 .xlsx 字节流（首行表头；时间列为 WIB 字样，与页面同口径）。
     *
     * <p>⚠️ 不能标 readOnly：审计是一条 INSERT（与内容列表导出同一形状）。
     */
    @Transactional
    public byte[] exportXlsx(long actorAccountId, AdminPaymentQueryService.Filter f) {
        // 多取一行判断「是不是还有更多」，不额外发一次 count。
        List<AdminPaymentRow> rows = query.searchAll(f, EXPORT_MAX_ROWS + 1);
        boolean truncated = rows.size() > EXPORT_MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, EXPORT_MAX_ROWS);
        }
        List<String> headers = HEADER_KEYS.stream().map(msg::get).toList();
        List<List<Object>> data = new java.util.ArrayList<>(rows.size() + 1);
        for (AdminPaymentRow p : rows) {
            data.add(java.util.Arrays.asList(
                    p.userId(),
                    // 支付号优先可读号（与页面同源同串）；无可读号回退 publicToken —— 别留空，
                    // 空串会让这一行在表里无法回查。
                    p.displayNo() != null ? p.displayNo() : p.publicToken(),
                    p.purpose(),
                    p.channel(),
                    p.amount(), // Number → 数字单元格：运营可直接求和/透视
                    p.currency(),
                    p.status(),
                    p.createdAtLabel() == null ? "" : p.createdAtLabel()));
        }
        if (truncated) {
            // 🔴 截断说明留在**表尾第一列**（与旧实现同位置）：文件被单独转发时，
            //    这是唯一能说明「这份表不全」的地方。
            data.add(java.util.List.of(// ⚠️ 传 String：int 会被 MessageFormat 交给 NumberFormat，变成「5,000 行」，
            //    而这份表里其它数字都没有千分位。
            msg.get("admin.v130.payments.export.truncated", String.valueOf(EXPORT_MAX_ROWS))));
        }
        // V1.3.0 Story 8.5：改经 AdminExportWriter（2.3a 起全站导出一个出口，表头随 locale）。
        // ⚠️ 写入器把 IOException 包成 UncheckedIOException 往上抛，那会落进兜底 handler
        //    变成一句没头没尾的 500。这里翻回本模块原有的 AppException，
        //    运营看到的仍是「Excel 导出生成失败」而不是「系统错误」。
        byte[] body;
        try {
            body = AdminExportWriter.xlsx("payments", headers, data);
        } catch (java.io.UncheckedIOException e) {
            throw AppException.serviceUnavailable("Excel 导出生成失败")
                    .code("admin.err.payments.exportFailed");
        }
        audit.record(actorAccountId, "PAYMENT_LIST_EXPORT", "payment_intent", "-",
                "rows=" + rows.size() + " truncated=" + truncated
                        + " userId=" + f.userId() + " purpose=" + f.purpose()
                        + " status=" + f.status() + " from=" + f.from() + " to=" + f.to());
        return body;
    }
}
