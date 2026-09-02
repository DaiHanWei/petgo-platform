package com.tailtopia.admin.service;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营种子内容发布（Story 3.1，FR-18）。
 *
 * <p>模块边界：**经 {@link ContentService} 写入，绝不直接访问 content repository**。种子内容写入
 * 与用户内容同一张 {@code content_posts}、同一套字段，仅 {@code author_id} 指向运营账号；
 * **无任何 seed/official 区分列**——Feed 读取侧（Story 3.2）无从分辨，实现混排不打标记（AC2）。
 */
@Service
public class AdminContentService {

    private static final int MAX_TEXT = 1000;
    private static final int MAX_IMAGES = 9;
    private static final Logger log = LoggerFactory.getLogger(AdminContentService.class);

    private final ContentService contentService;
    private final com.tailtopia.auth.repository.UserRepository users;
    private final com.tailtopia.admin.virtual.service.AdminPublishIdentityService identities;

    public AdminContentService(ContentService contentService,
            com.tailtopia.auth.repository.UserRepository users,
            com.tailtopia.admin.virtual.service.AdminPublishIdentityService identities) {
        this.contentService = contentService;
        this.users = users;
        this.identities = identities;
    }

    /**
     * 以运营账号发布一条种子内容。
     *
     * @param authorUserId 发布账号（Story 12.2：来自表单，但服务端校验它在身份池内）
     * @param type        三类内容类型之一（DAILY/KNOWLEDGE/GROWTH_MOMENT）
     * @param petId       仅 GROWTH_MOMENT 需绑定且须属该运营账号（复用 content 校验链）
     * @param text        正文 ≤1000 字符（服务端权威校验）
     * @param imageUrls   公开桶 CDN URL 列表 ≤9
     */
    public ContentPostResponse publishSeed(long authorUserId, ContentType type, Long petId,
            String text, List<String> imageUrls,
            List<com.tailtopia.content.domain.ImageSize> imageSizes,
            boolean callerMayPublishAsRealIdentity) {
        return publishSeed(authorUserId, type, petId, text, imageUrls, imageSizes,
                callerMayPublishAsRealIdentity, null);
    }

    /**
     * 带关联物种的版本（V1.1.6 Story 14.1 · AC4）。
     *
     * <p>🔴 物种落的是 {@code content_posts.species_override}（**行级覆写**）——
     * 运营在单条发布页明确选了一个值，那就是覆写；留空则不写，
     * 由读时推导按"账号定位 → 作者宠物档案"回落。
     */
    @Transactional
    public ContentPostResponse publishSeed(long authorUserId, ContentType type, Long petId,
            String text, List<String> imageUrls,
            List<com.tailtopia.content.domain.ImageSize> imageSizes,
            boolean callerMayPublishAsRealIdentity, String species) {
        if (type == null) {
            throw AppException.validation("内容类型不能为空").code("admin.err.seed.typeRequired");
        }
        if (text != null && text.length() > MAX_TEXT) {
            throw AppException.validation("正文不能超过 " + MAX_TEXT + " 字").code("admin.err.seed.textTooLong", MAX_TEXT);
        }
        if (imageUrls != null && imageUrls.size() > MAX_IMAGES) {
            throw AppException.validation("最多 " + MAX_IMAGES + " 张图片").code("admin.err.seed.tooManyImages", MAX_IMAGES);
        }
        // 🔴 V1.1.6 Story 12.2：作者来自表单，所以这里必须自己把两道门补上 ——
        //    与批量发布（AdminSeedBatchService）**同一口径**，是 Story 12.1 AC5 ② 说的"三处"之二。
        com.tailtopia.auth.domain.User author = users.findById(authorUserId)
                .orElseThrow(() -> AppException.validation("发布账号不存在")
                        .code("admin.err.seed.authorNotFound"));
        if (!identities.isInPool(author)) {
            throw AppException.validation("该账号不在运营发布身份池内，不能作为发布者")
                    .code("admin.err.seed.authorNotInPool");
        }
        if (!author.isEnabled()) {
            throw AppException.validation("该发布账号已停用").code("admin.err.seed.authorDisabled");
        }
        if (!callerMayPublishAsRealIdentity && identities.isRealPublishIdentity(authorUserId)) {
            throw AppException.validation("以运营真实账号发布内容需要单独授权（seed.publish_as_real）")
                    .code("admin.err.seed.realIdentityNeedsGrant", "seed.publish_as_real");
        }
        ContentPostCreateRequest req = new ContentPostCreateRequest(type, petId, text, imageUrls,
                null, null, imageSizes);
        // 复用 content 写入路径（同一张表、同一套字段）；幂等键防后台表单重复提交。
        if (species != null && !species.isBlank()
                && !com.tailtopia.content.species.ContentSpecies.isValid(species)) {
            throw AppException.validation("关联物种取值须是 "
                    + com.tailtopia.content.species.ContentSpecies.ALL)
                    .code("admin.err.seed.speciesInvalid",
                            com.tailtopia.content.species.ContentSpecies.ALL);
        }
        ContentPostResponse saved = contentService.publish(authorUserId, req, UUID.randomUUID().toString());
        if (species != null && !species.isBlank()) {
            contentService.setSpeciesOverride(saved.id(), species.trim());
        }
        log.info("种子内容发布成功 authorUserId={} postId={} type={}", authorUserId, saved.id(), type);
        return saved;
    }
}
