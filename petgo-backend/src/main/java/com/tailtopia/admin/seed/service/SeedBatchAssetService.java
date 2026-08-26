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

    public SeedBatchAssetService(SeedBatchAssetRepository assets, AdminSeedImageService images,
            MessageSource messages) {
        this.assets = assets;
        this.images = images;
        this.messages = messages;
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

        // 格式 / 单张大小 / 量宽高 / 落存储全部复用 12-2 那条链路（含 HEIC 拒绝与裁切预判）。
        UploadedImage up = images.upload(file, "seed-batch/" + batchId);
        try {
            return assets.save(SeedBatchAsset.of(batchId, fileName, up.objectKey(), up.url(),
                    up.w(), up.h(), up.sizeBytes()));
        } catch (DataIntegrityViolationException e) {
            // 唯一索引兜底：两个浏览器标签同时拖同名文件时，应用层那次查重会双双通过。
            throw AppException.validation(msg("admin.batch.asset.duplicateName",
                    new Object[] {fileName}));
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
