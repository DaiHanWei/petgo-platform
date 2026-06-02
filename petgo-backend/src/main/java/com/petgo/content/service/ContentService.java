package com.petgo.content.service;

import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import com.petgo.content.dto.ContentPostCreateRequest;
import com.petgo.content.dto.ContentPostResponse;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.profile.service.ProfileService;
import com.petgo.shared.error.AppException;
import com.petgo.shared.ratelimit.IdempotencyService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容发布服务（Story 2.3）。三类发布 + 成长日历绑宠物校验 + Idempotency-Key 去重。
 *
 * <p>模块边界：成长日历归属校验经 {@link ProfileService}（**禁 content 直接读 pet_profiles 表**）。
 * {@code authorId} 一律取自 JWT，不信任客户端。
 */
@Service
public class ContentService {

    private final ContentPostRepository posts;
    private final ProfileService profileService;
    private final IdempotencyService idempotency;

    public ContentService(ContentPostRepository posts, ProfileService profileService,
            IdempotencyService idempotency) {
        this.posts = posts;
        this.profileService = profileService;
        this.idempotency = idempotency;
    }

    @Transactional
    public ContentPostResponse publish(long authorId, ContentPostCreateRequest req, String idempotencyKey) {
        // 幂等重放：同 key 已落一条则取回，不重复创建。
        Optional<Long> existing = idempotency.findResourceId(idempotencyKey);
        if (existing.isPresent()) {
            return posts.findById(existing.get())
                    .map(ContentPostResponse::from)
                    .orElseThrow(() -> AppException.notFound("内容不存在"));
        }

        Long petId = req.petId();
        if (req.type() == ContentType.GROWTH_MOMENT) {
            // 成长日历必须绑定属于当前用户的宠物档案。
            if (petId == null) {
                throw AppException.validation("成长日历快乐时刻需绑定宠物档案");
            }
            if (!profileService.ownsPet(authorId, petId)) {
                throw AppException.validation("无法绑定该宠物档案");
            }
        } else {
            // 普通类型不绑宠物，忽略客户端误传的 petId。
            petId = null;
        }

        List<String> imageUrls = req.imageUrls();
        if (imageUrls != null && imageUrls.size() > 9) {
            throw AppException.validation("最多 9 张图片");
        }

        ContentPost saved = posts.save(ContentPost.publish(
                authorId, req.type(), petId, blankToNull(req.text()), imageUrls));

        idempotency.store(idempotencyKey, saved.getId());
        return ContentPostResponse.from(saved);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
