package com.tailtopia.admin.topic.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.topic.service.AdminTopicStore.FeaturedSlot;
import com.tailtopia.admin.topic.service.AdminTopicStore.TopicPage;
import com.tailtopia.admin.topic.service.AdminTopicStore.TopicSummary;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.service.ContentPinService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.topic.domain.TopicNameRules;
import com.tailtopia.topic.domain.TopicStatus;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台话题管理（V1.4.2 social-topic · Story 6.1 · AB-10B · D-2 / D-6 · R-5）。
 *
 * <h2>规则要点</h2>
 * <ul>
 *   <li>话题名校验只走 {@link TopicNameRules}（P-1 全仓唯一实现），本类不写正则；</li>
 *   <li>新建与 App 发帖建用户话题并发：{@code INSERT … ON CONFLICT DO NOTHING} 后重查判定，不 500、不出两条；</li>
 *   <li>R-5：重名用户话题 HIDDEN → 照常升级并<b>保持 HIDDEN</b>；DISABLED → 拒绝升级（先恢复）；</li>
 *   <li>升级后不可降回用户话题（本类没有、也不许加任何降级方法）；</li>
 *   <li>首页话题行（{@code featured_order}）：每个改动它的方法先拿 {@code pg_advisory_xact_lock}；
 *       前移逐行升序、互换三步（A→NULL、B→a、A→b），因为部分唯一索引是立即检查且 CHECK 不允许负数过渡；</li>
 *   <li>每个写操作恰好审计一次；summary 只记标准名、状态变化、序号，<b>不记说明全文</b>。</li>
 * </ul>
 */
@Service
public class AdminTopicService {

    /** 说明列宽（{@code desc_id} / {@code desc_en VARCHAR(500)}）。 */
    public static final int DESC_MAX = 500;

    static final String TARGET = "topic";

    private final AdminTopicStore store;
    private final ContentPinService pins;
    private final AdminAuditService audit;
    private final Clock clock;

    @Autowired
    public AdminTopicService(AdminTopicStore store, ContentPinService pins, AdminAuditService audit) {
        this(store, pins, audit, Clock.systemUTC());
    }

    AdminTopicService(AdminTopicStore store, ContentPinService pins, AdminAuditService audit, Clock clock) {
        this.store = store;
        this.pins = pins;
        this.audit = audit;
        this.clock = clock;
    }

    // ================================================================ 读

    @Transactional(readOnly = true)
    public TopicPage page(TopicFilter filter, int page) {
        return store.page(filter, page);
    }

    @Transactional(readOnly = true)
    public TopicSummary summary() {
        return store.summary();
    }

    /** 新行定位（AC3.3）：该话题在当前筛选下所在页；不在筛选结果里 → 0。 */
    @Transactional(readOnly = true)
    public int pageOf(long id, TopicFilter filter) {
        return store.pageOf(id, filter).orElse(0);
    }

    @Transactional(readOnly = true)
    public TopicView get(long id) {
        return store.findById(id).orElseThrow(AdminTopicService::notFound);
    }

    /** 实时帖子数（AC7.2；抽屉头部 / 升级黄条 / 确认句用）。 */
    @Transactional(readOnly = true)
    public int livePostCount(long id) {
        return store.livePostCount(id);
    }

