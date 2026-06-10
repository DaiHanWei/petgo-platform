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

    public AdminContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    /**
     * 以运营账号发布一条种子内容。
     *
     * @param adminUserId 运营 ADMIN 账号 id（取自登录会话，不信任表单）
     * @param type        三类内容类型之一（DAILY/KNOWLEDGE/GROWTH_MOMENT）
     * @param petId       仅 GROWTH_MOMENT 需绑定且须属该运营账号（复用 content 校验链）
     * @param text        正文 ≤1000 字符（服务端权威校验）
     * @param imageUrls   公开桶 CDN URL 列表 ≤9
     */
    public ContentPostResponse publishSeed(long adminUserId, ContentType type, Long petId,
            String text, List<String> imageUrls) {
        if (type == null) {
            throw AppException.validation("内容类型不能为空");
        }
        if (text != null && text.length() > MAX_TEXT) {
            throw AppException.validation("正文不能超过 " + MAX_TEXT + " 字");
        }
        if (imageUrls != null && imageUrls.size() > MAX_IMAGES) {
            throw AppException.validation("最多 " + MAX_IMAGES + " 张图片");
        }
        ContentPostCreateRequest req = new ContentPostCreateRequest(type, petId, text, imageUrls);
        // 复用 content 写入路径（同一张表、同一套字段）；幂等键防后台表单重复提交。
        ContentPostResponse saved = contentService.publish(adminUserId, req, UUID.randomUUID().toString());
        log.info("种子内容发布成功 adminUserId={} postId={} type={}", adminUserId, saved.id(), type);
        return saved;
    }
}
