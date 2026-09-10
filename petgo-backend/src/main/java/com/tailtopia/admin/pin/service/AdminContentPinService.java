package com.tailtopia.admin.pin.service;

import com.tailtopia.admin.pin.dto.PinDetail;
import com.tailtopia.admin.pin.dto.PinRow;
import com.tailtopia.admin.pin.dto.PinSummary;
import com.tailtopia.admin.pin.dto.PinnableContentRow;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.PinObjectType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentDisplayability;
import com.tailtopia.content.service.ContentPinService;
import com.tailtopia.shared.schedule.SchedulePhase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 顶置管理的后台视图与写入（Story 11.1 · AB-10A）。
 *
 * <p>🔴 <b>本类不重新实现任何机制</b>。排期写入、同坑位重叠校验、生效判定、下架/注销联动
 * 全部早在 Story 4.1 就随 {@link ContentPinService} 落地了 ——
 * 当时的注释写得很清楚：「本 story 没有对外接口，交付的是机制，供后台接上来之后直接调用」。
 * 本类就是那个「接上来」。
 *
 * <p>🛡 「内容失效未生效」的判定走 {@link ContentDisplayability} —— 与 App 的 Feed 坑位**同一份**。
 */
@Service
public class AdminContentPinService {

    /** 选择器每页条数。候选集接近全量内容，必须分页。 */
    private static final int PICK_PAGE_SIZE = 20;

    /** 列表摘要截断长度。 */
    private static final int SUMMARY_MAX = 40;

    /** 列表每页条数（V1.3.0 Story 7.3 · AC2）。 */
    public static final int PAGE_SIZE = 20;

    private final ContentPinService pins;
    private final ContentPostRepository posts;
    private final AdminAuditService audit;
    /** 抽屉里的作者昵称（Story 7.3 · AC3 顶置内容预览）。 */
    private final com.tailtopia.auth.service.AccountQueryService accountQuery;

    public AdminContentPinService(ContentPinService pins, ContentPostRepository posts,
            AdminAuditService audit, com.tailtopia.auth.service.AccountQueryService accountQuery) {
        this.pins = pins;
        this.posts = posts;
        this.audit = audit;
        this.accountQuery = accountQuery;
    }

    /** 一页排期 + 有无下一页（列表按生效时间倒序）。 */
    public record PinPage(List<PinRow> rows, boolean hasNext, int page) {
    }

    /**
     * 列表（Story 7.3 · AC1 / AC2）：按状态筛 + 生效时间倒序 + 分页。
     *
     * <p>⚠️ 筛选与分页都在**应用层**做：{@code phase} 由 {@code ScheduleWindow} 判定（含「提前结束」
     * 这种只看 {@code starts_at/ends_at} 判不出来的情况），拿 SQL 再算一遍就有第二份口径。
     * 一个坑位的全部排期是低基数数据，整表读一次不是问题。
     *
     * @param status {@code ACTIVE / PENDING / ENDED}；空或非法值 = 不筛（手改 URL 不该出 500）
     */
    @Transactional(readOnly = true)
    public PinPage page(String slot, String status, int page, Instant now) {
        return page(list(slot, now), status, page);
    }

    /**
     * 同上，但复用调用方已取好的整表 —— 一次请求里列表与摘要条共用一次读，
     * 且两者的 phase 判定用的是**同一个 now**（各自取 now 会在跨秒时给出不一致的状态）。
     */
    public PinPage page(List<PinRow> rows, String status, int page) {
        SchedulePhase want = phaseFilter(status);   // 提到循环外：非法 status 时不必每行构造一次异常
        List<PinRow> all = rows.stream()
                .filter(r -> want == null || matches(r, want))
                .sorted(java.util.Comparator.comparing(PinRow::startsAt).reversed()
                        .thenComparing(java.util.Comparator.comparingLong(PinRow::id).reversed()))
                .toList();
        // ⚠️ 先把页码钳到合法范围再算 offset：`page * PAGE_SIZE` 在 int 域会溢出成负数
        //    （?page=107374183 起），负下标进 subList 就是 500 —— 而手改 URL 不该出 500。
        //    顺带这也是「页码越界回退到最后一页」：处置掉最后一页最后一条后按原页码重拉不会落到空页。
        int lastPage = all.isEmpty() ? 0 : (all.size() - 1) / PAGE_SIZE;
        int safePage = Math.min(Math.max(page, 0), lastPage);
        int from = Math.min(safePage * PAGE_SIZE, all.size());
        int to = Math.min(from + PAGE_SIZE, all.size());
        return new PinPage(all.subList(from, to), to < all.size(), safePage);
    }

    /** 摘要条：生效中 · 待生效 · 已结束（口径与列表状态列同源，见 {@link PinSummary}）。 */
    @Transactional(readOnly = true)
    public PinSummary summary(String slot, Instant now) {
        return summary(list(slot, now));
    }

    /** 同上，复用整表。 */
    public PinSummary summary(List<PinRow> all) {
        // ⚠️ 用 ended() 而不是 phase == ENDED：被手动提前结束的「待生效」排期 phase 仍是 PENDING（见 PinRow.ended）。
        return new PinSummary(
                all.stream().filter(r -> !r.ended() && r.phase() == SchedulePhase.ACTIVE).count(),
                all.stream().filter(r -> !r.ended() && r.phase() == SchedulePhase.PENDING).count(),
                all.stream().filter(PinRow::ended).count());
    }

