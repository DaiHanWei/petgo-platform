# Story 1.1: 关注关系、用户 token 与关注接口

Status: review

> 版本 v1.3.4 · 主题 follow · 分支 `feat/1.3.4-follow-notify`
> 口径优先级：`决策日志-follow.md` > `PRD-v1.3.4-follow.md` > `architecture-v1.3.4-follow-delta.md` > `ui-v1.3.4-follow.html`（均在 `_bmad-output/planning-artifacts/v1.3.4/`）

## Story

As a 用户,
I want 关注 / 取关另一个用户,
so that 我能和喜欢的人建立持续的关系。

## Acceptance Criteria

**AC1 — 迁移：关注表与计数列** `[L0]` `[L1]`
1. 新迁移 `V<yyyyMMdd_HHmm>__create_user_follows_and_counters.sql`（取创建时刻；提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`）：
   - `CREATE TABLE user_follows (follower_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE, followee_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (follower_id, followee_id), CONSTRAINT ck_user_follows_not_self CHECK (follower_id <> followee_id))`；
   - 索引 `idx_user_follows_followee_created (followee_id, created_at DESC)`（粉丝列表）、`idx_user_follows_follower_created (follower_id, created_at DESC)`（关注列表 + 每日上限计数）；
   - `ALTER TABLE users ADD COLUMN following_count INT NOT NULL DEFAULT 0, ADD COLUMN follower_count INT NOT NULL DEFAULT 0`；
   - `COMMENT ON` 表与两列（冗余计数，只由关注 / 取关 / 拉黑取关 / 注销清理在同事务内条件更新，AD-1）。
2. 实体 `com.tailtopia.social.domain.UserFollow`（表 `user_follows`，复合主键 `follower_id + followee_id`，`createdAt`）；仓储 `social/repository/UserFollowRepository`。
3. `User` 加 `followingCount` / `followerCount` 两个字段与 getter，**映射为只读**：`@Column(name = "following_count", insertable = false, updatable = false)`（`follower_count` 同）。原因见 Dev Notes「计数列不可被实体回写」。

**AC2 — 迁移：用户 token** `[L0]` `[L1]`（D-2，AD-2）
1. 新迁移 `V<yyyyMMdd_HHmm>__users_public_token.sql`：
   - 新增 `users.public_token VARCHAR(32)`；存量行用 SQL 随机 **base62 32 位**回填（迁移里定义一个生成函数，如 `gen_base62_token(32)`，用 `random()` 逐字取 62 字符表；仅回填与 DB 默认值使用，新用户由应用层 `SecureRandom` 生成）；
   - 回填后 `SET NOT NULL` + `ADD CONSTRAINT uq_users_public_token UNIQUE (public_token)`；
   - 给列加 `DEFAULT gen_base62_token(32)`——测试代码里有 **26 个文件**直接 `INSERT INTO users (nickname, status) …`（如 `shop/order/CheckoutIntegrationTest.java:57`），不带默认值会全部撞 NOT NULL；
   - `COMMENT ON COLUMN users.public_token`（对外不可枚举用户标识，32 位 base62；自增 id 不外露；注销后保留不变）。
2. 新增 `com.tailtopia.auth.service.UserTokenGenerator`：照 `place/service/PlaceTokenGenerator` 写法（BASE62 × 32 + `SecureRandom`），提供**静态** `generate()`（理由见下条）。
3. `User` 加 `publicToken` 字段（`@Column(name = "public_token", nullable = false, updatable = false, length = 32)`）与 getter；在 `User.onCreate()`（`@PrePersist`）里 `if (publicToken == null) publicToken = UserTokenGenerator.generate()`。这样 `newGoogleUser` / `newAppleUser` / `newAdmin` / `newVirtual` 四个工厂与全部测试夹具（`ApiIntegrationTest.newUser`）**一处覆盖**，不必改各建号调用点。
4. `AccountQueryService` 新增 `Optional<User> findActiveByPublicToken(String token)`：`public_token = :token AND deleted_at IS NULL AND status = ACTIVE AND role = USER`（判据与 `isActive` 一致再加 role）；`token` 为空 / 长度不是 32 → 直接 empty，不查库。另加 `Optional<User> findByPublicToken(String token)`（只要求 `role = USER`、未注销，**不看封号**），供取关用（见 AC4.3）。

**AC3 — 关注 `PUT /api/v1/users/{token}/follow`** `[L1]`
1. `social/web/FollowController`，需登录 USER：`SecurityConfig` 增加 `.requestMatchers("/api/v1/users/*/follow").hasRole("USER")`（现有 `/api/v1/users/*/profile|posts|pet|mini-profile` 只放行 GET，见 `SecurityConfig.java:199-212`；不加这一条兽医 token 也能进来）。
2. `social/service/FollowService.follow(long viewerId, String targetToken)`，单事务，顺序：
   1. 限流 `RedisRateLimiter.check("rl:social:follow:" + viewerId, 30, Duration.ofMinutes(1))`，超出 429 `rate-limited`；
   2. `accounts.findActiveByPublicToken(token)` 为空（不存在 / 已注销 / 封号）→ 404 `not-found`；
   3. 目标是自己 → 422 `validation`；
   4. `hideRelations.isBlocked(viewerId, targetId)`（**只认 BLOCK**）→ 409 `follow-blocked`；`REPORT` 来源不拦；**对方拉黑了我照常往下走**（D-4）；
   5. 已关注 → 不做任何写入，直接返回当前状态（幂等，不计入每日上限）；
   6. 每日上限：`user_follows` 中 `follower_id = viewerId AND created_at >= 当前 WIB 自然日 00:00` 的行数 ≥ 500 → 429 `rate-limited`（AD-4；WIB 切日写法同 `PlaceCheckinService.java:113`，注入 `Clock` 便于测试）；
   7. `INSERT INTO user_follows … ON CONFLICT DO NOTHING`，**仅当受影响行数 = 1** 时同事务执行 `following_count + 1`（viewer）与 `follower_count + 1`（target）——并发双击只加一次。
3. 返回 `200` + `social/dto/FollowResultResponse { boolean iFollow, boolean followsMe, long followerCount }`：`iFollow = true`；`followsMe` = 对方是否关注我；`followerCount` = 写入后**重新读出**的对方 `follower_count`（不得用实体缓存值，见 AC1.3）。
4. 本 story **不发 `USER_FOLLOWED` 通知、不发事件**（Story 3.2 在「受影响行数 = 1」分支接入）。

**AC4 — 取关 `DELETE /api/v1/users/{token}/follow`** `[L1]`
1. `FollowService.unfollow(long viewerId, String targetToken)`，单事务：限流（与关注共用 `rl:social:follow:{userId}` 桶，见待确认 1）→ 解析 token → 是自己 → 422。
2. `DELETE FROM user_follows WHERE follower_id = ? AND followee_id = ?`，**仅当删除行数 = 1** 时两端计数 −1，写法 `GREATEST(x - 1, 0)`；未关注时静默成功（幂等）。
3. token 解析用 `findByPublicToken`（**封号用户也能取关**）：封号用户仍会出现在我的 Following 列表里（Story 2.2，AD-4），若取关也要求对方 ACTIVE，用户就永远取关不掉一个被封号的人。不存在 / 已注销 → 404。（见待确认 2）
4. 返回同 `FollowResultResponse`（`iFollow = false`）。**不发任何通知或推送**（FR-140.12 / PRD AC9）。

**AC5 — 读端口 `FollowReader`** `[L0]` `[L1]`
1. `social/read/FollowReader`（接口）+ 实现：
   - `FollowState followState(long viewerId, long targetId)`；
   - `Map<Long, FollowState> followStates(long viewerId, Collection<Long> targetIds)`——**一次查询**（`user_follows` 按 `(viewer→targets) ∪ (targets→viewer)` 一条 SQL），空集合不发查询；缺失的 id 填 `FollowState.NONE`。
2. `social/read/FollowState(boolean iFollow, boolean followsMe)` record，常量 `NONE = (false, false)`。
3. 模块边界同 `UserHideRelationReader`：`auth` / `content` / `notify` 只依赖本接口，不引用 `social.repository`。本 story 只有 `FollowService` 用它；Story 1.2 / 2.1 / 2.2 / 3.3 消费。

**AC6 — 计数写入的归属** `[L0]`
1. 两个计数列属 `users` 表（auth 模块）。新增 `auth/service/FollowCounterService`（`@Transactional(propagation = MANDATORY)`）提供 `applyFollow(followerId, followeeId)` / `applyUnfollow(followerId, followeeId)` / `purgeCountsFor(userId)`（AC7 用），内部走 `UserRepository` 的 native `UPDATE users SET … = GREATEST(… ± 1, 0) WHERE id = ?`；`social` 只调这个服务，不直写 `users` 表。
2. 禁止把计数读出来在 Java 里 ±1 再 save（并发丢更新）。

**AC7 — 注销清理关注关系** `[L1]`（AD-1，NFR4）
1. `FollowService.purgeUser(long userId)`（`@Transactional`，幂等）：
   - 该用户关注的每个人 `follower_count − 1`、关注该用户的每个人 `following_count − 1`（各一条集合 UPDATE … FROM user_follows，不逐行循环）；
   - 删除 `follower_id = userId` 与 `followee_id = userId` 的全部行；该用户自己两计数置 0。
2. `AccountDeletionService.execute` 接入：放在 `authDeletion.deleteByUserId(userId)` **之后**（此时 `deleted_at` 已写，`findActiveByPublicToken` 已拒绝新的关注，清理后不会再长出新行），加注释说明顺序原因。
3. ⚠️ **不能依赖 `ON DELETE CASCADE`**：注销是**就地匿名化**，`users` 行不删（`AuthAccountDeletionService.java:53`），CASCADE 永远不会触发。FK 的 CASCADE 仍按 AD-1 保留（防将来真删行）。
4. 封号**不删关系、不改计数**（AD-1）。

**AC8 — 错误类型** `[L0]`
1. `ErrorTypes.FOLLOW_BLOCKED = BASE + "follow-blocked"`；`AppException.followBlocked(String detail)` → `409`。javadoc 写明：只在「我拉黑了对方」时出现；对方拉黑我不出现（D-4，不暴露被拉黑）。

**AC9 — 测试** `[L0]` `[L1]`
1. 集成测试 `social/FollowIntegrationTest extends ApiIntegrationTest` 覆盖：
   - 关注成功：两端计数各 +1、响应三字段正确；重复 PUT 计数不变；
   - 取关：两端 −1；重复 DELETE 静默 200；计数不低于 0；
   - 关注自己 422；我拉黑对方 409 `follow-blocked`（断言 `$.type` 以 `follow-blocked` 结尾）；仅 REPORT 隐藏 → 照常成功；对方拉黑我 → 照常成功且对方 `follower_count` +1（D-4）；
   - 对方已注销 / 封号 / 随机 32 位 token → 404；封号用户可以被取关（AC4.3）；
   - 游客 401；兽医 token 403；
   - 同一用户第 31 次操作 429（`$.type` 以 `rate-limited` 结尾）；
   - 注销：A 关注 B、C 关注 A → 执行 `FollowService.purgeUser(A)` 后 B 的 `follower_count` 与 C 的 `following_count` 各 −1，两方向行全清；重跑幂等；
   - 新建用户自动有 32 位 `public_token`；两次建号 token 不同；`jdbc` 直插的用户也有 token（DB 默认值）。
2. 单测 `social/service/FollowServiceTest`（mock 仓储 + 固定 `Clock`）：当日计数 500 → 429；WIB 切日边界（UTC 16:59 vs 17:00）计数窗口正确；已关注时不查每日上限。
3. `FollowReader.followStates` 断言一次查询覆盖整批（Hibernate statistics 或 `jdbc` 计数，任选既有写法）。
4. `AccountDeletionServiceTest`（mock 全部依赖，构造器 `AccountDeletionServiceTest.java:36`）补 `FollowService` mock 与调用顺序断言；`MetricQueryParityTest` / `SyntheticAccountParityTest` 等用 `users.save` 的测试不需改。
5. `mvn -B clean package` 绿（L0）；L1 连本分支新建空库跑。

## Tasks / Subtasks

- [x] Task 1：迁移（AC1、AC2）
  - [x] 1.1 `create_user_follows_and_counters` 迁移
  - [x] 1.2 `users_public_token` 迁移（生成函数 + 回填 + NOT NULL + UNIQUE + DEFAULT）；本地空库从零跑通 Flyway
- [x] Task 2：实体与 token（AC1、AC2）
  - [x] 2.1 `UserFollow` 实体 + `UserFollowRepository`（native `insertIfAbsent` / `deleteRelation` / `countCreatedSince` / 批量状态查询 / 注销清理 SQL）
  - [x] 2.2 `User` 加 `publicToken`、两计数（只读映射）、`@PrePersist` 生成 token
  - [x] 2.3 `UserTokenGenerator`；`AccountQueryService.findActiveByPublicToken` / `findByPublicToken`；`UserRepository.findByPublicToken`
- [x] Task 3：服务与接口（AC3、AC4、AC6、AC8）
  - [x] 3.1 `FollowCounterService`（auth）+ `UserRepository` 计数 native UPDATE
  - [x] 3.2 `FollowService.follow` / `unfollow`（限流、拒绝顺序、每日上限、条件计数）
  - [x] 3.3 `FollowController` + `FollowResultResponse` + `SecurityConfig` 规则
  - [x] 3.4 `ErrorTypes.FOLLOW_BLOCKED` + `AppException.followBlocked`
- [x] Task 4：读端口（AC5）
  - [x] 4.1 `FollowReader` / `FollowState` / 实现
- [x] Task 5：注销联动（AC7）
  - [x] 5.1 `FollowService.purgeUser`；`AccountDeletionService` 接入（`authDeletion` 之后）
- [x] Task 6：测试（AC9）

## Dev Notes

### 现状（改动前必读）

- **关注完全不存在**：无表、无实体、无接口（AD §0）。`social` 包现有：`UserHideRelation`（拉黑 / 举报隐藏）、`UserHideRelationService`、`UserHideRelationReader`、`MeBlockedUsersController`、`BlockedUsersQueryService`。新代码与之同包。
- **建号入口**（都经 `User` 静态工厂 + `users.save`）：`AuthService.java:103`（Google）、`:144`（Apple）、`DevUserSeeder.java:40`、`AdminVirtualAccountService.java:121`（虚拟号）、`AdminBootstrap.java:94`（ADMIN shim）；测试 `ApiIntegrationTest.newUser`（`support/ApiIntegrationTest.java:153`）。`User` 已有 `@PrePersist onCreate()`（`User.java:204`），在这里补 token 最省、不漏。
- **测试里有 26 个文件用 `jdbc` 直插 `users`**（`grep -rln "INSERT INTO users" src/test`，集中在 `shop/**`），只给 `nickname, status` —— 所以 `public_token` 必须有 DB 默认值。
- **注销 = 就地匿名化，不删 `users` 行**：`AuthAccountDeletionService.deleteByUserId`（`auth/service/AuthAccountDeletionService.java:40-56`）调 `User.anonymizeForDeletion`（`User.java:431`），`deleted_at` 置值、PII 擦除。编排在 `AccountDeletionService.execute`（`account/service/AccountDeletionService.java:134-206`），各模块各自事务、幂等可重跑；`authDeletion` 在 `:195`。⇒ 关注行的 FK CASCADE 不会触发，必须显式清理（AC7）。
- **账号有效判据**：`AccountQueryService.isActive`（`auth/service/AccountQueryService.java:59`）= `deleted_at IS NULL AND status = ACTIVE`；批量版 `UserRepository.findActiveIds`（`auth/repository/UserRepository.java:28`）。`findUserById`（`:187`）只过滤 `role = USER`。封号 = `UserStatus.DEACTIVATED`（`AdminUserService.deactivate` → `AuthService.deactivateUser`），可逆。
- **拉黑判据**：`UserHideRelationReader.isBlocked(holder, target)` 只认 `BLOCK`（`social/read/UserHideRelationReader.java:46`）；`isHidden` 不分来源。关注只看 `isBlocked(viewer, target)`。
- **幂等插入先例**：`UserHideRelationRepository.insertIfAbsent`（`social/repository/UserHideRelationRepository.java:72-79`）用 native `ON CONFLICT DO NOTHING` 并返回行数；注释写明**不要**用「save + catch 唯一约束异常」（共享事务被标 rollback-only → 500）。关注照抄。
- **限流**：`RedisRateLimiter.check(key, limit, window)`（`shared/ratelimit/RedisRateLimiter.java:30`），固定窗口，超限抛 `AppException.rateLimited`（429，`ErrorTypes.RATE_LIMITED`）。现有调用只在 `AuthController` / `VetAuthController`（键 `rl:auth:*`）。**无按天限流先例**，按天上限一律 DB 计数（AD §0）。
- **WIB 自然日写法**：`PlaceCheckinService.java:53`（`ZoneId.of("Asia/Jakarta")`）与 `:112-113`（`clock.instant().atZone(WIB).toLocalDate()`），构造器注入 `Clock`；`IdCardShareRewardService.java:54-55` 同口径。
- **token 生成器先例**：`place/service/PlaceTokenGenerator.java:21-36`（BASE62 × 32 + `SecureRandom`，`@Component` 实例方法）。本 story 需要在 `@PrePersist` 里用，故做成静态方法（或静态 + 实例两种入口）。迁移里的存量回填先例 `V20260930_1131__add_place_checkin_pets.sql:16-20` 用的是 `md5` 截 32 位（hex，非 base62）；AD-2 要求 base62，本 story 按 AD-2 走生成函数。
- **错误类型**：`ErrorTypes`（`shared/error/ErrorTypes.java`，`BASE = "https://petgo/errors/"`）；`AppException` 工厂在 `shared/error/AppException.java:65-170`，409 先例 `keepsakeAlreadyUnlocked`。
- **安全规则**：`SecurityConfig.java:199-212` 对 `/api/v1/users/*/{mini-profile,profile,posts,pet}` 只放行 GET；其余落到默认 `authenticated()`（兽医也算已认证）。
- **最新迁移**：`V20261009_0113__tailsonality_match_unlock.sql`；V1–V108 为 legacy 序号，新迁移必须时间戳命名。

### 计数列不可被实体回写（必须保持）

Hibernate 默认 UPDATE 全列。`MeService.updateMe`（`auth/service/MeService.java:63-137`）、`captureLocale`、昵称审核、头像审核等都会 load `User` → 改字段 → save。如果 `following_count` / `follower_count` 是可更新映射，这些 save 会把**加载时的旧计数**写回去，覆盖掉同期别人的关注（丢更新且无报错）。所以两列必须 `insertable = false, updatable = false`，只能由 native UPDATE 改。`public_token` 同理设 `updatable = false`。

### 架构约束

- AD-1：`user_follows` 结构、索引、冗余计数同事务条件更新、`GREATEST(…, 0)` 兜底；注销先删关系再扣对端计数；封号不动。
- AD-2 / D-2：新接口只认 `public_token`；现有数字 id 路由与字段不动。
- AD-4：拒绝规则与状态码（自己 422 / 我拉黑对方 409 `follow-blocked` / 不存在·注销·封号 404）；对方拉黑我照常成功（D-4）；`rl:social:follow:{userId}` 30 次 / 分钟；每日 500 按 WIB 自然日 DB 计数。
- 护栏：Redis 只做限流（不缓存关注状态）；`ddl-auto=validate`，schema 只归 Flyway；错误统一 RFC 9457；日志不记 token（token 是对外标识，等同签名 URL 级别对待，日志只记内部 id）。
- 不新增中间件、不引 MQ。

### 本 story 不做

- 不发 `USER_FOLLOWED` 通知 / 事件（Story 3.2）；不做 24 小时去重。
- 不改主页 / `/me` / Feed / 详情接口的返回（Story 1.2 / 2.1）；不做拉黑联动（Story 1.2）。
- 不做关注 / 粉丝列表接口与推荐接口（Story 2.2 / 2.3）。
- 不改任何 App 代码。

### 测试

- 集成测试基类 `ApiIntegrationTest`（真 PG + Redis + MockMvc 全安全链）；参考 `social/UserHideRelationIntegrationTest.java`（拉黑写入、401、幂等的断言写法）。
- **本地必须连本分支的新库**跑 L1（默认 `petgo` 库带着别分支的迁移，Flyway validate 会让整类测试全 Error）：`DB_NAME=<新建空库> ./mvnw -B test`；`JAVA_HOME` 指 21。
- 限流测试注意 Redis 键按 userId 分，每个用例用新用户即可，不必清 Redis；**不要 FLUSH**。
- 每日 500 上限放单测（集成里造 500 个用户成本高）；集成只测 31 次 / 分钟。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.4/epics-v1.3.4-follow.md#Story 1.1]
- [Source: _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-follow-delta.md#AD-1 / AD-2 / AD-4 / §9 迁移清单]
- [Source: _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-follow.md#3.1 关注关系 / 拉黑与关注 / 其他规则（频率限制、虚拟号）/ AC9]
- [Source: _bmad-output/planning-artifacts/v1.3.4/决策日志-follow.md#D-2 / D-4]
- [Source: CLAUDE.md#Flyway 新迁移一律时间戳版本号 / 强制护栏]

### 待确认项裁定（2026-10-12，操作者 Hex；详见 `planning-artifacts/v1.3.4/决策日志-follow.md` D-8~D-11 / T-x）

> **以下裁定优先于本文件正文与 Completion Notes 中的「待确认」与暂定做法**；标 ⚠️ 的条目与正文暂定做法不同，实现时以此为准。

- ⚠️ D-9 官方内容账号可被关注：`findActiveByPublicToken` 去掉 `role = USER` 过滤（官方号 ADMIN 作者 shim 可被关注、可进列表）；官方号不主动关注人、不接收推送。注意 SecurityConfig 的「仅 USER 可调关注接口」仍只约束**调用方**，不影响被关注方。
- D-8 封号用户：关注 → 404；取关照常成功（正文暂定做法确认）。
- T-1 关注与取关共用 `rl:social:follow:{userId}` 限流桶（确认）。
- T-2 关注接口不看 `enabled`（确认）。
- T-3 每日 500 按当日新建且仍存在的行数（确认）。
- ⚠️ T-4 两条列表索引建三列版本：`(follower_id, created_at DESC, followee_id DESC)` / `(followee_id, created_at DESC, follower_id DESC)`。

## Dev Agent Record

### Agent Model Used

Claude（云端 headless session，bmad-dev-story 流程手工执行）

### Debug Log References

- 云端 `mvnw` 需 `LC_ALL=C.UTF-8`：存量测试有中文嵌套类名（`ScheduleWindowTest`），POSIX locale 下 javac 写 class 文件报 bad filename（与本 story 无关）。

### Completion Notes List

**实现摘要（2026-10-12）**
- 迁移：`V20261012_1020__create_user_follows_and_counters.sql`（表 + T-4 三列索引 + 两计数列）、`V20261012_1021__users_public_token.sql`（`gen_base62_token` 函数回填 + NOT NULL + UNIQUE + DEFAULT）。避开 app 主题预分配 `V20261012_1101~1108`，全部远端分支无撞号；`check-flyway-versions.sh origin/main` 通过。
- 按裁定 D-9：`findActiveByPublicToken` 与 `findByPublicToken` **都不按 role 过滤**（官方号可关注，也就必须可取关；正文 AC2.4「`findByPublicToken` 要求 role = USER」随 D-9 一并去掉）。
- code-review（2026-10-12）修复：
  - F1 互相关注并发死锁 → `FollowCounterService` 两行 users 按 id 升序更新；
  - F2 注销 / 封号后旧 access token 仍可关注、留下无清理入口的脏行 → `follow` 开头校验调用方 `isActive`，否则 401（新增单测 + 集成用例）；
  - F5 「计数不低于 0」未真正覆盖 → 集成测试补「计数已 0 但行仍在时取关」用例。
- L0：`clean package -DskipTests` 绿；`FollowServiceTest`(14) / `UserTokenGeneratorTest`(2) / `AccountDeletionServiceTest`(3) 全绿；全量纯单测回归 2988 通过（唯一失败为需 DB 的 `DashboardMaterializerIntegrationTest`，L1）。
- **L1 待本地验收**：`FollowIntegrationTest`（含 FollowReader 一次查询断言）、Flyway 空库从零跑通。**L1/L2 待本地验收。**

**待确认（code-review 留档，未改）**
- F3（PLAUSIBLE）：注销清理与「已解析目标、尚未提交」的并发关注 / 取关存在极低概率竞态（孤儿行 / 计数少扣），未加锁处理。
- F4：`ApiAccessLoggingFilter` 原样记录请求路径，`/api/v1/users/{token}/follow` 的 token 会进访问日志——与现有 `/places/{token}` 同口径的全局存量问题，建议统一对路径 token 段打码，超出本 story。
- F6（PLAUSIBLE）：一次查询断言用 SessionFactory 全局统计，若测试上下文有后台线程同时查库可能偶发 >1；本地 L1 若遇到再改 `StatementInspector`。

**原写 story 时的待确认（均已由「待确认项裁定」覆盖，保留备查）**

- 待确认 1：每分钟 30 次的「关注操作」是否包含**取关**。本 story 先按「关注与取关共用一个桶」写（PRD「单个用户每分钟最多 30 次关注操作」、AD-4 只给了一个键）；若只限关注，DELETE 去掉限流即可。
- 待确认 2：**取关封号用户**。AD-4 / epics 只写了「关注」时对方封号 → 404，取关未写。本 story 让取关对封号用户照常成功（否则我的 Following 列表里被封号的人永远取关不掉）；已注销 / 不存在仍 404。
- 待确认 3：**`role = ADMIN` 的官方内容作者 shim** 能否被关注。`findActiveByPublicToken` 按 `role = USER` 过滤，则官方号 404；而官方号的帖子会出现在 Feed，Story 2.1 若给它显示「+ Ikuti」，点了会失败。PRD 只说虚拟号可被关注，没提官方号。需要产品定：官方号可关注（去掉 role 过滤）还是 Feed 卡不对官方号显示按钮。
- 待确认 4：**`enabled = false` 的虚拟号**能否被关注。AD-4 的推荐接口要求 `enabled`，关注接口没写。本 story 不看 `enabled`（只看注销 / 封号 / role）。
- 待确认 5：每日 500 按「当日新建、**仍存在**的关注行数」计（AD-4 原文）——当天关注后又取关的那行不计入。限制效果靠每分钟 30 次兜底；若产品要求取关不退额度，需要另建计数表，超出 AD-4。
- 实现提示（非待确认）：epics Story 2.2 / AD-4 写「已注销用户不在列表（CASCADE）」，实际不会因 CASCADE 消失（`users` 行不删），而是靠本 story AC7 的显式清理；Story 2.2 实现时不要依赖 CASCADE。
- L1/L2 待本地验收。

### File List

- petgo-backend/src/main/resources/db/migration/V20261012_1020__create_user_follows_and_counters.sql（新）
- petgo-backend/src/main/resources/db/migration/V20261012_1021__users_public_token.sql（新）
- petgo-backend/src/main/java/com/tailtopia/auth/domain/User.java
- petgo-backend/src/main/java/com/tailtopia/auth/repository/UserRepository.java
- petgo-backend/src/main/java/com/tailtopia/auth/service/AccountQueryService.java
- petgo-backend/src/main/java/com/tailtopia/auth/service/FollowCounterService.java（新）
- petgo-backend/src/main/java/com/tailtopia/auth/service/UserTokenGenerator.java（新）
- petgo-backend/src/main/java/com/tailtopia/social/domain/UserFollow.java（新）
- petgo-backend/src/main/java/com/tailtopia/social/repository/UserFollowRepository.java（新）
- petgo-backend/src/main/java/com/tailtopia/social/read/FollowReader.java / FollowReaderImpl.java / FollowState.java（新）
- petgo-backend/src/main/java/com/tailtopia/social/service/FollowService.java（新）
- petgo-backend/src/main/java/com/tailtopia/social/dto/FollowResultResponse.java（新）
- petgo-backend/src/main/java/com/tailtopia/social/web/FollowController.java（新）
- petgo-backend/src/main/java/com/tailtopia/shared/error/ErrorTypes.java / AppException.java
- petgo-backend/src/main/java/com/tailtopia/shared/security/SecurityConfig.java
- petgo-backend/src/main/java/com/tailtopia/account/service/AccountDeletionService.java
- petgo-backend/src/test/java/com/tailtopia/social/FollowIntegrationTest.java（新，L1）
- petgo-backend/src/test/java/com/tailtopia/social/service/FollowServiceTest.java（新，L0）
- petgo-backend/src/test/java/com/tailtopia/auth/service/UserTokenGeneratorTest.java（新，L0）
- petgo-backend/src/test/java/com/tailtopia/account/service/AccountDeletionServiceTest.java
