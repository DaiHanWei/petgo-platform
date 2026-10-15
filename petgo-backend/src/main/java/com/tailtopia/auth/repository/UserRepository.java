package com.tailtopia.auth.repository;

import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByGoogleSub(String googleSub);

    /**
     * 这批 id 里<b>对外有效</b>账号（未注销 + status=ACTIVE，判据同 AccountQueryService.isActive）的
     * {@code (id, public_token)}（V1.3.4 follow T-19 · Story 2.1 / 2.2 共用）。
     * 已注销 / 封号的人不出现（D-8：各处按「不存在」处理，不给关注按钮）。
     */
    @Query("SELECT u.id, u.publicToken FROM User u WHERE u.id IN :ids AND u.deletedAt IS NULL "
            + "AND u.status = com.tailtopia.auth.domain.UserStatus.ACTIVE")
    java.util.List<Object[]> findActivePublicTokens(@Param("ids") java.util.Collection<Long> ids);

    /**
     * 这批 id 里<b>未注销</b>账号的 {@code (id, public_token)}，<b>封号也给</b>（V1.3.4 follow Story 2.2）。
     * 专供关注 / 粉丝列表：封号用户仍留在列表里且可被取关（D-8），取关要用 token。
     */
    @Query("SELECT u.id, u.publicToken FROM User u WHERE u.id IN :ids AND u.deletedAt IS NULL")
    java.util.List<Object[]> findUndeletedPublicTokens(@Param("ids") java.util.Collection<Long> ids);

    /** V1.3.4 follow D-2：按对外不可枚举标识取号（关注接口解析目标用）。 */
    /** 有效官方号（role=ADMIN、未注销、未封号）的 id 列表（V1.3.4 following 流 · 自动关注）。 */
    @Query("SELECT u.id FROM User u WHERE u.role = com.tailtopia.auth.domain.Role.ADMIN "
            + "AND u.deletedAt IS NULL AND u.status = com.tailtopia.auth.domain.UserStatus.ACTIVE ORDER BY u.id")
    java.util.List<Long> findActiveOfficialIds();

    Optional<User> findByPublicToken(String publicToken);

    /**
     * 关注 / 取关的冗余计数 ±1（V1.3.4 follow AD-1，只由 {@code FollowCounterService} 调用）。
     *
     * <p>🔴 必须在 SQL 里改，<b>禁止</b>读出来在 Java 里 ±1 再 save（并发丢更新）；
     * 计数列在实体上是只读映射，save 也写不进去。{@code GREATEST(…, 0)} 兜底，计数永不为负。
     */
    @Modifying
    @Query(value = "UPDATE users SET following_count = GREATEST(following_count + :delta, 0) WHERE id = :userId",
            nativeQuery = true)
    int addFollowingCount(@Param("userId") long userId, @Param("delta") int delta);

    /** 同 {@link #addFollowingCount}，改 {@code follower_count}。 */
    @Modifying
    @Query(value = "UPDATE users SET follower_count = GREATEST(follower_count + :delta, 0) WHERE id = :userId",
            nativeQuery = true)
    int addFollowerCount(@Param("userId") long userId, @Param("delta") int delta);

    /**
     * 注销清理：被注销者关注的每个人 {@code follower_count − 1}（一条集合 UPDATE，不逐行循环）。
     * 必须在删关注行<b>之前</b>执行。
     */
    @Modifying
    @Query(value = """
            UPDATE users u SET follower_count = GREATEST(u.follower_count - 1, 0)
              FROM user_follows f
             WHERE f.follower_id = :userId AND u.id = f.followee_id
            """, nativeQuery = true)
    int decrementFollowerCountsOfFollowees(@Param("userId") long userId);

    /**
     * 注销清理：关注被注销者的每个人 {@code following_count − 1}（一条集合 UPDATE）。
     * 必须在删关注行<b>之前</b>执行。
     */
    @Modifying
    @Query(value = """
            UPDATE users u SET following_count = GREATEST(u.following_count - 1, 0)
              FROM user_follows f
             WHERE f.followee_id = :userId AND u.id = f.follower_id
            """, nativeQuery = true)
    int decrementFollowingCountsOfFollowers(@Param("userId") long userId);

    /** 注销清理：本人两计数置 0。 */
    @Modifying
    @Query(value = "UPDATE users SET following_count = 0, follower_count = 0 WHERE id = :userId",
            nativeQuery = true)
    int zeroFollowCounts(@Param("userId") long userId);

    /** 重新读出 {@code follower_count}（实体值是加载时快照，关注写入后要最新值时用）。 */
    @Query(value = "SELECT follower_count FROM users WHERE id = :userId", nativeQuery = true)
    Integer findFollowerCount(@Param("userId") long userId);

    /**
     * 这批 id 里哪些是**仍然可见**的账号（V1.3.0 batch-b1 Story 4.1）。
     *
     * <p>判据与 {@code AccountQueryService.isActive} 逐字相同（未软删 + status=ACTIVE）——
     * 那个是单个查，这里是**批量**版（AD-6：推荐池一页十几只宠物，逐个查就是十几次往返）。
     * ⚠️ 两处判据必须一致：不一致的表现是「推荐位里那只宠物点进去 404」。
     */
    @Query("SELECT u.id FROM User u WHERE u.id IN :ids AND u.deletedAt IS NULL "
            + "AND u.status = com.tailtopia.auth.domain.UserStatus.ACTIVE")
    java.util.List<Long> findActiveIds(@Param("ids") java.util.Collection<Long> ids);

    /** FR-44：Apple 登录按 apple_sub 取号（首登未命中则建号）。 */
    Optional<User> findByAppleSub(String appleSub);

    /** Story 3.1：ADMIN 账密登录按 email + role 精确匹配。 */
    Optional<User> findByEmailAndRole(String email, Role role);

    /**
     * 后台用户列表的浏览态筛选（V1.1.6 Story 11.4 的手机号筛选 × V1.3.0 Story 8.1 的账号状态筛选），
     * id 倒序分页。
     *
     * <p>🔴 手机号「未填写」的判据是 <b>{@code phone IS NULL OR phone = ''}</b> —— 两种空都要算。
     * FR-70 允许用户**留空保存以撤回号码**（保存时写 null），
     * 而历史上也可能存在空串；只判 NULL 会把撤回过的人错分到"已填写"，
     * 于是运营的催填名单里就永远少了这批人。
     *
     * <p>⚠️ 字段名一律用 {@code phone}：日志脱敏按字段名匹配，
     * 换个别名转手该值就会绕过脱敏、让真实号码落盘（见该列的迁移注释）。
     *
     * <p>⚠️ 两个可选条件一律用**哨兵字符串**（{@code 'any'} / {@code 'all'}）而不是绑 null ——
     * 绑 null 的话「不筛」这条最常走的路会去比较 NULL，判定恒为 unknown，
     * 列表直接变成空页（而首次打开页面走的正是这条路）。
     *
     * <p>🔴 状态三态与 {@code AdminUserRow} 的展示逐条对齐：
     * {@code deleted}=已注销（{@code deletedAt} 非空，与 {@code status} 正交，优先级最高）、
     * {@code deactivated}=运营停用且未注销、{@code active}=未注销且未停用。
     */
    @Query("""
            SELECT u FROM User u
             WHERE u.role = :role
               AND (:phoneMode = 'any'
                    OR :phoneMode = 'filled' AND u.phone IS NOT NULL AND u.phone <> ''
                    OR :phoneMode = 'empty' AND (u.phone IS NULL OR u.phone = ''))
               AND (:status = 'all'
                    OR :status = 'deleted' AND u.deletedAt IS NOT NULL
                    OR :status = 'deactivated' AND u.deletedAt IS NULL AND u.status = :deactivated
                    OR :status = 'active' AND u.deletedAt IS NULL AND u.status <> :deactivated)
             ORDER BY u.id DESC
            """)
    Page<User> findAdminUsers(@Param("role") Role role, @Param("phoneMode") String phoneMode,
            @Param("status") String status,
            @Param("deactivated") com.tailtopia.auth.domain.UserStatus deactivated,
            Pageable pageable);

    /** 召回名单导出（Story 11.4）：同一筛选口径、不分页、id 倒序。 */
    @Query("""
            SELECT u FROM User u
             WHERE u.role = :role
               AND (:filled = true
                    AND u.phone IS NOT NULL AND u.phone <> ''
                    OR :filled = false
                    AND (u.phone IS NULL OR u.phone = ''))
             ORDER BY u.id DESC
            """)
    java.util.List<User> findAllByRoleAndPhoneFilled(@Param("role") Role role,
            @Param("filled") boolean filled);

    /** 虚拟账号列表（Story 9.8，A-6），近建在前。 */
    java.util.List<User> findByAccountTypeOrderByIdDesc(AccountType accountType);


    /** 概览看板（Story 9.10）：按账号类型计数（如虚拟账号数）。 */
    long countByAccountType(AccountType accountType);

    /** 概览看板（bug 20260731-442）：真实且未注销的用户数（剔除虚拟/种子账号与已注销）。 */
    long countByAccountTypeAndDeletedAtIsNull(AccountType accountType);

    /** 内容审核 story 4：违规重置默认昵称唯一性查重（DefaultNameGenerator）。 */
    boolean existsByNickname(String nickname);

    /**
     * 按昵称模糊搜索某类账号（V1.1.6 Story 12.1 · AC3 纳入身份池用）。
     *
     * <p>⚠️ 与后台用户搜索（{@code AdminUserService#search}，走下方
     * {@link #searchByDisplayedName}）<b>刻意分开</b>：这里要按 {@code accountType}
     * 锁定虚拟号池，且只匹配 {@code nickname} 一列（虚拟号必填昵称）。
     */
    java.util.List<User> findTop20ByRoleAndAccountTypeAndNicknameContainingIgnoreCaseOrderByIdDesc(
            Role role, com.tailtopia.auth.domain.AccountType accountType, String nickname);

    /**
     * 后台用户搜索：按**展示昵称或注册邮箱**模糊匹配（2026-09-02 运营诉求：手里常常
     * 只有前台截图上的昵称，或只记得邮箱的一段）。
     *
     * <p>昵称口径与列表展示一致（{@code AdminUserService#currentName}）：改过名走
     * {@code nickname}，没改过走注册时的 {@code displayName} —— 两列都 like 会出现
     * 「搜到的词不在页面任何一行上」的怪异命中。
     *
     * <p>已注销账号不参与模糊匹配：昵称/邮箱已随注销匿名化，快照列仅供按 id /
     * 邮箱精确定位后**展示**，拿来搜等于把匿名化又打开一条缝。
     */
    @Query("""
            select u from User u
            where u.role = :role and u.deletedAt is null
              and (lower(coalesce(u.nickname, u.displayName)) like :pattern
                   or lower(coalesce(u.email, '')) like :pattern)
            order by u.id desc
            """)
    List<User> searchByDisplayedNameOrEmail(@Param("role") Role role,
            @Param("pattern") String pattern, Pageable pageable);

    /**
     * 生命周期推送日扫候选（留存手册抓手 1）。只取<b>能被推、且推了有意义</b>的账号：
     * 普通用户（不推兽医/管理员）、真实账号（<b>不推虚拟种子账号</b>——给自己的运营马甲发召回
     * 既污染漏斗又毫无意义）、未注销、未停用、状态 ACTIVE（被封号的人不该收到「回来记录吧」）。
     */
    @Query("select u from User u where u.role = :role and u.accountType = :accountType "
            + "and u.deletedAt is null and u.enabled = true and u.status = :status")
    List<User> findLifecyclePushCandidates(@Param("role") Role role,
            @Param("accountType") AccountType accountType,
            @Param("status") UserStatus status);

    /**
     * 刷新最后活跃时刻，<b>每日至多写一次</b>（留存手册抓手 1 的流失判定依据）。
     *
     * <p>{@code dayStart} 之后已刷过则 WHERE 不命中 → Postgres 不产生行版本、不写 WAL，
     * 代价只剩一次主键索引查找。这就是为什么这里不需要 Redis 去重键：
     * 条件 UPDATE 本身就是幂等的，再压一层缓存只会多一个可失效的活动部件。
     *
     * <p>JPQL 批量更新绕过 {@code @PreUpdate} —— 刻意为之：活跃刷新<b>不应</b>动 {@code updated_at}
     * （那一列的语义是「业务数据被改过」，不是「人来过」）。
     */
    @Modifying
    @Query("update User u set u.lastActiveAt = :now "
            + "where u.id = :userId and (u.lastActiveAt is null or u.lastActiveAt < :dayStart)")
    int touchLastActiveAt(@Param("userId") long userId, @Param("now") Instant now,
            @Param("dayStart") Instant dayStart);

    /**
     * 记一笔「某人某个 WIB 自然日来过」（日报 DAU 口径，见 V20260925_1330）。
     * 同日重复调用命中主键冲突即跳过 —— 只是一次索引查找，不产生写。
     */
    @Modifying
    @Query(value = "INSERT INTO user_active_days (user_id, active_date) VALUES (:userId, :day) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int recordActiveDay(@Param("userId") long userId, @Param("day") java.time.LocalDate day);

    /**
     * 用户标签选择器的候选（bug 20260828）：**未注销**的普通用户，按 id 或昵称模糊匹配。
     *
     * <p>🔴 {@code deletedAt is null} 是这条查询存在的主要理由 —— 后台此前只有一个手填
     * 用户 ID 的文本框，运营把标签分给了一个已注销账号。选择器从**列不出来**开始堵，
     * 服务层的硬校验（{@code UserTagQueryService#assign}）是第二道。两道都要有：
     * 只有选择器的话，手填那条路照样能绕过去。
     *
     * <p>⚠️ **不剔除已停用（封号）账号**：运营有时确实要给封号账号挂标签（如「观察中」），
     * 但候选行必须标注状态 —— 与召回名单导出同一条口径（不替运营做决定，
     * 但也不让他在不知情的情况下操作）。
     *
     * <p>⚠️ {@code :pattern} 绝不传 null：空关键词传 {@code "%"} 匹配全部。
     * 绑 null 时 Postgres 推不出类型，而「不带关键词」正是首次加载的那一次
     * （与内容标签的 {@code searchPinnable} 同一个坑）。
     */
    @Query("""
            select u from User u
            where u.role = :role and u.deletedAt is null
              and (:pattern = '%'
                   or lower(coalesce(u.nickname, '')) like :pattern
                   or lower(coalesce(u.displayName, '')) like :pattern
                   or cast(u.id as string) like :pattern)
            order by u.id desc
            """)
    Page<User> searchTaggableUsers(@Param("role") Role role, @Param("pattern") String pattern,
            Pageable pageable);
}

