package com.tailtopia.auth.service;

import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.dto.UserLifecycleSnapshot;
import com.tailtopia.auth.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号状态查询（跨模块 service 接口）。供 Story 2.6 名片失效判定、Story 3.2 Feed 作者投影经接口取数据，
 * **不让 profile/content 直接 join users 表**（架构 Architectural Boundaries）。
 */
@Service
public class AccountQueryService {

    private final UserRepository users;

    /** V1.1.6 Story 5.1：运营标签随作者投影一并批量取（AD-11）。 */
    private final UserTagQueryService userTags;

    public AccountQueryService(UserRepository users, UserTagQueryService userTags) {
        this.users = users;
        this.userTags = userTags;
    }

    /**
     * 账号对外是否有效 —— <b>既未注销、也未被封号</b>。
     *
     * <p>「注销」与「封号」是<b>两个正交维度</b>：注销是用户自己删号（{@code deleted_at}，不可逆）、
     * 封号是运营停用（{@code status=DEACTIVATED}，可逆，V1.1.4 Story 3.2）。
     *
     * <p>⚠️ <b>2026-08-17 产品拍板：两者的 H5 分享页都不可见。</b>
     * 此前本方法<b>只看注销</b>，于是被封号的用户其宠物分享页照样对全网可见 ——
     * 头像、名字、照片、里程碑全在。封号本就是因为违规，让他的对外页继续挂着不合理。
     *
     * <p>本方法<b>只服务 H5 对外分享页的可见性判定</b>（{@code CardPageController} 与
     * {@code MilestoneSharePageController} 是唯二调用方），<b>不是</b>通用的「能否登录」判断
     * —— 登录门禁在 {@code AuthService} 自己那套里，别把这个方法挪去当登录判据。
     *
     * <p>⚠️ 封号可逆，所以本判定也必须可逆：重新激活后分享页要恢复可见
     * （{@code reactivatedAccountBecomesVisibleAgain} 钉着这条）。
     */
    @Transactional(readOnly = true)
    public boolean isActive(long userId) {
        return users.findById(userId)
                .map(u -> u.getDeletedAt() == null && u.getStatus() == UserStatus.ACTIVE)
                .orElse(false);
    }

    /** 取用户语言偏好（bug 20260625-105）：'en' 或 'id'（默认/未设=id）。供系统推送文案本地化。 */
    @Transactional(readOnly = true)
    public java.util.Locale localeOf(long userId) {
        return users.findById(userId)
                .map(u -> "en".equalsIgnoreCase(u.getLocale()) ? java.util.Locale.ENGLISH : INDONESIAN)
                .orElse(INDONESIAN);
    }

    private static final java.util.Locale INDONESIAN = java.util.Locale.forLanguageTag("id");

    /** Story 3.2：取用户宠物状态（A/B/C），供 Feed 硬过滤；不存在/未设返回 empty。 */
    @Transactional(readOnly = true)
    public Optional<String> petStatusOf(long userId) {
        return users.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .map(u -> u.getPetStatus() == null ? null : u.getPetStatus().name());
    }

    /**
     * Story 3.2：批量取作者展示投影（Feed 卡片用），注销账号匿名化（NFR-8）。
     * 缺失/注销作者一律返回 {@link AuthorView#anonymized}，不泄漏曾否存在。
     *
     * <h2>🔴 V1.1.6 Story 5.1：运营标签也在这里一并取</h2>
     * 四处展示位（首页卡 / 详情页作者区 / 评论区 / 迷你主页预览卡）**早就都在调本方法**，
     * 所以标签接在这里 → 四处**天生批量**，而且**没有哪一处能绕过去逐条查**（AD-11）。
     * 这比"四处各写一遍取标签、再各自记得写成批量"稳得多。
     *
     * <p>⚠️ 与下方 {@code activeSignatureOf} 的"刻意不塞进作者投影"**不矛盾**：
     * 签名只在点头像弹卡时才用得上，而标签**四处都要、每行都要**；
     * 且取数是**整页一次**，不是每行一次。
     */
    @Transactional(readOnly = true)
    public Map<Long, AuthorView> findAuthorViews(Collection<Long> userIds) {
        Map<Long, AuthorView> found = users.findAllById(userIds).stream()
                .map(AccountQueryService::toAuthorView)
                .collect(Collectors.toMap(AuthorView::userId, Function.identity()));
        // 缺失的（不存在）也按匿名化补齐，调用方按 id 取必有值。
        Map<Long, AuthorView> views = userIds.stream().distinct()
                .collect(Collectors.toMap(Function.identity(),
                        id -> found.getOrDefault(id, AuthorView.anonymized(id))));
        return attachTags(views);
    }

