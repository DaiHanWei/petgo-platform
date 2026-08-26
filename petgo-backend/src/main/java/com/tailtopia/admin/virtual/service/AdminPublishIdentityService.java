package com.tailtopia.admin.virtual.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.virtual.domain.SeedRealAccountGrant;
import com.tailtopia.admin.virtual.domain.SeedRealAccountGrant.Status;
import com.tailtopia.admin.virtual.dto.PublishIdentityOption;
import com.tailtopia.admin.virtual.dto.RealAccountCandidate;
import com.tailtopia.admin.virtual.dto.RealAccountRow;
import com.tailtopia.admin.virtual.repository.SeedContentHashRepository;
import com.tailtopia.admin.virtual.repository.SeedRealAccountGrantRepository;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营发布身份池 —— 真实账号侧（V1.1.6 Story 12.1 · AB-3I）。
 *
 * <p><b>为什么需要它</b>：虚拟账号没有宠物档案（发不了成长日历）、没有粉丝积累、主页是空的 ——
 * 替代不了公司那几个人格化 IP 号。而此前后台的发布账号只接受虚拟账号。
 *
 * <h2>三条不能动的边界</h2>
 * <ul>
 *   <li>🛡 <b>不改 {@code account_type}</b>：真实号仍是 {@link AccountType#REAL}。改类型会让它在
 *       App 内的一切行为（登录、发帖、被查看）走进未被验证的分支。见 {@link SeedRealAccountGrant}。</li>
 *   <li>🛡 <b>不做 App 端绑定验证</b>（不要求员工扫码或输验证码，决策 A-6）：风险边界是
 *       "内部人冒充内部人"，技术强绑定挡不住，审计追责比它划算一个量级。</li>
 *   <li>🛡 <b>只接纳公司资产性质的 IP 号，不支持员工私人账号</b>（决策 A-7）：一旦支持，
 *       技术上就再无法区分公司号与私人号，风险敞口无法收口。这条<b>靠流程与审计</b>守，
 *       代码里守不住 —— 所以「授权说明」是必填的。</li>
 * </ul>
 *
 * <p>🛡 <b>移出 ≠ 封号</b>：{@link #remove} 只收回"后台可代其发布"这一项，
 * 该账号在 App 内的登录、发帖、被查看全部不受影响；历史内容的作者归属与内容本身也完全不动。
 */
@Service
public class AdminPublishIdentityService {

    private static final int NOTE_MAX = 500;

    private final UserRepository users;
    private final SeedRealAccountGrantRepository grants;
    private final SeedContentHashRepository hashes;
    private final PetProfileRepository pets;
    private final PendingPublishScheduleCounter schedules;
    private final AdminAuditService audit;

    public AdminPublishIdentityService(UserRepository users, SeedRealAccountGrantRepository grants,
            SeedContentHashRepository hashes, PetProfileRepository pets,
            PendingPublishScheduleCounter schedules, AdminAuditService audit) {
        this.users = users;
        this.grants = grants;
        this.hashes = hashes;
        this.pets = pets;
        this.schedules = schedules;
        this.audit = audit;
    }

    // ————————————————————— 读 —————————————————————

    /** 池内生效的真实账号，新纳入的在前。 */
    @Transactional(readOnly = true)
    public List<RealAccountRow> listRealAccounts() {
        List<RealAccountRow> out = new ArrayList<>();
        for (SeedRealAccountGrant g : grants.findByStatusOrderByIdDesc(Status.ACTIVE)) {
            users.findById(g.getUserId()).ifPresent(u -> out.add(toRow(g, u)));
        }
        return out;
    }

    /**
     * 三处发布入口共用的「发布账号」选项（AC6）。
     *
     * <p>顺序固定：**虚拟账号在前、运营真实账号在后**。这不是审美 ——
     * 常用路径（虚拟账号）放前面，需要格外当心的那类放后面且带标记，
     * 手滑选到第一项时选到的是危害最小的那类。
     *
     * <p>🛡 <b>本方法不做预选</b>：模板里那个 {@code disabled selected} 的占位项才是默认，
     * 强制运营显式选择。返回列表里也没有"默认项"这个概念。
     */
    @Transactional(readOnly = true)
    public List<PublishIdentityOption> selectableIdentities() {
        List<PublishIdentityOption> out = new ArrayList<>();
        for (User u : users.findByAccountTypeOrderByIdDesc(AccountType.VIRTUAL)) {
            // V1.1.6 Story 14.1：带上账号物种定位，供单条发布页的物种下拉自动跟随。
            out.add(new PublishIdentityOption(u.getId(), u.getNickname(), false, !u.isEnabled(),
                    com.tailtopia.content.species.ContentSpeciesResolver
                            .effectiveAccountSpecies(u)));
        }
        for (SeedRealAccountGrant g : grants.findByStatusOrderByIdDesc(Status.ACTIVE)) {
            // 🔴 运营真实账号**没有**账号物种定位 —— 传 null，界面据此默认留空。
            users.findById(g.getUserId()).ifPresent(u -> out.add(
                    new PublishIdentityOption(u.getId(), u.getNickname(), true, !u.isEnabled(),
                            null)));
        }
        return out;
    }

    /**
     * 纳入候选搜索（AC3）：**用户 ID 精确** 或 **昵称模糊**。
     *
     * <p>只找 {@link Role#USER} + {@link AccountType#REAL} —— 虚拟账号走另一区，
     * 兽医/管理员账号压根不是"内容作者"这条线上的东西。
     */
    @Transactional(readOnly = true)
    public List<RealAccountCandidate> searchCandidates(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        List<User> hits;
        if (q.chars().allMatch(Character::isDigit)) {
            hits = parseId(q).flatMap(users::findById)
                    .filter(u -> u.getRole() == Role.USER && u.getAccountType() == AccountType.REAL)
                    .map(List::of).orElseGet(List::of);
        } else {
            hits = users
                    .findTop20ByRoleAndAccountTypeAndNicknameContainingIgnoreCaseOrderByIdDesc(
                            Role.USER, AccountType.REAL, q);
        }
        return hits.stream()
                // 🛡 已注销的账号不该被纳入：它的昵称已被匿名化，主页也没了 —— 纳进来毫无意义，
                //    而漏掉这个判断会让运营在列表里看到一行"已注销"的发布身份。
                .filter(u -> u.getDeletedAt() == null)
                .map(u -> new RealAccountCandidate(u.getId(), u.getNickname(), u.getAvatarUrl(),
                        grants.existsByUserIdAndStatus(u.getId(), Status.ACTIVE), !u.isEnabled()))
                .toList();
    }

    /**
     * 是否在身份池内 —— **发布链路唯一该问的问题**（AC2）。
     *
     * <p>🔴 虚拟账号天然在池内（它本来就是为发种子建的）；真实账号看有没有生效授权。
     * 发布服务不要自己去比 {@code account_type}，那正是本 story 要消除的那句硬断言。
     */
    @Transactional(readOnly = true)
    public boolean isInPool(User author) {
        return author.getAccountType() == AccountType.VIRTUAL
                || grants.existsByUserIdAndStatus(author.getId(), Status.ACTIVE);
    }

    /** 该账号是否是**运营真实账号**（用于选择器上的显著标记与二次确认）。 */
    @Transactional(readOnly = true)
    public boolean isRealPublishIdentity(long userId) {
        return grants.existsByUserIdAndStatus(userId, Status.ACTIVE);
    }

    /**
     * 移出 / 禁用前要显示的待发布排期数（AC4）。
     *
     * <p>🔴 <b>只读，不做任何处置</b>：不阻止移出（运营可能正是要停掉这个号）、
     * 排期也不自动取消、不自动转草稿 —— 保持"到点标记失败并注明原因"。
     * 自动取消是替运营做决定；失败记录带原因比静默转草稿更可追溯。
     *
     * <p>⚠️ 虚拟账号的**禁用**同样走这个数（V1.1.0 原文没有这条，是本次引入排期后的连带影响）。
     */
    @Transactional(readOnly = true)
    public long pendingScheduleCount(long userId) {
        return schedules.countPendingFor(userId);
    }

    // ————————————————————— 写 —————————————————————

    /**
     * 纳入身份池（AC3）。授权说明**必填**。
     *
     * @throws AppException 账号不存在 / 不是真实用户 / 已注销 / 已在池内 / 说明为空或过长
     */
    @Transactional
    public void grant(long userId, String authorizationNote, long adminId) {
        String note = authorizationNote == null ? "" : authorizationNote.trim();
        if (note.isEmpty() || note.length() > NOTE_MAX) {
            throw AppException.validation("授权说明必填且不超过 " + NOTE_MAX + " 字");
        }
        User u = users.findById(userId)
                .orElseThrow(() -> AppException.notFound("账号不存在"));
        if (u.getRole() != Role.USER || u.getAccountType() != AccountType.REAL) {
            throw AppException.validation("仅真实用户账号可纳入运营发布身份池");
        }
        if (u.getDeletedAt() != null) {
            throw AppException.validation("该账号已注销，不可纳入");
        }
        if (grants.existsByUserIdAndStatus(userId, Status.ACTIVE)) {
            throw AppException.validation("该账号已在身份池内");
        }
        grants.save(SeedRealAccountGrant.grant(userId, note, adminId));
        audit.record(adminId, "PUBLISH_IDENTITY_GRANT", "user", String.valueOf(userId),
                "note=" + note);
    }

    /**
     * 移出身份池（AC3）。
     *
     * <p>🛡 移出后该账号**不可再被选为新内容的发布者**；历史内容的作者归属与内容本身
     * <b>完全不受影响</b>，该账号在 App 内的一切行为也不受影响。
     */
    @Transactional
    public void remove(long userId, long adminId) {
        SeedRealAccountGrant g = grants.findByUserIdAndStatus(userId, Status.ACTIVE)
                .orElseThrow(() -> AppException.validation("该账号不在身份池内"));
        g.remove(adminId);
        grants.save(g);
        audit.record(adminId, "PUBLISH_IDENTITY_REMOVE", "user", String.valueOf(userId),
                "note=" + g.getAuthorizationNote());
    }

    // ————————————————————— 内部 —————————————————————

    private RealAccountRow toRow(SeedRealAccountGrant g, User u) {
        long published = hashes.countByAuthorId(u.getId());
        long deleted = hashes.countDeletedByAuthorId(u.getId());
        Species s = deriveSpecies(u.getId());
        return new RealAccountRow(u.getId(), u.getNickname(), u.getAvatarUrl(),
                g.getAuthorizationNote(), g.getGrantedAt(), g.getGrantedBy(),
                u.isEnabled(), !u.isEnabled(), published, deleted, s.guess(), s.source());
    }

    private record Species(String guess, String source) {
    }

    /**
     * 物种推导（AC7 的只读自查列）。
     *
     * <p>⚠️ 运营真实账号**没有**「账号物种定位」字段 —— 那是虚拟账号补偿"无宠物档案"的专属字段。
     * 所以这里只能从宠物档案反推，<b>未建档就推不出来</b>，而这是常态而非异常。
     * 本列的用途就是让运营**提前知道**哪些号属于这种情况，否则无从察觉。
     *
     * <p>🔴 story 提到的"养多物种推不出"在当前数据模型里**不可能出现**：
     * {@code PetProfileRepository#findByOwnerId} 返回 {@code Optional} —— 一个用户至多一份宠物档案。
     * 所以这里只有"有档案"和"没档案"两支。真要支持多宠物，那是另一件事（会改 V1 的档案模型），
     * <b>不在本 story 提前留分支</b>：留了也没人能走到，反而让读代码的人以为支持多宠物。
     */
    private Species deriveSpecies(long userId) {
        return pets.findByOwnerId(userId)
                .map(p -> new Species(p.getPetType().name(), "宠物档案"))
                .orElse(new Species("—", "推不出（该账号未建宠物档案）"));
    }

    private static Optional<Long> parseId(String digits) {
        try {
            return Optional.of(Long.parseLong(digits));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
