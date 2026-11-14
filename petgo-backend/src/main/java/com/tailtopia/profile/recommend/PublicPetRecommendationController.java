package com.tailtopia.profile.recommend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 游客版推荐宠物（V1.5.0 onboarding-recall Story 1.1 · OR-2 · FR-174 / FR-175）。
 * {@code GET /api/v1/public/pet-recommendations?limit=}
 *
 * <h2>为什么另开一条，而不是让 {@code /me/pet-recommendations} 允许匿名</h2>
 * {@code /me} 下的那条是<b>按查看者算的</b>（拉黑 / 已追更 / 同品种排序），且刻意只认 {@code role=USER}。
 * 游客版是一份<b>全站公共列表</b>：落在已 {@code permitAll} 的 {@code /api/v1/public/**}，不改 {@code SecurityConfig}，
 * 不破坏 {@code /me} 的语义。带了有效 token 来调也返回同一份全站结果（不按查看者算）。
 *
 * <h2>形状</h2>
 * 只给第一页：{@code hasMore} 恒 false、{@code nextCursor} 恒 null；{@code limit} 默认 6、上限 10；
 * 字段集与登录版相同（{@code coverLikedByViewer} 恒 false），不新增任何账号私密字段。
 * 结果来自 10 分钟进程内快照（{@link PublicPetRecommendationSnapshot}，OR-5）。
 */
@RestController
public class PublicPetRecommendationController {

    /** 游客区横滑 6 张（FR-175）。 */
    static final int DEFAULT_LIMIT = 6;

    private final PublicPetRecommendationSnapshot snapshot;

    public PublicPetRecommendationController(PublicPetRecommendationSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @GetMapping("/api/v1/public/pet-recommendations")
    public RecommendedPetResponse.Page recommendations(
            @RequestParam(name = "limit", required = false) Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        return RecommendedPetResponse.Page.last(snapshot.firstPage(size));
    }
}