    /**
     * 给一批作者投影贴上运营标签（整批一次查询）。
     *
     * <p>🛡 **注销作者不查也不贴** —— 匿名化之后不该再挂着身份标识（AC6）。
     * 顺带：一页全是注销作者时连查询都不发。
     */
    private Map<Long, AuthorView> attachTags(Map<Long, AuthorView> views) {
        List<Long> visible = views.values().stream()
                .filter(v -> !v.deleted())
                .map(AuthorView::userId)
                .toList();
        if (visible.isEmpty()) {
            return views;
        }
        Map<Long, List<com.tailtopia.auth.dto.UserTagView>> tags =
                userTags.findVisibleTags(visible, Instant.now());
        if (tags.isEmpty()) {
            return views;
        }
        Map<Long, AuthorView> out = new java.util.HashMap<>(views);
        tags.forEach((userId, list) -> out.computeIfPresent(userId, (k, v) -> v.withTags(list)));
        return out;
    }

    /** Story 3.1：按 id 取普通用户（role=USER），供后台用户详情只读聚合。 */
    @Transactional(readOnly = true)
    public Optional<User> findUserById(long userId) {
        return users.findById(userId).filter(u -> u.getRole() == Role.USER);
    }

    /** Story 3.1：按注册邮箱精确取普通用户（role=USER），供后台搜索。 */
    @Transactional(readOnly = true)
    public Optional<User> findUserByEmail(String email) {
        return users.findByEmailAndRole(email, Role.USER);
    }

    /** bug 20260701-164：后台用户管理分页列出全部普通用户（role=USER），供列表浏览。 */
    @Transactional(readOnly = true)
    public Page<User> listUsers(Pageable pageable) {
        return users.findByRole(Role.USER, pageable);
    }

    /**
     * 迷你主页专用：取用户个性签名（未设置 / 已注销 / 不存在 → empty）。
     *
     * <p>⚠️ **刻意不塞进 {@link AuthorView}** —— 那是 Feed 每一行都要带的作者投影，
     * 为了一个只在「点头像弹卡」时才用得上的字段，让整个内容流的响应都变胖不划算。
     * 注销过滤在此就地做（与 {@link #toAuthorView} 同口径），避免调用方漏判 NFR-8 匿名化。
     */
    @Transactional(readOnly = true)
    public Optional<String> activeSignatureOf(long userId) {
        return users.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .map(User::getSignature);
    }

    /**
     * 生命周期推送日扫快照（留存手册抓手 1）。只读端口 —— notify 模块<b>不直访</b> users 表。
     *
     * <p>日期一律折 UTC：定时日扫、{@code created_at}、{@code last_active_at} 三者必须同一基准，
     * 否则「注册满 1 天」会在时区边界上抖动，同一个人可能连着两天各收一条 D1。
     */
    @Transactional(readOnly = true)
    public List<UserLifecycleSnapshot> lifecycleSnapshots() {
        return users.findLifecyclePushCandidates(Role.USER, AccountType.REAL, UserStatus.ACTIVE)
                .stream()
                .map(u -> new UserLifecycleSnapshot(
                        u.getId(),
                        toUtcDate(u.getCreatedAt()),
                        toUtcDate(u.getLastActiveAt()),
                        u.getPublishedCount()))
                .toList();
    }

    /**
     * 刷新「最后活跃」（留存手册抓手 1）。每日至多落一次写（条件 UPDATE 幂等，见 repository 注释）。
     *
     * <p>独立事务：调用方是请求链路上的 filter，活跃刷新失败<b>绝不可</b>波及业务事务
     * —— 记不上「他今天来过」最多让召回推送晚一轮，让用户的请求 500 则是真事故。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void touchLastActive(long userId, Instant now) {
        users.touchLastActiveAt(userId, now, now.atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static LocalDate toUtcDate(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static AuthorView toAuthorView(User u) {
        if (u.getDeletedAt() != null) {
            return AuthorView.anonymized(u.getId());
        }
        String name = u.getNickname() != null ? u.getNickname() : u.getDisplayName();
        // 标签由 attachTags 整批贴上（这里先给空表，避免每行各查一次）。
        return new AuthorView(u.getId(), name, u.getAvatarUrl(), false, List.of());
    }
}
