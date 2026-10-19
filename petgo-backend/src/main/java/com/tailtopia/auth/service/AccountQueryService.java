package com.tailtopia.auth.service;

import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.dto.UserLifecycleSnapshot;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.growth.domain.GrowthBadge;
import com.tailtopia.growth.service.GrowthQueryService;
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

    /**
     * V1.4.0 ① Story 3.1：作者等级随投影一并批量取（AD-14）。🔴 {@code GrowthQueryService} 不得反向注入本类（bean 循环）。
     */
    private final GrowthQueryService growth;

    public AccountQueryService(UserRepository users, UserTagQueryService userTags, GrowthQueryService growth) {
        this.users = users;
        this.userTags = userTags;
        this.growth = growth;
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
     * <p>本方法服务<b>对外可见性判定</b>：H5 分享页（{@code CardPageController}、
     * {@code MilestoneSharePageController}）、主页宠物卡（{@code PublicProfilePetController}），
     * 以及 V1.3.4 follow Story 1.2 起的<b>公开主页与其内容区</b>（{@code PublicProfileController}：
     * PRD AC11 / 决策 D-8「封号用户各处按不存在处理」）和<b>关注调用方自检</b>（{@code FollowService}）。
     * 它<b>不是</b>通用的「能否登录」判断 —— 登录门禁在 {@code AuthService} 自己那套里，别把这个方法挪去当登录判据。
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

    /** 有效官方号（role=ADMIN、未注销、未封号）的内部 id（V1.3.4 following 流 · 自动关注）。 */
    @Transactional(readOnly = true)
    public List<Long> activeOfficialIds() {
        return users.findActiveOfficialIds();
    }

    /** 该账号是否「有效的真实普通用户」（role=USER、account_type=REAL、未注销、未封号；VIRTUAL 马甲不算）。 */
    @Transactional(readOnly = true)
    public boolean isActiveRegularUser(long userId) {
        return users.findById(userId)
                .map(u -> u.getRole() == Role.USER && u.getAccountType() == AccountType.REAL
                        && u.getDeletedAt() == null
                        && u.getStatus() == UserStatus.ACTIVE)
                .orElse(false);
    }

    /**
     * {@link #isActive} 的**批量**版：这批 id 里哪些账号对外有效（未注销 + 未封号）。
     *
     * <h2>🔴 判据与 {@link #isActive} 逐字相同，不许各写一遍</h2>
     * V1.3.0 batch-b1 Story 4.1 的推荐池要判一页十几个 owner，逐个 {@code isActive} 就是
     * 十几次往返（AD-6）。而只判「注销」不判「封号」的表现是
     * <b>「推荐位里那只宠物点进去 404」</b>—— 落地页的可见性判据用的正是 {@code isActive}
     * （code-review 2026-09-15 抓到过一次）。
     *
     * <p>⚠️ 同 {@code isActive}：这是**对外可见性**判据，不是「能否登录」判据。
     *
     * @return 有效账号的 id 集合（入参里缺失的 id 不出现）
     */
    @Transactional(readOnly = true)
    public java.util.Set<Long> activeIdsAmong(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(users.findActiveIds(userIds));
    }

    /**
     * 一批用户的对外 token（V1.3.4 follow · T-19：Story 2.1 / 2.2 共用的批量取法）—— <b>一次查询</b>。
     *
     * <p>只返回<b>对外有效</b>账号（未注销 + 未封号，判据与 {@link #isActive} 逐字相同）：
     * 已注销 / 封号的人缺席 → 调用方拿到 null → 不显示关注按钮（D-8「各处按不存在处理」）。
     * ⚠️ <b>不按 role 过滤</b>（D-9：官方内容账号可被关注）。
     *
     * @return {@code userId → publicToken}；空入参返回空表、不发查询
     */
    @Transactional(readOnly = true)
    public Map<Long, String> publicTokensOf(Collection<Long> userIds) {
        return publicTokensOf(userIds, false);
    }

    /**
     * 同 {@link #publicTokensOf(Collection)}；{@code includeDeactivated = true} 时<b>封号账号也给 token</b>
     * （已注销仍不给）—— 关注 / 粉丝列表专用（Story 2.2 · D-8：封号用户留在列表里、可被取关）。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> publicTokensOf(Collection<Long> userIds, boolean includeDeactivated) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = userIds.stream().distinct().toList();
        Map<Long, String> out = new java.util.HashMap<>();
        for (Object[] row : includeDeactivated ? users.findUndeletedPublicTokens(ids)
                : users.findActivePublicTokens(ids)) {
            out.put((Long) row[0], (String) row[1]);
        }
        return out;
    }

    /** 取用户语言偏好（bug 20260625-105）：'en' 或 'id'（默认/未设=id）。供系统推送文案本地化。 */
    @Transactional(readOnly = true)
    public java.util.Locale localeOf(long userId) {
        return users.findById(userId)
                .map(u -> "en".equalsIgnoreCase(u.getLocale()) ? java.util.Locale.ENGLISH : INDONESIAN)
                .orElse(INDONESIAN);
    }

    private static final java.util.Locale INDONESIAN = java.util.Locale.forLanguageTag("id");

    /**
     * 是否虚拟号（{@code account_type = VIRTUAL}，运营马甲/种子号）。虚拟号从不登录 App、没有 IM 账号，
     * 给它发离线推送腾讯回 90001（To_Account are invalid）——推送入口据此跳过（bug 519/521 附带清理）。
     * 不存在的用户按 false 处理（保持原推送行为）。
     */
    @Transactional(readOnly = true)
    public boolean isVirtual(long userId) {
        return users.findById(userId)
                .map(u -> u.getAccountType() == AccountType.VIRTUAL)
                .orElse(false);
    }

    /**
     * 是否官方内容账号（{@code role = ADMIN} 的作者 shim，后台发帖的 {@code author_id} 即它）。
     * V1.3.4 follow · D-9：官方号可被关注，但<b>不接收通知推送</b>（它不登录 App）。不存在的用户按 false。
     */
    @Transactional(readOnly = true)
    public boolean isOfficialAccount(long userId) {
        return users.findById(userId)
                .map(u -> u.getRole() == Role.ADMIN)
                .orElse(false);
    }

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
        return attachTags(basicViews(userIds));
    }

    /**
     * 同 {@link #findAuthorViews}，但<b>不查运营标签</b>（V1.3.0 batch-b1 Story 3.1）。
     *
     * <h2>⚠️ 只给"确定不展示标签"的调用方</h2>
     * 目前唯一的调用方是 <b>@ 候选集</b>：那是一个打字时弹出的选择列表，一行只有头像 + 昵称，
     * 一次要取 50 个人 —— 为它多查一次 {@code user_tag_assignments} 纯属白跑
     * （查完即丢，code-review 2026-09-15）。
     *
     * <p>🔴 <b>要展示标签就必须用 {@link #findAuthorViews}</b>：本方法回的投影里
     * {@code tags} 恒为空表，误用的表现是「标签在某一处悄悄消失」，而<b>不会有任何报错</b>。
     * 加新调用方之前先问一句：那个位置要不要显示标签？
     */
    @Transactional(readOnly = true)
    public Map<Long, AuthorView> findAuthorViewsWithoutTags(Collection<Long> userIds) {
        return basicViews(userIds);
    }

    /**
     * 通知触发人投影（V1.3.4 follow · Story 3.3）—— <b>一次</b> {@code findAllById} 同时取昵称、{@code public_token}、
     * 是否对外有效（不为 token 再多查一次）。通知中心不展示运营标签，故不查标签。缺失的 id 不出现。
     */
    @Transactional(readOnly = true)
    public Map<Long, com.tailtopia.auth.dto.ActorView> findActorViews(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, u -> {
            boolean active = u.getDeletedAt() == null && u.getStatus() == UserStatus.ACTIVE;
            String name = u.getNickname() != null ? u.getNickname() : u.getDisplayName();
            return active ? new com.tailtopia.auth.dto.ActorView(u.getId(), name, u.getPublicToken(), true)
                    : new com.tailtopia.auth.dto.ActorView(u.getId(), null, null, false);
        }));
    }

    /**
     * 身份三件套（id / 昵称 / 头像 + 是否注销）+ 作者等级。缺失的 id 按匿名化补齐，调用方按 id 取必有值。
     *
     * <p>V1.4.0 ① Story 3.1：对<b>未注销</b>的作者整批调一次 {@code getBadges}（常数次查询，AD-14）；
     * 停用 / 不展示等级的账号 {@code getBadges} 不返回 → {@code growth = null}。全部已注销时不查。
     */
    private Map<Long, AuthorView> basicViews(Collection<Long> userIds) {
        Map<Long, AuthorView> found = users.findAllById(userIds).stream()
                .map(AccountQueryService::toAuthorView)
                // 显式 HashMap：下面要 computeIfPresent 贴等级（Collectors.toMap 不保证可变）。
                .collect(Collectors.toMap(AuthorView::userId, Function.identity(), (a, b) -> a,
                        java.util.HashMap::new));
        List<Long> visible = found.values().stream().filter(v -> !v.deleted()).map(AuthorView::userId).toList();
        if (!visible.isEmpty()) {
            Map<Long, GrowthBadge> badges = growth.getBadges(visible);
            badges.forEach((userId, badge) -> found.computeIfPresent(userId, (k, v) -> v.withGrowth(badge)));
        }
        return userIds.stream().distinct()
                .collect(Collectors.toMap(Function.identity(),
                        id -> found.getOrDefault(id, AuthorView.anonymized(id))));
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

    /**
     * 按对外 token 取一个<b>可被关注</b>的账号（V1.3.4 follow Story 1.1，D-2 / D-8 / D-9）：
     * 未注销 + {@code status = ACTIVE}（判据与 {@link #isActive} 一致）。
     *
     * <p>⚠️ <b>不按 role 过滤</b>（决策 D-9：官方内容账号 ADMIN 作者 shim 可被关注）；
     * 「只有 USER 能调关注接口」约束的是<b>调用方</b>（SecurityConfig），与被关注方无关。
     * ⚠️ 不看 {@code enabled}（T-2：被运营停用的虚拟号照常可被关注）。
     *
     * <p>token 为空 / 长度不是 32 → 直接 empty，不查库。
     */
    @Transactional(readOnly = true)
    public Optional<User> findActiveByPublicToken(String token) {
        return findByPublicToken(token)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE);
    }

    /**
     * 按对外 token 取一个<b>未注销</b>账号，<b>不看封号</b>（V1.3.4 follow Story 1.1 AC4.3 / D-8）。
     *
     * <p>专供<b>取关</b>：封号用户仍留在我的关注列表里（D-8），若取关也要求对方 ACTIVE，
     * 用户就永远取关不掉一个被封号的人。与 {@link #findActiveByPublicToken} 同样不按 role 过滤（D-9）。
     */
    @Transactional(readOnly = true)
    public Optional<User> findByPublicToken(String token) {
        if (token == null || token.length() != UserTokenGenerator.LENGTH) {
            return Optional.empty();
        }
        return users.findByPublicToken(token)
                .filter(u -> u.getDeletedAt() == null);
    }

    /**
     * 按 id 取账号，<b>不按 role 过滤</b>（V1.3.4 follow Story 1.2）。
     *
     * <p>专供公开主页取 {@code joinedAt} / {@code userToken} / 两关注计数：决策 D-9 官方内容账号
     * （ADMIN 作者 shim）可被关注，主页必须给它 token；{@link #findUserById} 只认 USER，会让官方号主页
     * 拿不到 token、关注按钮点了也无从调用。可见性判定（注销 / 封号）由调用方先行完成。
     */
    @Transactional(readOnly = true)
    public Optional<User> findAccountById(long userId) {
        return users.findById(userId);
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

    /**
     * 后台搜索（2026-09-02）：按展示昵称或注册邮箱模糊匹配普通用户（role=USER，未注销），
     * 近注册在前，至多 {@code limit} 条。口径见 {@code UserRepository#searchByDisplayedNameOrEmail}。
     */
    @Transactional(readOnly = true)
    public List<User> searchUsersByDisplayedName(String keyword, int limit) {
        String pattern = "%" + keyword.trim().toLowerCase() + "%";
        return users.searchByDisplayedNameOrEmail(Role.USER, pattern,
                org.springframework.data.domain.PageRequest.of(0, limit));
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
     *
     * @return 是否为该用户当天（WIB）首次活跃（{@code user_active_days} 本次新写入一行）。
     *         V1.4.0 ① Story 1.1：为 true 时调用方发 {@code UserDailyActiveEvent}（每日登录成长值）。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean touchLastActive(long userId, Instant now) {
        users.touchLastActiveAt(userId, now, now.atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant());
        // 日报「昨日日活」：按印尼自然日逐日记录（last_active_at 只存最后一次，算不了历史某天的日活）。
        return users.recordActiveDay(userId, now.atZone(WIB).toLocalDate()) == 1;
    }

    /** 日报切日时区（运营在雅加达）。 */
    private static final java.time.ZoneId WIB = com.tailtopia.shared.schedule.ScheduleWindow.WIB;

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
