package com.tailtopia.profile.visitor;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 他人公开主页的**宠物区**（V1.3.0 batch-b1 Story 2.3 · FR-118.2 · AC3）。
 * {@code GET /api/v1/users/{userId}/pet}
 *
 * <h2>为什么是独立端点而不是塞进 {@code /users/{id}/profile}</h2>
 * 两条理由：
 * <ol>
 *   <li><b>模块方向</b>：宠物档案归 {@code profile} 模块，而 {@code profile} 已经依赖
 *       {@code auth}（投影层要查主人昵称 / 是否 active）。把宠物塞进 auth 的主页响应里，
 *       就成了 auth → profile 的反向依赖。</li>
 *   <li><b>失败面</b>：宠物卡取数挂掉时，主页的身份区与内容区照常渲染 ——
 *       一个装饰性区块不该有能力把整页拖垮。</li>
 * </ol>
 *
 * <h2>🛡 没有宠物 → 204，不是 404</h2>
 * 「这个人没建过档案」是**正常状态**（状态 B / C 的用户本来就没有宠物），
 * 用 404 表达它，客户端就没法把它与「用户不存在」区分开，只能靠猜。
 *
 * <h2>游客可读，与主页同一口径</h2>
 * 点头像看这人是谁不需要登录，那么看到他养了只什么也不需要。
 * ⚠️ 但**点进去**的访客视图<b>仅登录可用</b>（AC1，{@link InAppVisitorPetController}）——
 * 客户端在跳转处走 FR-0C 登录门控。两层边界不同是刻意的。
 *
 * <h2>🔴 主动拉黑者拿不到</h2>
 * 与 {@code /users/{id}/profile} 逐条一致：命中即 403，**只认 BLOCK**（举报隐藏放行）。
 * 三个端点（主页 / 内容区 / 宠物卡）各拦一次 —— 只拦一个就等于留了两个绕过口。
 */
@RestController
public class PublicProfilePetController {

    private final ProfileService profiles;
    private final VisitorProjectionService visitors;
    private final AccountQueryService accounts;
    private final UserHideRelationReader hideRelations;

    public PublicProfilePetController(ProfileService profiles, VisitorProjectionService visitors,
            AccountQueryService accounts, UserHideRelationReader hideRelations) {
        this.profiles = profiles;
        this.visitors = visitors;
        this.accounts = accounts;
        this.hideRelations = hideRelations;
    }

    /**
     * @return 有档案 → 200 + 卡片投影；没有 / 主人注销或被封 → <b>204 No Content</b>
     *
     * <p>⚠️ 用 {@link ResponseEntity#noContent()} 而不是 {@code return null}：
     * 后者出去的是 <b>200 + 空 body</b>，与 Javadoc、客户端注释、测试名说的 204 都不是一回事
     * （code-review 2026-09-15）。客户端靠状态码分辨「没宠物」与「有宠物但字段全空」。
     */
    @GetMapping("/api/v1/users/{userId}/pet")
    public ResponseEntity<PublicProfilePetResponse> pet(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long userId) {
        Long viewerId = viewerId(jwt);
        if (viewerId != null && hideRelations.isBlocked(viewerId, userId)) {
            throw AppException.blockedUser("你已拉黑该用户");
        }
        // 🛡 主人注销 / 被封 → 当作没有宠物，而不是把档案照常发出去。
        // 判定走 accountQueryService.isActive，与访客投影层同一句（不另写一套）。
        if (!accounts.isActive(userId)) {
            return ResponseEntity.noContent().build();
        }
        Optional<PetProfile> pet = profiles.findByOwnerId(userId);
        if (pet.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        // Diary 条数与点进去之后统计条上那个数**同一个实现**（否则用户第一眼就看出对不上）。
        // ⚠️ 走 `diaryCount` 而不是 `stats(...).happyMomentCount()`：后者会连带算问诊次数、
        //    里程碑进度与两次健康表计数 —— 五条查询换一个数，而这是个游客可达的端点。
        return ResponseEntity.ok(
                PublicProfilePetResponse.of(pet.get(), visitors.diaryCount(pet.get())));
    }

    /** 登录<b>用户</b> id（游客 / 非 USER 角色 → null）。兽医 {@code sub=vetId} 与 users.id 会碰撞。 */
    private static Long viewerId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null
                || !"USER".equals(jwt.getClaimAsString("role"))) {
            return null;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
