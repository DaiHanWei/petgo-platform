package com.tailtopia.admin.seed.service;

import com.tailtopia.admin.seed.domain.SeedBatchAsset;
import com.tailtopia.admin.seed.dto.UploadedImage;
import com.tailtopia.admin.seed.repository.SeedBatchAssetRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 批次素材上传与配额（V1.1.6 Story 13.2 · AB-3K Step 1）。
 *
 * <p><b>解决的是</b>：运营录内容之前得先去别处传图、拿链接、再粘回来。
 * 现在一次把这一批要用的图拖进来，录入时从缩略图墙上点选。
 *
 * <h2>三条配额规则</h2>
 * <ul>
 *   <li><b>单批 ≤200 张 / ≤500MB</b>。推算：每条内容最多 9 张，单批实际发 20~30 条已属偏多
 *       （校验预览要逐行过目），30×9=270，取 200 覆盖绝大多数并留余量。</li>
 *   <li>🛡 <b>上限按累计算</b>（AC3）—— 否则<b>分三次拖就能绕过限制</b>。</li>
 *   <li>🛡 <b>同批文件名不许重复</b>，且拦在**上传阶段**：拖到校验阶段才报错时，
 *       运营已经把整份表格填完了。运营常见做法就是"先拖猫的文件夹、再拖狗的"（A-9）。</li>
 * </ul>
 *
 * <p>🔴 <b>服务端必须自己再判一次配额</b>：页面上那层"选择时即拦截"只是体验 ——
 * 它省掉的是"等几分钟才发现超限"，但拦不住绕过页面直接打接口。
 */
@Service
public class SeedBatchAssetService {

    /** 单批张数上限。 */
    public static final int MAX_ASSETS = 200;

    /** 单批总字节上限（500MB）。 */
    public static final long MAX_TOTAL_BYTES = 500L * 1024 * 1024;

    private final SeedBatchAssetRepository assets;
    private final AdminSeedImageService images;
    private final MessageSource messages;
    // bug 20260901-468 第 5 处：上传素材也要 markSaved，否则「新建批次 → 只拖图 → 离开」
    // 的批次进不了列表，运营找不回来，7 天后连图被回收。
    private final com.tailtopia.admin.seed.repository.SeedBatchRepository batches;

    public SeedBatchAssetService(SeedBatchAssetRepository assets, AdminSeedImageService images,
            MessageSource messages,
            com.tailtopia.admin.seed.repository.SeedBatchRepository batches) {
        this.assets = assets;
        this.images = images;
        this.messages = messages;
        this.batches = batches;
    }

    /** 当前工作集（不含已废弃）。 */
    @Transactional(readOnly = true)
    public List<SeedBatchAsset> wall(long batchId) {
        return assets.findByBatchIdOrderByIdAsc(batchId).stream()
                .filter(a -> !a.isOrphaned()).toList();
    }

    /** 已用配额 —— 页面上那个"已选 37 张 / 92MB"的服务端权威值。 */
    @Transactional(readOnly = true)
    public Usage usage(long batchId) {
        List<SeedBatchAsset> live = assets.findByBatchIdAndOrphanedAtIsNull(batchId);
        long bytes = live.stream().mapToLong(SeedBatchAsset::getSizeBytes).sum();
        return new Usage(live.size(), bytes, MAX_ASSETS, MAX_TOTAL_BYTES);
    }

    /** 配额用量。 */
    public record Usage(int count, long bytes, int maxCount, long maxBytes) {

        public boolean full() {
            return count >= maxCount || bytes >= maxBytes;
        }
    }