    /** 抽屉：一条排期 + 坑位内容预览（AC3）；不存在 → 404。 */
    @Transactional(readOnly = true)
    public PinDetail detail(long id, Instant now) {
        ContentPin p = pins.byId(id).orElseThrow(() -> com.tailtopia.shared.error.AppException
                .notFound("顶置排期不存在").code("admin.err.pins.notFound"));
        ContentPost post = p.getContentId() == null ? null
                : posts.findById(p.getContentId()).orElse(null);
        boolean gone = p.getObjectType() != PinObjectType.PROMO
                && !ContentDisplayability.isDisplayable(post);
        String summary = p.getObjectType() == PinObjectType.PROMO ? p.getPromoTitle()
                : (post == null ? null : truncate(post.getText()));
        PinRow row = new PinRow(p.getId(), p.getSlot(), p.getObjectType().name(), p.getContentId(),
                summary, p.getStartsAt(), p.getEndsAt(), p.getTerminatedAt(), pins.phaseOf(p, now), gone);
        var author = post == null || post.getAuthorId() == null ? null
                : accountQuery.findAuthorViews(List.of(post.getAuthorId())).get(post.getAuthorId());
        return new PinDetail(row,
                post == null || post.getImageUrls() == null || post.getImageUrls().isEmpty()
                        ? null : post.getImageUrls().get(0),
                author == null ? null : author.nickname(), author != null && author.deleted(),
                p.getPromoImageUrl(), p.getPromoTitle(), p.getPromoLinkUrl());
    }

    /** 状态匹配：已提前结束的排期一律算「已结束」，与摘要条 / 徽标同口径。 */
    private static boolean matches(PinRow r, SchedulePhase want) {
        return want == SchedulePhase.ENDED ? r.ended() : (!r.ended() && r.phase() == want);
    }

    /** 状态筛选参数 → 阶段；空 / 非法 = 不筛。 */
    private static SchedulePhase phaseFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return SchedulePhase.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 某坑位的全部排期（含历史），已带 phase 与「内容失效」标记。 */
    @Transactional(readOnly = true)
    public List<PinRow> list(String slot, Instant now) {
        List<ContentPin> all = pins.listBySlot(slot);
        List<Long> contentIds = all.stream()
                .map(ContentPin::getContentId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, ContentPost> byId = contentIds.isEmpty()
                ? Map.of()
                : posts.findAllById(contentIds).stream()
                        .collect(Collectors.toMap(ContentPost::getId, Function.identity()));

        return all.stream().map(p -> {
            boolean gone = false;
            String summary;
            if (p.getObjectType() == PinObjectType.PROMO) {
                summary = p.getPromoTitle();
            } else {
                ContentPost post = p.getContentId() == null ? null : byId.get(p.getContentId());
                // 🛡 与 Feed 坑位同一判定：缺了这一致性，后台会说「生效中」而 App 上是空的。
                gone = !ContentDisplayability.isDisplayable(post);
                summary = post == null ? null : truncate(post.getText());
            }
            return new PinRow(p.getId(), p.getSlot(), p.getObjectType().name(), p.getContentId(),
                    summary, p.getStartsAt(), p.getEndsAt(), p.getTerminatedAt(),
                    pins.phaseOf(p, now), gone);
        }).toList();
    }

    /** 内容选择器：只返回可公开展示的内容，分页。 */
    @Transactional(readOnly = true)
    public List<PinnableContentRow> pickable(String keyword, int page) {
        // 🔴 绝不传 null：绑 null 时 Postgres 推不出类型（lower(bytea) does not exist），
        //    而"不带关键词"正是页面首次加载的那一次。无关键词 → "%" 匹配全部。
        String pattern = (keyword == null || keyword.isBlank())
                ? "%" : "%" + keyword.trim().toLowerCase() + "%";
        return posts.searchPinnable(pattern, PageRequest.of(Math.max(page, 0), PICK_PAGE_SIZE)).stream()
                .map(p -> new PinnableContentRow(p.getId(), p.getType().name(),
                        truncate(p.getText()), p.getCreatedAt()))
                .toList();
    }

    /** @return 新排期 id（Story 7.3：抽屉建完要立刻打开它） */
    @Transactional
    public long createContentPin(long adminId, String slot, long contentId,
            Instant startsAt, Instant endsAt) {
        ContentPin saved = pins.schedule(ContentPin.ofContent(slot, contentId, startsAt, endsAt));
        audit.record(adminId, "CONTENT_PIN_CREATE", "content_pin", String.valueOf(saved.getId()),
                "slot=" + slot + " contentId=" + contentId);
        return saved.getId();
    }

    /** @return 新排期 id（同上） */
    @Transactional
    public long createPromoPin(long adminId, String slot, String imageUrl, String title,
            String linkUrl, Instant startsAt, Instant endsAt) {
        ContentPin saved = pins.schedule(
                ContentPin.ofPromo(slot, imageUrl, title, linkUrl, startsAt, endsAt));
        audit.record(adminId, "CONTENT_PIN_CREATE", "content_pin", String.valueOf(saved.getId()),
                "slot=" + slot + " promo=" + title);
        return saved.getId();
    }

    @Transactional
    public void reschedule(long adminId, long id, Instant startsAt, Instant endsAt) {
        pins.update(id, startsAt, endsAt, null);
        audit.record(adminId, "CONTENT_PIN_EDIT", "content_pin", String.valueOf(id),
                "startsAt=" + startsAt + " endsAt=" + endsAt);
    }

    /** 手动提前结束。已结束的为幂等 no-op。 */
    @Transactional
    public boolean terminate(long adminId, long id, Instant at) {
        boolean changed = pins.terminateNow(id, at);
        if (changed) {
            audit.record(adminId, "CONTENT_PIN_TERMINATE", "content_pin", String.valueOf(id),
                    "at=" + at);
        }
        return changed;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return t.length() <= SUMMARY_MAX ? t : t.substring(0, SUMMARY_MAX) + "…";
    }
}
