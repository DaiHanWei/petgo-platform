package com.tailtopia.profile.visitor;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.UserHideRelationReader;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宠物访客视图的**站内入口**（V1.3.0 batch-b1 Story 2.3 · AD-4 · B1-D1）。
 * {@code GET /api/v1/pets/{petId}/visitor/...}
 *
 * <h2>🔴 为什么另起一条路径，而不是把分享 token 发给站内访客</h2>
 * B1-D1 否掉了「由主页下发对方宠物的分享链接码」：那等于把一条<b>永久公开、可转发到站外</b>
 * 的链接发给每一个站内访客。<b>站内可见 ≠ 可对外分发。</b>
 * <p>所以本控制器<b>一个字节的 cardToken 都不下发</b>（AC2）——
 * 响应用的 {@link VisitorProfileResponse} 里物理上就没有那个字段。
 *
 * <h2>🔴 同一层投影，绝不复制（AD-4 Rule 2）</h2>
 * 数据全部出自 {@link VisitorProjectionService} —— 与
 * {@link VisitorPetController}（分享 token、游客可读）<b>同一份</b>。
 * 复制一份投影逻辑 = 将来改一处漏一处 = 私密数据从新入口漏出去。
 * <p>⚠️ 两者<b>只差鉴权边界</b>：那边挂在 {@code /api/v1/public/} 下（permitAll），
 * 本控制器在 {@code /api/v1/} 下，落进默认的 authenticated 规则 ——
 * <b>因此本 story 不改 SecurityConfig</b>（AC1「仅对登录用户开放」靠"不放行"实现，
 * 而不是靠再写一条规则；少动一次安全配置就少一次出错机会）。
 *
 * <h2>🔴 主动拉黑者同样进不来</h2>
 * 主页那条 {@code /users/{id}/profile} 已经 403 了，但<b>本端点按 petId 直达</b> ——
 * 只拦主页等于留了一个绕过口（FR-94 第 4 条 / 架构 S2：纯前端拦截可被深链绕过）。
 * ⚠️ <b>只认 BLOCK</b>，与主页同口径：举报隐藏照常放行。
 *
 * <h2>🔴 反向：**对方拉黑了我** → 与「这只宠物不存在」同一个 404（Story 2.5）</h2>
 * 主页上那条路已经把宠物卡收成 204 了，但本端点按 petId 直达 —— 不拦就等于留了个绕过口。
 * ⚠️ 用的是<b>同一句 GONE_DETAIL 同一个状态码</b>：与「档案已删 / 主人注销 / 主人被封」
 * 一并不可区分，否则一对比就能推断出自己被拉黑。
 *
 * <h2>⚠️ viewer 只认 {@code role=USER}</h2>
 * 兽医 token 的 {@code sub=vetId} 与 {@code users.id} 是两个会碰撞的命名空间，
 * 拿它查 {@code isBlocked} 就是用无关用户的隐藏关系做判断。非 USER 角色按「没有拉黑关系」走
 * —— 它仍然要登录才进得来，只是不参与拉黑判定。
 *
 * <h2>⚠️ 没有 calendar / day 两个端点</h2>
 * 访客视图<b>没有日历</b>（2026-08-18 与 08-28 两次拍板不做，UI 旧稿画错已于 2026-09-11 修订）。
 * 公开那条路径上留着的 calendar/day 是<b>有意保留的</b>（Story 2.2 建好且有测试），
 * <b>看到它不等于该在这里也开一份</b>。
 */
@RestController
@RequestMapping("/api/v1/pets/{petId}/visitor")
public class InAppVisitorPetController {

    /**
     * 🛡 三种失效共用这一句：档案不存在 / 主人已注销 / 主人被封号。
     *
     * <p>文案与状态码只要有差别，就等于告诉扫描者「这个 id 曾经有过一只宠物」。
     */
    private static final String GONE_DETAIL = "链接已失效";

    /** 🛡 时间线单次上限，与公开那条同一个数（{@code limit} 来自请求方，必须夹紧）。 */
    private static final int MAX_TIMELINE_LIMIT = 100;

    private final VisitorProjectionService visitors;
    private final UserHideRelationReader hideRelations;

    public InAppVisitorPetController(VisitorProjectionService visitors,
            UserHideRelationReader hideRelations) {
        this.visitors = visitors;
        this.hideRelations = hideRelations;
    }

    /** 访客看到的宠物档案。🛡 白名单 record，里面没有 cardToken（AC2）。 */
    @GetMapping("/profile")
    public VisitorProfileResponse profile(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long petId) {
        PetProfile pet = requireVisible(jwt, petId);
        return VisitorProfileResponse.of(pet, visitors.ownerNickname(pet));
    }

    /** 访客统计条三列。🛡 健康记录条数不在其中（{@link VisitorStats} 里根本没有那个字段）。 */
    @GetMapping("/stats")
    public VisitorStats stats(@AuthenticationPrincipal Jwt jwt, @PathVariable long petId) {
        return visitors.stats(requireVisible(jwt, petId));
    }

    /** 访客时间线。⚠️ 不分页，与公开那条同口径（「看一眼别人的宠物」，不是「翻完整个档案」）。 */
    @GetMapping("/timeline")
    public VisitorPetController.TimelineItems timeline(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long petId,
            @RequestParam(name = "limit", defaultValue = "30") int limit) {
        int safe = Math.max(1, Math.min(limit, MAX_TIMELINE_LIMIT));
        // 🔴 站内入口不含私密条目：petId 可枚举、主人未分享过（code review #2，见 VisitorProjectionService#timeline）
        return new VisitorPetController.TimelineItems(
                visitors.timeline(requireVisible(jwt, petId), safe, false));
    }

    /**
     * 取可见档案，否则统一失效；并拦下「已主动拉黑对方」的访客。
     *
     * <p>⚠️ 可见性判定<b>不在这里写</b>，收在
     * {@link VisitorProjectionService#findVisibleProfileById(long)} —— 两个入口共用同一个判定。
     */
    private PetProfile requireVisible(Jwt jwt, long petId) {
        PetProfile pet = visitors.findVisibleProfileById(petId)
                .orElseThrow(() -> AppException.notFound(GONE_DETAIL));
        Long viewerId = viewerId(jwt);
        if (viewerId != null && hideRelations.isBlocked(viewerId, pet.getOwnerId())) {
            throw AppException.blockedUser("你已拉黑该用户");
        }
        // 🔴 Story 2.5：**对方拉黑了我** → 落进上面那句同样的 404。
        // ⚠️ 这里**不能**抛 blockedUser（403）：那等于明白告诉他"你被拉黑了"，
        // 而 FR-118.5 的全部意义就是别让他确认这件事。
        if (viewerId != null && hideRelations.isBlocked(pet.getOwnerId(), viewerId)) {
            throw AppException.notFound(GONE_DETAIL);
        }
        return pet;
    }

    /** 登录<b>用户</b> id（非 USER 角色 → null，见类注释）。 */
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