    /**
     * 上传一张素材。
     *
     * <p>🔴 <b>校验顺序是刻意的：先查配额与重名，再落存储。</b>
     * 反过来的话，一张注定要被拒的图已经被写进对象存储了 ——
     * 而 F21 之下那个对象<b>删不掉</b>，于是每次重名重试都在攒垃圾。
     */
    @Transactional
    public SeedBatchAsset upload(long batchId, MultipartFile file) {
        String fileName = safeName(file);
        Usage used = usage(batchId);
        if (used.count() + 1 > MAX_ASSETS) {
            throw AppException.validation(msg("admin.batch.asset.tooMany",
                    new Object[] {MAX_ASSETS}));
        }
        if (assets.findByBatchIdAndFileName(batchId, fileName).isPresent()) {
            // 🛡 与**已在墙上的**素材一并查重（AC3）：分次追加时最容易撞的就是这个。
            throw AppException.validation(msg("admin.batch.asset.duplicateName",
                    new Object[] {fileName}));
        }
        if (used.bytes() + file.getSize() > MAX_TOTAL_BYTES) {
            throw AppException.validation(msg("admin.batch.asset.tooLarge",
                    new Object[] {MAX_TOTAL_BYTES / 1024 / 1024}));
        }

        // bug 20260901-467：文件内容 SHA-256 —— 素材级查重的判据（同内容改名/跨批都认得出）。
        // 字节本来就要整个读进来传 OSS，顺手摘要一次，代价可忽略。
        String sha;
        try {
            sha = sha256Hex(file.getBytes());
        } catch (java.io.IOException e) {
            throw AppException.validation(msg("admin.batch.asset.unreadable", new Object[] {fileName}));
        }

        // 格式 / 单张大小 / 量宽高 / 落存储全部复用 12-2 那条链路（含 HEIC 拒绝与裁切预判）。
        UploadedImage up = images.upload(file, "seed-batch/" + batchId);
        try {
            SeedBatchAsset a = SeedBatchAsset.of(batchId, fileName, up.objectKey(), up.url(),
                    up.w(), up.h(), up.sizeBytes());
            a.setContentSha256(sha);
            SeedBatchAsset saved = assets.save(a);
            // bug 20260901-468：拖了图即视为「这个批次是真的」——否则只传素材的批次
            // 不出现在列表里，除了记住 URL 没有任何路径能回来。markSaved 幂等。
            batches.findById(batchId).ifPresent(b -> {
                b.markSaved();
                batches.save(b);
            });
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 唯一索引兜底：两个浏览器标签同时拖同名文件时，应用层那次查重会双双通过。
            throw AppException.validation(msg("admin.batch.asset.duplicateName",
                    new Object[] {fileName}));
        }
    }

    /**
     * 素材墙的「内容重复」标记（bug 20260901-467，产品拍板：标记提示但放行）。
     *
     * @return assetId → 本地化提示「与批次 #X 的 y.jpg 内容相同」。只标**后传的那张**
     *         （与更早的比），首张不标 —— 两张都标会让人分不清谁重复了谁。
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, String> duplicateNotes(long batchId) {
        java.util.Map<Long, String> notes = new java.util.HashMap<>();
        for (SeedBatchAsset a : wall(batchId)) {
            if (a.getContentSha256() == null) {
                continue; // 存量素材无哈希，不参与
            }
            assets.findByContentSha256AndOrphanedAtIsNullOrderByIdAsc(a.getContentSha256()).stream()
                    .filter(other -> other.getId() < a.getId())
                    .findFirst()
                    .ifPresent(first -> notes.put(a.getId(),
                            msg("admin.batch.asset.duplicateContent",
                                    new Object[] {first.getBatchId(), first.getFileName()})));
        }
        return notes;
    }

    /**
     * 内容指纹用的图片键（bug 20260901-467 次生缺陷）：URL → 素材内容哈希。
     *
     * <p>🔴 指纹原来直接拼 URL，而每次上传的 URL 都带随机串 ⇒ 「同一张图 + 同一段正文」
     * 重传后指纹不同，**带图内容的跨批去重一直是失效的**。改为能解析到素材的 URL 用
     * {@code sha:<内容哈希>}，解析不到（站外 URL / 无哈希的存量素材）回落 URL 原文。
     * ⚠️ 三条发布路径都必须走这里 —— 判据分叉的表现是"另一条路径发过的判不出重复"。
     * 代价：存量指纹里带图的那部分从此对不上（它们本来也从没对上过）。
     */
    @Transactional(readOnly = true)
    public List<String> fingerprintKeys(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return urls;
        }
        java.util.Map<String, String> shaByUrl = new java.util.HashMap<>();
        for (SeedBatchAsset a : assets.findByUrlIn(urls)) {
            if (a.getContentSha256() != null) {
                shaByUrl.put(a.getUrl(), "sha:" + a.getContentSha256());
            }
        }
        return urls.stream().map(u -> shaByUrl.getOrDefault(u, u)).toList();
    }

    /** 十六进制 SHA-256。 */
    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : d) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 文件名。
     *
     * <p>⚠️ 只取**基名**：某些浏览器（和拖整个文件夹时）会带上路径，
     * 而带路径的名字会让"同名"判不出来（{@code 猫/1.jpg} 与 {@code 狗/1.jpg} 在运营眼里
     * 是同名的两张图，在字符串上却不是）。
     */
    private static String safeName(MultipartFile file) {
        String raw = file.getOriginalFilename();
        if (raw == null || raw.isBlank()) {
            throw AppException.validation("上传的文件没有文件名");
        }
        String base = raw.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        String name = slash >= 0 ? base.substring(slash + 1) : base;
        if (name.isBlank()) {
            throw AppException.validation("上传的文件没有文件名");
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String msg(String key, Object[] args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    /** 供扫描器与测试读全部（含已废弃）。 */
    @Transactional(readOnly = true)
    public List<SeedBatchAsset> allOf(long batchId) {
        return assets.findByBatchIdOrderByIdAsc(batchId);
    }

    /** 废弃素材台账总量（字节）。F21 反转后回收的对象就是这些。 */
    @Transactional(readOnly = true)
    public long orphanedBytes() {
        return assets.totalOrphanedBytes();
    }
}
