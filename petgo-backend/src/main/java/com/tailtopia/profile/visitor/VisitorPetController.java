package com.tailtopia.profile.visitor;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.shared.error.AppException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 内访客只读视图的对外接口（V1.1.6 Story 2.2 · 架构 AD-2 Rule 6）。
 *
 * <p>装了 App 的人点开别人分享的宠物链接，走的就是这里。数据全部出自
 * {@link VisitorProjectionService} —— 那一层<b>结构上就取不到</b>健康记录与问诊存档。
 *
 * <h2>🛡 为什么挂在 {@code /api/v1/public/} 下</h2>
 * <ol>
 *   <li><b>必须对未登录开放</b>：同一个链接在浏览器里无需登录即可看完整 Diary，
 *       App 内却要求登录会把用户推回浏览器（FR-92 §④）。
 *       {@code SecurityConfig} 里 {@code GET /api/v1/public/**} 已是 {@code permitAll}，
 *       <b>因此本 story 不需要改安全配置</b> —— 少动一次安全配置就少一次出错机会。</li>
 *   <li><b>只暴露 GET</b>：那条 permitAll 规则限定了 GET。本层永远只读，
 *       在这里加任何写方法都会因为落不进那条规则而立刻 401 —— 这是刻意留的绊子。</li>
 * </ol>
 *
 * <h2>🛡 身份来自 token，不来自登录态</h2>
 * 路径上的 {@code cardToken} 是<b>不可枚举</b>的分享 token（架构护栏：对外标识不用自增 id）。
 * ⚠️ <b>不得</b>复用作者态的 {@code /api/v1/pet-profiles/me/...} —— 那条路从 JWT 取身份，
 * 而访客根本没有身份；对未登录用户它还会落到给游客做的示例页（AD-2 Rule 6）。
 */
@RestController
@RequestMapping("/api/v1/public/shared-pets")
public class VisitorPetController {

    /**
     * 🛡 <b>四种失效共用这一句</b>：token 不存在 / 档案已删 / 账号已注销 / 账号被封号。
     *
     * <p>文案与状态码只要有任何差别，就等于给扫描者一个信号 ——
     * 拿一堆随机 token 扫一遍就能扫出哪些是真实用户、谁被封了。
     */
    private static final String GONE_DETAIL = "链接已失效";

    private final VisitorProjectionService visitors;

    public VisitorPetController(VisitorProjectionService visitors) {
        this.visitors = visitors;
    }

    /** 访客日历月视图。只返回<b>有 Diary 记录</b>的日子；无记录日与未来日由前端补格。 */
    @GetMapping("/{cardToken}/calendar")
    public VisitorCalendarMonth calendar(@PathVariable String cardToken,
            @RequestParam("year") int year, @RequestParam("month") int month) {
        return visitors.calendarMonth(requireVisible(cardToken), year, month);
    }

    /** 访客某天详情。 */
    @GetMapping("/{cardToken}/day")
    public DayItems day(@PathVariable String cardToken,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return new DayItems(date, visitors.dayDetail(requireVisible(cardToken), date));
    }

    /**
     * 取可见档案，否则统一失效。
     *
     * <p>⚠️ 判定<b>不在这里写</b>，收在 {@link VisitorProjectionService#findVisibleProfile(String)} ——
     * H5 与 App 两个出口共用同一个判定，才不会哪天只改了一处。
     */
    private PetProfile requireVisible(String cardToken) {
        return visitors.findVisibleProfile(cardToken)
                .orElseThrow(() -> AppException.notFound(GONE_DETAIL));
    }

    /**
     * 某天详情响应。
     *
     * <p>⚠️ 条目类型是 {@link VisitorTimelineItem} 而<b>不是</b>作者态的 {@code TimelineItemResponse} ——
     * 后者带着症状摘要、AI 分级、健康记录 id 四个字段。
     */
    public record DayItems(LocalDate date, List<VisitorTimelineItem> items) {
    }
}
