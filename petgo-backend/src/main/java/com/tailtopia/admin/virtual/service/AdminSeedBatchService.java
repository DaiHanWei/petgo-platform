package com.tailtopia.admin.virtual.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.virtual.domain.SeedContentHash;
import com.tailtopia.admin.virtual.repository.SeedContentHashRepository;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.error.AppException;
import java.nio.charset.StandardCharsets;
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
    private final AdminPublishIdentityService identities;
    private final AdminAuditService audit;

    public AdminSeedBatchService(UserRepository users, ContentService contentService,
            SeedContentHashRepository hashes, AdminPublishIdentityService identities,
            AdminAuditService audit) {
        this.users = users;
        this.contentService = contentService;
        this.hashes = hashes;
        this.identities = identities;
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
     * 批量发布。校验作者**在身份池内** + enabled；逐行解析 → hash 去重 → 发布 → 记 hash + 计数。
     *
     * <p>🔴 <b>V1.1.6 Story 12.1 放开的就是这里</b>：原先是一句
     * {@code author.getAccountType() != AccountType.VIRTUAL} 的硬断言，它是当时唯一挡着
     * 运营真实账号发布的东西。改成问「在不在身份池内」——
     * <b>而不是</b>去改那个账号的 {@code account_type}（改类型会让它在 App 内的一切行为
     * 走进未被验证的分支，见 {@link AdminPublishIdentityService}）。
     */
    @Transactional
    public BatchResult publishBatch(long virtualUserId, String rawLines, long adminId,
            boolean callerMayPublishAsRealIdentity) {
        User author = users.findById(virtualUserId)
                .orElseThrow(() -> AppException.notFound("发布账号不存在"));
        if (!identities.isInPool(author)) {
            throw AppException.validation("该账号不在运营发布身份池内，不能作为发布者");
        }
        // 🔴 AC5 ②：**以运营真实账号身份发布**需要独立权限码 seed.publish_as_real。
        //
        // 为什么把它做成一个**显式入参**而不是在服务里读 SecurityContext：
        // 这个检查要在三处发布入口（12-2 单条 / 13-x 批量 / 13-5 定时）都成立，
        // 而"忘记检查"是静默的 —— 加个参数就让漏掉变成**编译错误**。
        // 从 SecurityContext 里偷偷读，新入口作者不会知道有这回事。
        if (!callerMayPublishAsRealIdentity && identities.isRealPublishIdentity(author.getId())) {
            throw AppException.validation("以运营真实账号发布内容需要单独授权（seed.publish_as_real）");
        }
        if (!author.isEnabled()) {
            // 虚拟账号"停用"与真实账号"被封"在这里是同一件事：都不该继续替它发内容。
            throw AppException.validation("该发布账号已停用");
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
            String hash = com.tailtopia.admin.seed.service.SeedContentFingerprint.of(
                    ContentType.DAILY, text, images);
            // 🔴 V1.1.6 Story 13.4：判据加了**作者维度** —— 同一文案不同账号各自独立。
            //    原先按 hash 单列判，"同一文案换个账号再发一遍"（内容运营的常规操作）
            //    会被静默吞掉。
            //
            // ⚠️ 这条老路径（Story 9.8 的"贴进去就发"）**仍然保留静默跳过** ——
            //    它没有预览这一步，无处展示提示。AC4 要求的"改为提示、由运营决定"
            //    落在新工作台的校验预览里（13-4 的 SeedBatchValidator）。
            //    两条路径的去重**判据**已统一；差别只在"命中之后怎么告诉人"。
            if (hashes.existsByContentHashAndAuthorId(hash, virtualUserId)) {
                skipped++;
                continue;
            }
            ContentPostResponse saved = contentService.publish(virtualUserId,
                    new ContentPostCreateRequest(ContentType.DAILY, null, text, images),
                    UUID.randomUUID().toString());
            // AC7：记下**实际按下发布的后台账号** —— 真实账号是某个真人的账号，出事要追到人。
            hashes.save(SeedContentHash.of(hash, saved.id(), virtualUserId, adminId));
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

    // ⚠️ 原先这里有一份私有的 contentHash 实现。V1.1.6 Story 13.4 抽成
    //    SeedContentFingerprint 共享 —— 两条录入路径写进的是**同一张指纹表**，
    //    各算一份的表现是"老路径发过的文案，新工作台判不出重复"，而那种不一致没人会想到去查。
}
