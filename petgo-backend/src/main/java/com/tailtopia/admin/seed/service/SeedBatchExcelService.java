package com.tailtopia.admin.seed.service;

import com.tailtopia.admin.seed.service.SeedBatchEntryService.RawRow;
import com.tailtopia.admin.virtual.dto.PublishIdentityOption;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.error.AppException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Excel 模板与导入（V1.1.6 Story 13.3 · AC4）。
 *
 * <p><b>此前模板只有「正文」「图片URL」两列</b>，图片得填 URL —— 运营先得去别处传图拿链接。
 * 现在六列：正文 / 图片文件名 / 发布账号 / 内容类型 / 关联物种 / 计划发布时间，
 * 图片列填的是 <b>13-2 已上传素材的文件名</b>（顺序即展示顺序）。
 *
 * <h2>🔴 模板带下拉数据校验</h2>
 * 内容类型 / 关联物种 / 发布账号三列在 Excel 内做成下拉可选 ——
 * 「比事后校验拦截体验好一个量级」：运营直接选，而不是手打然后等报错。
 *
 * <p>🔴 <b>内容类型下拉只含 {@code DAILY} / {@code KNOWLEDGE}</b>：
 * 若模板里能选到 {@code GROWTH_MOMENT}，运营选了就是**整行必然失败**（A-10），
 * 属可以从源头避免的错误。
 *
 * <p>⚠️ 枚举名<b>别照 PRD 字面抄</b>：PRD 写 {@code MOMENT}，代码里是 {@code DAILY}。
 *
 * <p>选项放在第二张表（{@code 选项}）里、下拉用**区间引用**而不是内联列表：
 * 内联列表在 Excel 里有 255 字符上限，而发布账号列表是动态的、很容易超。
 */
@Service
public class SeedBatchExcelService {

    /** 运营填的墙上时间按这个时区解释（与顶置管理同口径）。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 🔴 批量支持的内容类型 —— 刻意不含 {@code GROWTH_MOMENT}（A-10）。 */
    public static final List<ContentType> BATCH_TYPES = List.of(ContentType.DAILY, ContentType.KNOWLEDGE);

    /**
     * 关联物种下拉取值。
     *
     * <p>⚠️ 取自既有 {@link PetType} 再加 {@code GENERAL}（通用）——
     * AB-3H 规定存量虚拟账号统一置为 {@code GENERAL}，所以它必须是个可选值。
     * 字段本体属 Story 14-1，本 story 只负责在模板里留出这一列。
     */
    public static final List<String> SPECIES_OPTIONS =
            List.of("GENERAL", PetType.CAT.name(), PetType.DOG.name(), PetType.OTHER.name());

    private static final String[] HEADERS = {
            "正文", "图片文件名", "发布账号", "内容类型", "关联物种", "计划发布时间"};

    /** 列头下面那一行说明 —— 运营打开模板第一眼看到的就是它。 */
    private static final String[] HINTS = {
            "一条一行。长正文可含换行，写在同一单元格里即可",
            "填本批已上传素材的文件名，多张用英文逗号分隔，顺序即展示顺序",
            "留空则继承页头设置的默认发布账号",
            "留空则继承页头设置的默认类型。不支持「成长日历」",
            "留空时：虚拟账号继承其账号物种定位；运营真实账号留空由算法推导",
            "格式 2026-09-01 08:30（印尼时间 WIB）。留空则继承批次默认；都留空 = 立即发布"};

    /** 生成带下拉数据校验的模板。 */
    public byte[] template(List<PublishIdentityOption> identities) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("批量内容");
            Sheet opts = wb.createSheet("选项");