    /**
     * 新建表单输入即查（AC3.4，300ms 防抖由前端做）。空输入 → {@code null}（什么都不显示）。
     */
    @Transactional(readOnly = true)
    public NameCheck checkName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            return null;
        }
        if (!TopicNameRules.isValidName(name)) {
            return NameCheck.invalid();
        }
        return store.findByNorm(TopicNameRules.normalize(name))
                .map(t -> classify(t))
                .orElseGet(NameCheck::fresh);
    }

    private NameCheck classify(TopicView t) {
        if (t.official()) {
            return new NameCheck(NameCheck.Kind.OFFICIAL_EXISTS, t.id(), t.nameDisplay(), t.status(), 0);
        }
        if (t.disabled()) {
            return new NameCheck(NameCheck.Kind.DISABLED_CONFLICT, t.id(), t.nameDisplay(), t.status(), 0);
        }
        return new NameCheck(NameCheck.Kind.UPGRADE, t.id(), t.nameDisplay(), t.status(), store.livePostCount(t.id()));
    }

    // ================================================================ 新建 / 升级（AC3）

    /**
     * 新建官方话题；同名用户话题 → 升级（需 {@code upgradeConfirmed}，服务端权威，AC3.8）。
     */
    @Transactional
    public CreateResult create(Long adminId, String rawName, String rawDescId, String rawDescEn,
            boolean upgradeConfirmed) {
        String name = rawName == null ? "" : rawName.strip();
        if (!TopicNameRules.isValidName(name)) {
            throw AppException.validation("话题名不符合规则").code("admin.err.topics.nameInvalid");
        }
        String display = Normalizer.normalize(name, Normalizer.Form.NFC);
        String norm = TopicNameRules.normalize(name);
        String descId = desc(rawDescId);
        String descEn = desc(rawDescEn);

        Optional<TopicView> existing = store.findByNorm(norm);
        if (existing.isEmpty()) {
            if (store.insertOfficial(display, norm, descId, descEn)) {
                TopicView created = store.findByNorm(norm)
                        .orElseThrow(() -> new IllegalStateException("topic row missing after insert"));
                audit.record(adminId, AuditActions.TOPIC_CREATED, TARGET, String.valueOf(created.id()),
                        "name=" + norm + "; type=OFFICIAL; status=ACTIVE");
                return new CreateResult(created.id(), created.nameDisplay(), false, 0);
            }
            // 并发：期间被 App 发帖（USER）或另一位运营（OFFICIAL）抢先建出 —— 重查后按现状判定。
            existing = store.findByNorm(norm);
            if (existing.isEmpty()) {
                throw new IllegalStateException("topic row missing after upsert");
            }
        }
        return upgrade(adminId, existing.get(), display, descId, descEn, upgradeConfirmed);
    }

    private CreateResult upgrade(Long adminId, TopicView t, String display, String descId, String descEn,
            boolean confirmed) {
        rejectIfNotUpgradable(t);
        int posts = store.livePostCount(t.id());
        if (!confirmed) {
            // 输入时没看到黄条（期间刚被 App 用户建出）→ 回显黄条同句，运营再点一次保存走 confirm。
            throw AppException.validation("已有同名用户话题，需确认升级")
                    .code("admin.topics.upgradeWarn", t.nameDisplay(), posts);
        }
        if (!store.upgrade(t.id(), display, descId, descEn, now())) {
            // 期间被禁用 / 被别人升级：按最新状态给出对应提示，不改任何字段。
            rejectIfNotUpgradable(store.findById(t.id()).orElseThrow(AdminTopicService::notFound));
            throw stale();
        }
        audit.record(adminId, AuditActions.TOPIC_UPGRADED, TARGET, String.valueOf(t.id()),
                "name=" + t.nameNorm() + "; type: USER -> OFFICIAL; status=" + t.status() + "; posts=" + posts);
        return new CreateResult(t.id(), display, true, posts);
    }

    private static void rejectIfNotUpgradable(TopicView t) {
        if (t.official()) {
            throw AppException.validation("已有同名官方话题").code("admin.err.topics.officialExists", t.nameDisplay());
        }
        if (t.disabled()) {
            // R-5：禁用的用户话题拒绝升级，提示先恢复。
            throw AppException.validation("同名用户话题已被禁用").code("admin.err.topics.upgradeDisabled",
                    t.nameDisplay());
        }
    }

    // ================================================================ 编辑（AC4）

    /**
     * 编辑：显示写法只能改大小写；说明只对官方话题生效（用户话题忽略任何说明参数，AC4.4）。
     *
     * @return true = 有改动并已保存；false = 与现状一致（不写库、不审计）
     */
    @Transactional
    public boolean update(Long adminId, long id, String rawDisplay, String rawDescId, String rawDescEn) {
        TopicView t = get(id);
        String candidate = rawDisplay == null ? "" : rawDisplay.strip();
        if (!TopicNameRules.isValidName(candidate) || !TopicNameRules.normalize(candidate).equals(t.nameNorm())) {
            throw AppException.validation("只能修改字母大小写").code("admin.err.topics.caseOnly");
        }
        String display = Normalizer.normalize(candidate, Normalizer.Form.NFC);
        String descId = t.official() ? desc(rawDescId) : t.descId();
        String descEn = t.official() ? desc(rawDescEn) : t.descEn();
        boolean displayChanged = !display.equals(t.nameDisplay());
        boolean descChanged = !Objects.equals(descId, t.descId()) || !Objects.equals(descEn, t.descEn());
        if (!displayChanged && !descChanged) {
            return false;
        }
        store.updateContent(id, display, descId, descEn, now());
        StringBuilder summary = new StringBuilder("name=").append(t.nameNorm());
        if (displayChanged) {
            summary.append("; display: ").append(t.nameDisplay()).append(" -> ").append(display);
        }
        if (descChanged) {
            summary.append("; description changed");
        }
        audit.record(adminId, AuditActions.TOPIC_UPDATED, TARGET, String.valueOf(id), summary.toString());
        return true;
    }

    // ================================================================ 隐藏 / 禁用 / 恢复（AC6）

    /** 隐藏（直接生效）。@return 原本是否在首页话题行（toast 追加「已移出」一段） */
    @Transactional
    public boolean hide(Long adminId, long id) {
        store.lockFeatured();
        TopicView t = get(id);
        if (!t.active()) {
            throw stale();
        }
        Integer pos = t.featuredOrder();
        if (pos != null) {
            removeFromRow(id, pos);
        }
        if (store.updateStatus(id, TopicStatus.ACTIVE, TopicStatus.HIDDEN, null, now()) == 0) {
            throw stale();
        }
        audit.record(adminId, AuditActions.TOPIC_HIDDEN, TARGET, String.valueOf(id),
                "name=" + t.nameNorm() + "; status: ACTIVE -> HIDDEN" + unfeaturedNote(pos));
        return pos != null;
    }

    /** 禁用（前端已 confirm）：同一事务内移出首页话题行 + 结束该话题页坑位全部未结束排期（D-6）。 */
    @Transactional
    public void disable(Long adminId, long id) {
        store.lockFeatured();
        TopicView t = get(id);
        if (t.disabled()) {
            throw stale();
        }
        Instant now = now();
        Integer pos = t.featuredOrder();
        if (pos != null) {
            removeFromRow(id, pos);
        }
        if (store.updateStatus(id, t.status(), TopicStatus.DISABLED, now, now) == 0) {
            throw stale();
        }
        int ended = pins.terminateForSlot(ContentPin.topicSlot(id), now);
        audit.record(adminId, AuditActions.TOPIC_DISABLED, TARGET, String.valueOf(id),
                "name=" + t.nameNorm() + "; status: " + t.status() + " -> DISABLED" + unfeaturedNote(pos)
                        + "; pinsEnded=" + ended);
    }

    /** 恢复为正常：清 {@code disabled_at}；首页话题行位置与禁用时结束的顶置<b>都不恢复</b>。 */
    @Transactional
    public void restore(Long adminId, long id) {
        TopicView t = get(id);
        if (t.active()) {
            throw stale();
        }
        if (store.updateStatus(id, t.status(), TopicStatus.ACTIVE, null, now()) == 0) {
            throw stale(); // 并发恢复 / 双击：只有一次生效、只审计一次
        }
        audit.record(adminId, AuditActions.TOPIC_RESTORED, TARGET, String.valueOf(id),
                "name=" + t.nameNorm() + "; status: " + t.status() + " -> ACTIVE");
    }

    // ================================================================ 首页话题行（AC5）

    /** 放入（排到最后）。已在行中 → 幂等返回当前位（不审计）。@return 所在序号 */
    @Transactional
    public int feature(Long adminId, long id) {
        store.lockFeatured();
        TopicView t = get(id);
        if (!t.active()) {
            throw AppException.validation("隐藏或禁用的话题不能放入首页话题行")
                    .code("admin.err.topics.featureNotAllowed");
        }
        if (t.featuredOrder() != null) {
            return t.featuredOrder();
        }
        int count = store.featuredCount();
        if (count >= AdminTopicStore.FEATURED_MAX) {
            throw AppException.validation("首页话题行已满").code("admin.err.topics.featureLimit");
        }
        int pos = count + 1;
        store.setFeaturedOrder(id, pos);
        audit.record(adminId, AuditActions.TOPIC_FEATURED, TARGET, String.valueOf(id),
                "name=" + t.nameNorm() + "; order=" + pos);
        return pos;
    }

    /** 移出（后面的依次前移）。不在行中 → 幂等（不审计）。 */
    @Transactional
    public void unfeature(Long adminId, long id) {
        store.lockFeatured();
        TopicView t = get(id);
        Integer pos = t.featuredOrder();
        if (pos == null) {
            return;
        }
        removeFromRow(id, pos);
        audit.record(adminId, AuditActions.TOPIC_UNFEATURED, TARGET, String.valueOf(id),
                "name=" + t.nameNorm() + "; order=" + pos);
    }

    /** 改序号：与原占该位的话题互换；只接受 1..当前个数。 */
    @Transactional
    public void reorder(Long adminId, long id, int order) {
        store.lockFeatured();
        TopicView t = get(id);
        int count = store.featuredCount();
        Integer pos = t.featuredOrder();
        if (pos == null || order < 1 || order > count) {
            throw AppException.validation("序号超出范围").code("admin.err.topics.featureOrderRange", count);
        }
        if (order == pos) {
            return;
        }
        store.lockFeaturedRows();
        Optional<Long> other = store.idAtFeaturedOrder(order);
        // 互换三步：A→NULL、B→a、A→b（部分唯一索引立即检查，单条 UPDATE 互换会撞）。
        store.setFeaturedOrder(id, null);
        other.ifPresent(o -> store.setFeaturedOrder(o, pos));
        store.setFeaturedOrder(id, order);
        audit.record(adminId, AuditActions.TOPIC_FEATURE_REORDERED, TARGET, String.valueOf(id),
                "name=" + t.nameNorm() + "; order: " + pos + " -> " + order
                        + other.map(o -> "; swappedWith=" + o).orElse(""));
    }

    /** 移出 {@code pos}：先按 id 锁住行内全部行，再置 NULL，再把后面的<b>按升序逐行</b> -1。调用方已持 advisory 锁。 */
    private void removeFromRow(long id, int pos) {
        store.lockFeaturedRows();
        store.setFeaturedOrder(id, null);
        for (FeaturedSlot s : store.featuredAfter(pos)) {
            store.setFeaturedOrder(s.id(), s.order() - 1);
        }
    }

    // ================================================================ 内部

    private static String unfeaturedNote(Integer pos) {
        return pos == null ? "" : "; unfeatured order=" + pos;
    }

    /** 说明：空白 → null；超长 → 422。 */
    private static String desc(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        // 按码点数（与 PG VARCHAR(500) 的字符数口径一致）；emoji 等补充平面字符算 1 个。
        if (s.codePointCount(0, s.length()) > DESC_MAX) {
            throw AppException.validation("说明过长").code("admin.err.topics.descTooLong", DESC_MAX);
        }
        return s;
    }

    private Instant now() {
        return clock.instant();
    }

    private static AppException notFound() {
        return AppException.notFound("话题不存在").code("admin.err.topics.notFound");
    }

    private static AppException stale() {
        return AppException.validation("话题状态已变化").code("admin.err.topics.stale");
    }

    /** 新建结果：{@code upgraded} = 由同名用户话题升级而来（toast 用 upgraded 文案与帖子数）。 */
    public record CreateResult(long id, String nameDisplay, boolean upgraded, int postCount) {
    }

    /**
     * 名称查重结果（{@code checkName}）。
     *
     * @param livePostCount 只对 {@link Kind#UPGRADE} 有意义（黄条 / 确认句的实时帖子数）
     */
    public record NameCheck(Kind kind, Long topicId, String nameDisplay, TopicStatus status, int livePostCount) {

        public enum Kind { INVALID, NEW, UPGRADE, OFFICIAL_EXISTS, DISABLED_CONFLICT }

        static NameCheck invalid() {
            return new NameCheck(Kind.INVALID, null, null, null, 0);
        }

        static NameCheck fresh() {
            return new NameCheck(Kind.NEW, null, null, null, 0);
        }

        public boolean upgrade() {
            return kind == Kind.UPGRADE;
        }
    }
}
