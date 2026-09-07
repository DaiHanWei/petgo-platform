package com.tailtopia.admin.payment.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.payment.dto.AdminPaymentRow;
import com.tailtopia.shared.error.AppException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

    private static final String[] HEADERS = {
            "user_id", "payment_no", "purpose", "channel", "amount", "currency", "status",
            "created_at_wib"};

    private final AdminPaymentQueryService query;
    private final AdminAuditService audit;

    public AdminPaymentExportService(AdminPaymentQueryService query, AdminAuditService audit) {
        this.query = query;
        this.audit = audit;
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
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("payments");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
            }
            int rowIdx = 1;
            for (AdminPaymentRow p : rows) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(p.userId());
                // 支付号优先可读号（与页面同源同串）；无可读号回退 publicToken —— 别留空，
                // 空串会让这一行在表里无法回查。
                row.createCell(1).setCellValue(p.displayNo() != null ? p.displayNo() : p.publicToken());
                row.createCell(2).setCellValue(p.purpose());
                row.createCell(3).setCellValue(p.channel());
                Cell amount = row.createCell(4);
                amount.setCellValue(p.amount()); // 数字单元格：运营可直接求和/透视
                row.createCell(5).setCellValue(p.currency());
                row.createCell(6).setCellValue(p.status());
                row.createCell(7).setCellValue(p.createdAtLabel() == null ? "" : p.createdAtLabel());
            }
            if (truncated) {
                sheet.createRow(rowIdx).createCell(0).setCellValue(
                        "已达单次导出上限 " + EXPORT_MAX_ROWS + " 行，请收窄筛选条件后分批导出");
            }
            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }
            wb.write(out);
            audit.record(actorAccountId, "PAYMENT_LIST_EXPORT", "payment_intent", "-",
                    "rows=" + rows.size() + " truncated=" + truncated
                            + " userId=" + f.userId() + " purpose=" + f.purpose()
                            + " status=" + f.status() + " from=" + f.from() + " to=" + f.to());
            return out.toByteArray();
        } catch (IOException e) {
            throw AppException.serviceUnavailable("Excel 导出生成失败")
                    .code("admin.err.payments.exportFailed");
        }
    }
}