            Row header = sheet.createRow(0);
            CellStyle bold = wb.createCellStyle();
            var font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
                header.getCell(i).setCellStyle(bold);
            }
            Row hintRow = sheet.createRow(1);
            for (int i = 0; i < HINTS.length; i++) {
                hintRow.createCell(i).setCellValue(HINTS[i]);
            }

            // 选项表：A 列内容类型、B 列物种、C 列发布账号。
            int maxRows = Math.max(Math.max(BATCH_TYPES.size(), SPECIES_OPTIONS.size()),
                    identities.size());
            for (int i = 0; i < maxRows; i++) {
                Row r = opts.createRow(i);
                if (i < BATCH_TYPES.size()) {
                    r.createCell(0).setCellValue(BATCH_TYPES.get(i).name());
                }
                if (i < SPECIES_OPTIONS.size()) {
                    r.createCell(1).setCellValue(SPECIES_OPTIONS.get(i));
                }
                if (i < identities.size()) {
                    r.createCell(2).setCellValue(identityLabel(identities.get(i)));
                }
            }

            DataValidationHelper helper = sheet.getDataValidationHelper();
            // 数据行从第 3 行（下标 2）开始 —— 第 1 行列头、第 2 行说明。
            addDropdown(sheet, helper, 3, "选项!$A$1:$A$" + BATCH_TYPES.size());
            addDropdown(sheet, helper, 4, "选项!$B$1:$B$" + SPECIES_OPTIONS.size());
            if (!identities.isEmpty()) {
                addDropdown(sheet, helper, 2, "选项!$C$1:$C$" + identities.size());
            }

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.setColumnWidth(i, 30 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw AppException.serviceUnavailable("Excel 模板生成失败")
                    .code("admin.err.seedBatch.templateBuildFailed");
        }
    }

    /**
     * 发布账号在 Excel 里的显示文本。
     *
     * <p>⚠️ 带上 {@code (id=N)} 是为了**回读时能确定地解析出账号** ——
     * 昵称可能重复、也可能被改。真实账号前面那个 ⚠️ 与选择器里的标记同源
     * （内容会出现在那个真人的个人主页并推送给他的粉丝）。
     */
    public static String identityLabel(PublishIdentityOption o) {
        return (o.real() ? "⚠️ " : "") + o.nickname() + " (id=" + o.userId() + ")";
    }

    private static void addDropdown(Sheet sheet, DataValidationHelper helper, int col,
            String rangeFormula) {
        DataValidationConstraint c = helper.createFormulaListConstraint(rangeFormula);
        // 到第 1000 行为止 —— 单批上限远小于它，够用且不会让文件变大。
        DataValidation v = helper.createValidation(c, new CellRangeAddressList(2, 1000, col, col));
        v.setShowErrorBox(true);
        v.setSuppressDropDownArrow(true);
        sheet.addValidationData(v);
    }

    /**
     * 解析导入文件。
     *
     * <p>🛡 <b>解析失败的行不丢弃</b>：把问题写成该行的错误信息，仍然生成草稿行 ——
     * 丢弃的话运营对不上"我明明有 50 行，怎么只进来 47 行"。
     * 那份错误由 13-4 的校验预览逐行展示。
     */
    public List<RawRow> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw AppException.validation("请选择要导入的 Excel 文件")
                    .code("admin.err.seedBatch.fileRequired");
        }
        List<RawRow> out = new ArrayList<>();
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter fmt = new DataFormatter();
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue; // 列头
                }
                String body = cell(fmt, row, 0);
                String names = cell(fmt, row, 1);
                String account = cell(fmt, row, 2);
                String type = cell(fmt, row, 3);
                String species = cell(fmt, row, 4);
                String at = cell(fmt, row, 5);
                if (body.isEmpty() && names.isEmpty() && account.isEmpty()) {
                    continue; // 空行 / 说明行
                }
                if (isHintRow(body)) {
                    continue;
                }
                out.add(new RawRow(body, splitNames(names), parseAccount(account),
                        parseType(type), species.isBlank() ? null : species.trim(),
                        parseTime(at)));
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw AppException.validation("Excel 解析失败，请用最新模板重试")
                    .code("admin.err.seedBatch.parseFailed");
        }
        if (out.isEmpty()) {
            throw AppException.validation("Excel 里没有有效的数据行")
                    .code("admin.err.seedBatch.noDataRows");
        }
        return out;
    }

    /**
     * 模板自带的说明行会被当成数据行读进来。
     *
     * <p>⚠️ 运营常见做法是**直接在模板上填**、把说明那行留着 ——
     * 不跳过它就会多出一条正文是"一条一行……"的内容。既有的批量导入也做过同样的跳过。
     */
    private static boolean isHintRow(String body) {
        return body.equals(HINTS[0]);
    }

    private static String cell(DataFormatter fmt, Row row, int idx) {
        return row.getCell(idx) == null ? "" : fmt.formatCellValue(row.getCell(idx)).trim();
    }

    private static List<String> splitNames(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 从 {@code 昵称 (id=123)} 里取 id；也接受纯数字。
     *
     * <p>⚠️ <b>只认 id，不按昵称反查</b>：昵称可能重复、也可能被改，
     * 按昵称猜一个账号出来是"看起来能用但可能发错人"的那类错误。
     * 解析不出来就返回 null ⇒ 走继承批次默认那条路。
     */
    public static Long parseAccount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var m = java.util.regex.Pattern.compile("id=(\\d+)").matcher(raw);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        String t = raw.trim();
        if (t.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(t);
        }
        return null;
    }

    /**
     * 内容类型。
     *
     * <p>🔴 {@code GROWTH_MOMENT} **在这里就拒绝**：模板下拉里没有它，
     * 但运营可以手打进去。返回 null 会让它静默继承批次默认 —— 那更糟（发出去的类型不是他写的）。
     */
    public static ContentType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (ContentType c : BATCH_TYPES) {
            if (c.name().equals(t)) {
                return c;
            }
        }
        throw AppException.validation("内容类型「" + raw + "」不可用于批量发布（只支持 "
                + BATCH_TYPES.stream().map(Enum::name).toList() + "）")
                .code("admin.err.seedBatch.typeNotAllowed", raw,
                        BATCH_TYPES.stream().map(Enum::name).toList().toString());
    }

    /** {@code yyyy-MM-dd HH:mm}（WIB 墙上时间）→ UTC。解析不了就当没填。 */
    public static java.time.Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim().replace('T', ' '), TIME_FMT)
                    .atZone(WIB).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
