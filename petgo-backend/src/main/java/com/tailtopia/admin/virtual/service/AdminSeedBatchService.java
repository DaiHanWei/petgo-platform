package com.tailtopia.admin.virtual.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.virtual.domain.SeedContentHash;
import com.tailtopia.admin.virtual.repository.SeedContentHashRepository;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.error.AppException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 轻量批量种子发布（Story 9.8 Part 2，AB-1.1-02；用户 2026-07-14 定「复用 seed-post + 虚拟账号」轻量方案）。
 * 以选定**虚拟账号**为作者逐条发 DAILY 种子（复用 {@link ContentService#publish}），**内容 hash 去重**跨批防重发。
 * 每行一条：{@code 文本} 或 {@code 文本 ||| url1, url2}（图 URL 逗号分隔，≤9）。
 */
@Service
public class AdminSeedBatchService {

    private static final String IMG_DELIM = "|||";
    private static final int MAX_IMAGES = 9;

    private final UserRepository users;
    private final ContentService contentService;
    private final SeedContentHashRepository hashes;
    private final AdminAuditService audit;

    public AdminSeedBatchService(UserRepository users, ContentService contentService,
            SeedContentHashRepository hashes, AdminAuditService audit) {
        this.users = users;
        this.contentService = contentService;
        this.hashes = hashes;
        this.audit = audit;
    }

    /** 批量结果。 */
    public record BatchResult(int published, int skipped) {
    }

    public String readLines(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw AppException.validation("请选择要导入的 Excel 文件");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            if (name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt")) {
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            }
            try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
                DataFormatter fmt = new DataFormatter();
                StringBuilder out = new StringBuilder();
                for (Row row : wb.getSheetAt(0)) {
                    String text = fmt.formatCellValue(row.getCell(0)).trim();
                    String images = fmt.formatCellValue(row.getCell(1)).trim();
                    if (text.isEmpty() || isHeader(text)) {
                        continue;
                    }
                    out.append(text);
                    if (!images.isEmpty()) {
                        out.append(' ').append(IMG_DELIM).append(' ').append(images);
                    }
                    out.append('\n');
                }
                return out.toString();
            }
        } catch (Exception e) {
            throw AppException.validation("Excel 导入失败，请检查文件格式");
        }
    }

    /**
     * 批量发布。校验虚拟账号（VIRTUAL + enabled）；逐行解析 → hash 去重 → 以虚拟账号发布 → 记 hash + 计数。
     */
    @Transactional
    public BatchResult publishBatch(long virtualUserId, String rawLines, long adminId) {
        User author = users.findById(virtualUserId)
                .orElseThrow(() -> AppException.notFound("虚拟账号不存在"));
        if (author.getAccountType() != AccountType.VIRTUAL) {
            throw AppException.validation("只能以虚拟账号批量发布");
        }
        if (!author.isEnabled()) {
            throw AppException.validation("该虚拟账号已停用");
        }
        if (rawLines == null || rawLines.isBlank()) {
            throw AppException.validation("批量内容为空");
        }

        int published = 0;
        int skipped = 0;
        for (String line : rawLines.split("\\R")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String text;
            List<String> images = null;
            int di = line.indexOf(IMG_DELIM);
            if (di >= 0) {
                text = line.substring(0, di).trim();
                images = parseImages(line.substring(di + IMG_DELIM.length()));
            } else {
                text = line.trim();
            }
            if (text.isEmpty()) {
                continue;
            }
            String hash = contentHash(text, images);
            if (hashes.existsById(hash)) {
                skipped++; // 跨批去重：已发过，跳过
                continue;
            }
            ContentPostResponse saved = contentService.publish(virtualUserId,
                    new ContentPostCreateRequest(ContentType.DAILY, null, text, images),
                    UUID.randomUUID().toString());
            hashes.save(SeedContentHash.of(hash, saved.id(), virtualUserId));
            author.incrementPublished();
            published++;
        }
        users.save(author);
        audit.record(adminId, "SEED_BATCH_PUBLISH", "user", String.valueOf(virtualUserId),
                "published=" + published + " skipped=" + skipped);
        return new BatchResult(published, skipped);
    }

    private static List<String> parseImages(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String u = part.trim();
            if (!u.isEmpty()) {
                out.add(u);
            }
        }
        if (out.isEmpty()) {
            return null;
        }
        if (out.size() > MAX_IMAGES) {
            throw AppException.validation("单条最多 " + MAX_IMAGES + " 张图片");
        }
        return out;
    }

    private static boolean isHeader(String text) {
        String t = text.toLowerCase();
        return t.equals("文本") || t.equals("正文") || t.equals("text") || t.equals("content");
    }

    /** sha256(type|text|sorted images) 十六进制。图排序保证顺序无关的稳定去重。 */
    private static String contentHash(String text, List<String> images) {
        StringBuilder sb = new StringBuilder("DAILY\n").append(text).append('\n');
        if (images != null) {
            images.stream().sorted().forEach(u -> sb.append(u).append(','));
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : d) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
