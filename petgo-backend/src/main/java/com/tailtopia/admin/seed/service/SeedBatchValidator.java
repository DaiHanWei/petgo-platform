package com.tailtopia.admin.seed.service;

import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.domain.SeedBatchAsset;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.dto.RowError;
import com.tailtopia.admin.seed.dto.RowValidation;
import com.tailtopia.admin.seed.repository.SeedBatchAssetRepository;
import com.tailtopia.admin.virtual.repository.SeedContentHashRepository;
import com.tailtopia.admin.virtual.service.AdminPublishIdentityService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 逐行校验（V1.1.6 Story 13.4 · AC1/AC3/AC4）。
 *
 * <h2>🔴 硬错误与提示是两回事</h2>
 * <ul>
 *   <li><b>硬错误</b>（账号不在池内、类型不支持、素材找不到、正文与图皆空…）⇒ 该行不发，
 *       留草稿可改后重提。🛡 <b>不阻塞整批</b> —— 50 行错 3 行，另外 47 行照发。</li>
 *   <li><b>去重命中</b>⇒ 只是**提示**，由运营决定。原先是**静默跳过**，
 *       界面只显示一个跳过条数，运营根本不知道哪一条被吞了。</li>
 * </ul>
 *
 * <p>🛡 <b>每条错误都要能对上运营那份表格</b>：素材报**文件名**、类型报**合法取值**、
 * 账号报**原因**。只说"第 7 行有误"等于让他自己猜。
 */
@Service
public class SeedBatchValidator {

    private final SeedBatchAssetRepository assets;
    private final UserRepository users;
    private final AdminPublishIdentityService identities;
    private final SeedContentHashRepository hashes;
    /** 指纹图片键解析（bug 20260901-467）：URL → 素材内容哈希，三条发布路径同一判据。 */
    private final SeedBatchAssetService assetService;

    public SeedBatchValidator(SeedBatchAssetRepository assets, UserRepository users,
            AdminPublishIdentityService identities, SeedContentHashRepository hashes,
            SeedBatchAssetService assetService) {
        this.assets = assets;
        this.users = users;
        this.identities = identities;
        this.hashes = hashes;
        this.assetService = assetService;
    }

    /** 校验一个批次的全部行。 */
    @Transactional(readOnly = true)
    public List<RowValidation> validate(SeedBatch batch, List<SeedBatchRow> rows) {
        Set<String> liveAssetUrls = new HashSet<>();
        for (SeedBatchAsset a : assets.findByBatchIdAndOrphanedAtIsNull(batch.getId())) {
            liveAssetUrls.add(a.getUrl());
        }
        List<RowValidation> out = new ArrayList<>(rows.size());
        for (SeedBatchRow row : rows) {
            out.add(validateRow(row, liveAssetUrls));
        }
        return out;
    }

    private RowValidation validateRow(SeedBatchRow row, Set<String> liveAssetUrls) {
        List<RowError> errors = new ArrayList<>();

        // ① 录入阶段已经记下的问题（缺账号 / 缺类型 / 素材名对不上）原样带出来 ——
        //    🛡 在这里重算一遍会和录入时的措辞分叉，而运营看到两种说法会以为是两个问题。
        //    bug 20260924-565：库里存的是文案码（i18n:…），解回 RowError；历史中文原样带出。
        errors.addAll(SeedRowErrors.decode(row.getErrorMessage()));

        // ② 发布账号：不在身份池内 / 已禁用 / 已移出。
        Optional<User> author = row.getAuthorUserId() == 0
                ? Optional.empty()
                : users.findById(row.getAuthorUserId());
        if (row.getAuthorUserId() == 0) {
            if (errors.isEmpty()) {
                // 🛡 与录入阶段（SeedBatchEntryService#appendRows）同一个码 —— 同一问题一种说法。
                errors.add(new RowError("admin.err.seedBatch.row.authorUnset"));
            }
        } else if (author.isEmpty()) {
            errors.add(new RowError("admin.err.seedBatch.row.authorNotFound",
                    String.valueOf(row.getAuthorUserId())));
        } else if (!identities.isInPool(author.get())) {
            // ⚠️ "已移出身份池"与"从来不在池里"在运营看来是同一件事，报同一句话即可 ——
            //    区分它们要额外查授权历史，而对"我该怎么办"没有影响。
            errors.add(new RowError("admin.err.seedBatch.row.authorNotInPool", author.get().getNickname()));
        } else if (!author.get().isEnabled()) {
            errors.add(new RowError("admin.err.seedBatch.authorDisabled", author.get().getNickname()));
        }

        // ③ 内容类型：🔴 GROWTH_MOMENT 单独给一句话，指向单条发布。
        //    只说"类型不合法"会让运营以为是填错字，而实际是"这条得换个入口发"。
        if (row.getContentType() == ContentType.GROWTH_MOMENT) {
            errors.add(new RowError("admin.err.seedBatch.row.growthMoment",
                    SeedBatchExcelService.BATCH_TYPES.stream().map(Enum::name).toList().toString()));
        } else if (row.getContentType() != null
                && !SeedBatchExcelService.BATCH_TYPES.contains(row.getContentType())) {
            // 与 Excel 导入（SeedBatchExcelService#parseType）同一个码。
            errors.add(new RowError("admin.err.seedBatch.typeNotAllowed", row.getContentType().name(),
                    SeedBatchExcelService.BATCH_TYPES.stream().map(Enum::name).toList().toString()));
        }

        // ④ 关联物种：不在枚举内 ⇒ 注明合法取值。
        if (row.getSpecies() != null && !row.getSpecies().isBlank()
                && !SeedBatchExcelService.SPECIES_OPTIONS.contains(row.getSpecies())) {
            errors.add(new RowError("admin.err.seedBatch.row.speciesInvalid", row.getSpecies(),
                    SeedBatchExcelService.SPECIES_OPTIONS.toString()));
        }

        // ⑤ 素材是否还在本批里。
        //    ⚠️ 录入之后素材可能被清理掉（13-2 的废弃回收），所以这里要再看一遍 ——
        //    否则会发出一条图片 404 的内容。
        if (row.getImageUrls() != null) {
            for (String url : row.getImageUrls()) {
                if (!liveAssetUrls.contains(url)) {
                    errors.add(new RowError("admin.err.seedBatch.row.assetGone"));
                    break;
                }
            }
        }

        // ⑥ 正文与图片皆空 —— 沿用 App 端的最低内容门槛。
        boolean noBody = row.getBody() == null || row.getBody().isBlank();
        boolean noImages = row.getImageUrls() == null || row.getImageUrls().isEmpty();
        if (noBody && noImages) {
            errors.add(new RowError("admin.err.seedBatch.row.empty"));
        }

        // ⑦ 去重：🔴 只是提示，不是错误。判据含**作者维度**（同一文案不同账号各自独立）。
        boolean duplicate = false;
        if (errors.isEmpty() && author.isPresent()) {
            String hash = SeedContentFingerprint.of(row.getContentType(), row.getBody(),
                    assetService.fingerprintKeys(row.getImageUrls()));
            duplicate = hashes.existsByContentHashAndAuthorId(hash, author.get().getId());
        }
        return new RowValidation(row, errors, duplicate);
    }
}
