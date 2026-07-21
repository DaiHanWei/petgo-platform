package com.tailtopia.admin.virtual.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.service.AdminSeedBatchService;
import com.tailtopia.admin.virtual.service.AdminSeedBatchService.BatchResult;
import com.tailtopia.shared.error.AppException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 轻量批量种子发布（Story 9.8 Part 2，AB-1.1-02）。Thymeleaf admin slice，{@code /admin/seed-batch}。
 * 门控 {@code virtual_account.manage}。选虚拟账号 + 多行文本逐条发 DAILY，内容 hash 跨批去重。
 */
@Controller
public class AdminSeedBatchController {

    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('virtual_account.manage')";

    private final AdminSeedBatchService batch;

    public AdminSeedBatchController(AdminSeedBatchService batch) {
        this.batch = batch;
    }

    @GetMapping("/admin/seed-batch")
    @PreAuthorize(AUTH)
    public String form() {
        return "redirect:/admin/seed-post?tab=batch";
    }

    @GetMapping("/admin/seed-batch/template")
    @PreAuthorize(AUTH)
    public ResponseEntity<byte[]> template() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("批量发布示例");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("正文");
            header.createCell(1).setCellValue("图片URL");
            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("今天带喵星人晒太阳啦");
            row1.createCell(1).setCellValue("https://cdn.example.com/cat-1.jpg");
            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("第二条日常，多张图用英文逗号分隔");
            row2.createCell(1).setCellValue("https://cdn.example.com/dog-1.jpg,https://cdn.example.com/dog-2.jpg");
            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);
            workbook.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=seed-batch-template.xlsx")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw AppException.serviceUnavailable("Excel 示例生成失败");
        }
    }

    @PostMapping("/admin/seed-batch")
    @PreAuthorize(AUTH)
    public String publish(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long virtualUserId, @RequestParam String lines, RedirectAttributes flash) {
        try {
            BatchResult r = batch.publishBatch(virtualUserId, lines, admin.getAdminAccountId());
            flash.addFlashAttribute("notice",
                    "批量完成：发布 " + r.published() + " 条，去重跳过 " + r.skipped() + " 条");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/seed-post?tab=batch";
    }

    @PostMapping("/admin/seed-batch/import")
    @PreAuthorize(AUTH)
    public String importFile(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long virtualUserId, @RequestParam MultipartFile file, RedirectAttributes flash) {
        try {
            String lines = batch.readLines(file);
            BatchResult r = batch.publishBatch(virtualUserId, lines, admin.getAdminAccountId());
            flash.addFlashAttribute("notice",
                    "Excel 导入完成：发布 " + r.published() + " 条，去重跳过 " + r.skipped() + " 条");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/seed-post?tab=batch";
    }
}
