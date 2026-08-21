package com.tailtopia.admin.pin.service;

import com.tailtopia.admin.pin.dto.PinRow;
import com.tailtopia.admin.pin.dto.PinnableContentRow;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.PinObjectType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentDisplayability;
import com.tailtopia.content.service.ContentPinService;
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

    private final ContentPinService pins;
    private final ContentPostRepository posts;
    private final AdminAuditService audit;

    public AdminContentPinService(ContentPinService pins, ContentPostRepository posts,
            AdminAuditService audit) {
        this.pins = pins;
        this.posts = posts;
        this.audit = audit;
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
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        return posts.searchPinnable(kw, PageRequest.of(Math.max(page, 0), PICK_PAGE_SIZE)).stream()
                .map(p -> new PinnableContentRow(p.getId(), p.getType().name(),
                        truncate(p.getText()), p.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void createContentPin(long adminId, String slot, long contentId,
            Instant startsAt, Instant endsAt) {
        ContentPin saved = pins.schedule(ContentPin.ofContent(slot, contentId, startsAt, endsAt));
        audit.record(adminId, "CONTENT_PIN_CREATE", "content_pin", String.valueOf(saved.getId()),
                "slot=" + slot + " contentId=" + contentId);
    }

    @Transactional
    public void createPromoPin(long adminId, String slot, String imageUrl, String title,
            String linkUrl, Instant startsAt, Instant endsAt) {
        ContentPin saved = pins.schedule(
                ContentPin.ofPromo(slot, imageUrl, title, linkUrl, startsAt, endsAt));
        audit.record(adminId, "CONTENT_PIN_CREATE", "content_pin", String.valueOf(saved.getId()),
                "slot=" + slot + " promo=" + title);
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
