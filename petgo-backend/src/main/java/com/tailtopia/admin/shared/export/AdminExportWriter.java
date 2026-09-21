package com.tailtopia.admin.shared.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 导出统一出口（V1.3.0 Story 2.3a AC7，规则 12：CSV 正确转义、XLSX 一字段一列、表头随界面语言由调用方解析后传入）。
 * 架构模式规则：禁止各页自己拼字符串。
 * <ul>
 *   <li>{@link #xlsx}：POI，{@link Number} 落数字单元格，其余 {@code toString}，{@code autoSizeColumn}；</li>
 *   <li>{@link #csv}：RFC 4180——含 {@code "} / {@code ,} / 换行的字段加引号并 {@code "}→{@code ""}；
 *       前导 {@code = + - @} 的单元格前加 {@code '} 防公式注入；UTF-8 BOM 由调用方拼（{@code "﻿" + csv}）。</li>
 * </ul>
 * 现有三处导出（支付 xlsx / 内容 csv / 用户 xlsx）由各页 story 迁移（7.1 / 8.1 / 8.5）。
 */
public final class AdminExportWriter {

    private static final java.util.regex.Pattern PLAIN_NUMBER = java.util.regex.Pattern.compile("[-+]?\\d+(\\.\\d+)?");

    private AdminExportWriter() {
    }

    public static byte[] xlsx(String sheetName, List<String> headers, List<List<Object>> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName == null || sheetName.isBlank() ? "export" : sheetName);
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            int r = 1;
            for (List<Object> row : rows) {
                Row xr = sheet.createRow(r++);
                for (int i = 0; i < row.size(); i++) {
                    Object v = row.get(i);
                    Cell cell = xr.createCell(i);
                    if (v == null) {
                        cell.setBlank();
                    } else if (v instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                    } else if (v instanceof Boolean b) {
                        cell.setCellValue(b);
                    } else {
                        cell.setCellValue(String.valueOf(v));
                    }
                }
            }
            for (int i = 0; i < headers.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String csv(List<String> headers, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers.stream().map(h -> (Object) h).toList());
        for (List<Object> row : rows) {
            appendRow(sb, row);
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<Object> row) {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(row.get(i)));
        }
        sb.append("\r\n");
    }

    /** 单元格转义（包可见供单测）。 */
    static String cell(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v);
        // 公式注入防护：前导 = + - @ 的文本前加 '（Excel 不会把 '=SUM(...) 当公式）；纯数字文本（如 "-1"）放行。
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !(v instanceof Number) && !PLAIN_NUMBER.matcher(s).matches()) {
            s = "'" + s;
        }
        boolean quote = s.indexOf('"') >= 0 || s.indexOf(',') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
                || s.startsWith(" ") || s.endsWith(" ") || s.startsWith("\t");
        if (quote) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
