# Story 5.1: 运营弹窗数据与 App 读取接口

Status: ready-for-dev

## Story

As a 运营,
I want 配置好的弹窗由服务端按人群、时间、频次筛好再下发给 App,
so that 每个用户只收到该看、还没看够次数的弹窗。

> 本 story **只做后端**：两张表、实体、统一跳转目标的后端枚举与校验（Epic 6 复用）、App 读取接口 `GET /api/v1/ops-popups/candidates`、曝光上报 `POST /api/v1/ops-popups/{token}/impressions`、注销联动。App 侧数据层与弹窗在 Story 5.4；跳转执行在 Story 5.2。后台配置页（AB-30）与其写接口归 admin 主题（D-1 / D-2 / AD-1），**本 story 不写任何后台页面或后台写接口**。

## Acceptance Criteria

**AC1 — 迁移 `V20261012_1106__create_ops_popups.sql`** `[L0]` `[L1]`
1. 文件名必须**一字不差**为 `petgo-backend/src/main/resources/db/migration/V20261012_1106__create_ops_popups.sql`（预分配号，不得另取）。内容照 Dev Notes「迁移全文」逐字落盘（注释可补充，DDL 不得改动）。
2. `ops_popups` 列与可空性（AD-14 + D-23）：`id` · `public_token VARCHAR(32) NOT NULL UNIQUE` · `name VARCHAR(100) NOT NULL` · `status VARCHAR(16) NOT NULL DEFAULT 'DISABLED'`（CHECK `ACTIVE`/`DISABLED`）· `starts_at TIMESTAMPTZ NOT NULL` · `ends_at TIMESTAMPTZ NULL`（**NULL = 长期有效**，D-23；CHECK `ends_at IS NULL OR ends_at > starts_at`）· `priority INT NOT NULL DEFAULT 0` · `audience VARCHAR(8) NOT NULL DEFAULT 'ALL'`（CHECK `ALL`/`NEW`/`OLD`）· `new_user_days INT NOT NULL DEFAULT 7`（CHECK `> 0`）· `frequency VARCHAR(8) NOT NULL DEFAULT 'ONCE'`（CHECK `ONCE`/`DAILY`）· `image_url_id VARCHAR(512) NOT NULL` · `image_url_en VARCHAR(512) NULL` · `title_id/title_en VARCHAR(120) NULL` · `body_id/body_en VARCHAR(255) NULL` · `button_text_id VARCHAR(40) NOT NULL` · `button_text_en VARCHAR(40) NULL` · `target_type VARCHAR(16) NOT NULL DEFAULT 'NONE'`（CHECK 五值）· `target_value VARCHAR(512) NULL`（CHECK `(target_type = 'NONE') = (target_value IS NULL)`）· `created_at` / `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`。
3. `ops_popup_impressions(id, popup_id, user_id, shown_at)`：`popup_id` FK → `ops_popups(id) ON DELETE CASCADE`；`user_id` FK → `users(id) ON DELETE CASCADE`；`shown_at TIMESTAMPTZ NOT NULL DEFAULT now()`；索引 `(user_id, popup_id, shown_at DESC)`。
4. 部分索引 `idx_ops_popups_active_pick ON ops_popups (priority DESC, created_at DESC, id DESC) WHERE status = 'ACTIVE'`。
5. 不使用 `CONCURRENTLY`；不预置任何弹窗数据（运营数据由 admin 主题写入；L2 用 Story 5.4 Dev Notes 的 SQL 在 stag 手工灌）。
6. `bash scripts/ci/check-flyway-versions.sh origin/main` 通过。
7. L0 迁移文本测试 `OpsPopupMigrationTest` 钉住：文件名、两表、全部 CHECK 约束名与取值、两条 FK 的 `ON DELETE CASCADE`、无 `CONCURRENTLY`。
8. L1：Spring 上下文以 `ddl-auto=validate` 启动成功（实体与表一致）。

**AC2 — 实体与统一跳转目标后端枚举（Epic 6 复用）** `[L0]`
1. 新建 `com.tailtopia.ops.target.OpsTargetType`（`NONE, PRODUCT, CATEGORY, APP_PAGE, EXTERNAL`）、`OpsAppPage`（`HOME, PUBLISH, TOKO, KNOW_YOUR_PET, HEALTH`，**只这 5 个**）、`OpsTargetView` record `(String type, String value)`。
2. 新建 `com.tailtopia.ops.target.OpsTargetPolicy`（`@Component`）：
   - `static boolean isWellFormed(OpsTargetType type, String value)`：`NONE` ⇔ `value == null`；`PRODUCT` 值匹配 `^[0-9A-Za-z_-]{1,32}$`；`CATEGORY` 值 ∈ `ProductCategory` 枚举名（`MAKANAN` / `OBAT_VITAMIN` / `CAMILAN` / `PERAWATAN`，区分大小写）；`APP_PAGE` 值 ∈ `OpsAppPage` 枚举名（区分大小写）；`EXTERNAL` 值长度 ≤ 512、`URI` 可解析、scheme（忽略大小写）为 `http` 或 `https`、host 非空。其余一律 false。
   - `boolean isAvailable(OpsTargetType type, String value)`：先 `isWellFormed`，`PRODUCT` 再要求 `ShopProductRepository.findByPublicTokenAndActiveTrue(value).isPresent()`；其余类型 well-formed 即可用（品类是固定枚举、无下线状态，AD-14）。
   - 这是后台保存弹窗（AB-30）与运营通知（Epic 6）**唯一**的目标校验口径，类注释写明「admin 主题与 Story 6.x 直接调用，不得另写一份」。
3. 新建 `com.tailtopia.ops.OpsTokenGenerator`（SecureRandom + Base62 22 位，字符表与长度照抄 `ShopTokenGenerator`）。
4. 新建实体 `com.tailtopia.ops.popup.domain.OpsPopup`（`@Table(name = "ops_popups")`）与 `OpsPopupImpression`，以及枚举 `OpsPopupStatus` / `OpsPopupAudience` / `OpsPopupFrequency`；枚举列 `@Enumerated(EnumType.STRING)`，`length` 与迁移一致。`OpsPopup` 提供静态工厂 `create(...)`（新建 `status = DISABLED`、生成 `public_token`）+ `activate()` / `disable()` + getter——**供 admin 主题与测试夹具使用**；本 story 不提供写接口。

**AC3 — 候选接口 `GET /api/v1/ops-popups/candidates`** `[L0]` `[L1]`
1. `SecurityConfig` 加**精确** matcher `GET /api/v1/ops-popups/candidates` → `permitAll()`（游客可读）。带有效 JWT 时识别用户；**只认 `role = USER`**，VET token / 无 JWT 一律按游客处理（照 `MiniProfileController.viewerId` 写法）。
2. 过滤条件（全部成立才进列表）：
   - `status = 'ACTIVE'`；
   - 时间窗左闭右开：`starts_at <= now` 且（`ends_at IS NULL` 或 `now < ends_at`），判定语义与 `ScheduleWindow.isActiveAt` 一致；
   - 人群：**游客** → 只要 `audience = ALL`；**登录用户** → `ALL` 加上 `NEW`（`users.created_at >= now − new_user_days × 24h`，即注册时长 ≤ N 天，**恰好 N×24h 算 NEW**）或 `OLD`（注册时长 > N×24h）二者之一；
   - 跳转目标可用：`OpsTargetPolicy.isAvailable(target_type, target_value)`（PRODUCT 须商品存在且 `is_active = true`）；
   - 图片可解析：`image_url_id` 经「图片地址解析」（见 AC3.4）得到非空 URL；
   - **登录用户**频次未满：`ONCE` → 该用户对该弹窗**从无**曝光记录；`DAILY` → 该用户对该弹窗在**当前 WIB 自然日**（`[今日 00:00 WIB, 明日 00:00 WIB)`，用 `ScheduleWindow.WIB`）无曝光记录。**游客不做频次过滤**（AD-15，频次在设备本地）。
3. 排序 `priority DESC, created_at DESC, id DESC`；最多返回 **20** 条。
4. 图片地址解析（`image_url_id` / `image_url_en` 两列同一规则）：值以 `https://` 开头 → 原样返回；否则视为 OSS objectKey，经既有 `ShopImageUrlResolver.publicUrl(key)` 拼公开桶 CDN URL；拼不出（null）→ 视为不可用。`image_url_id` 不可用 → 该弹窗不下发；`image_url_en` 不可用 → 该字段省略（App 回落 ID 图，PRD AC6）。以 `http://` 开头的值视为不可用（不下发明文图）。
5. 响应 `200`，JSON（全局 NON_NULL，null 字段整个省略）：
   ```json
   {
     "items": [
       {
         "token": "aB3...22位",
         "frequency": "ONCE",
         "imageUrlId": "https://…",
         "imageUrlEn": "https://…",
         "titleId": "Diskon 20% makanan kucing",
         "titleEn": "20% off cat food",
         "bodyId": "Cuma sampai Minggu. Yuk, cek pilihannya di Toko!",
         "bodyEn": "Only until Sunday. Check the picks in Toko!",
         "buttonTextId": "Lihat sekarang",
         "buttonTextEn": "See now",
         "target": { "type": "CATEGORY", "value": "MAKANAN" }
       }
     ]
   }
   ```
   - `token` = `public_token`（**不外露自增 id**，D-24）；`target.type` 为五值之一，`NONE` 时为 `{"type":"NONE"}`（value 省略）；
   - 空白字符串（`trim().isEmpty()`）的标题 / 正文 / 按钮 EN 文案按 null 输出（省略）；
   - **不返回** `name`、`priority`、`audience`、`new_user_days`、`status`、时间窗、自增 id；
   - 无候选 → `200 {"items": []}`（不是 204，App 只有一种解析分支）。
6. 后台编辑弹窗不重置频次：频次挂在 `popup_id` 上，编辑不换 id、不换 token（AD-14）——由 L1 测试钉住「改了标题后 ONCE 仍不再下发」。

**AC4 — 曝光上报 `POST /api/v1/ops-popups/{token}/impressions`** `[L0]` `[L1]`
1. `SecurityConfig` 精确 matcher `POST /api/v1/ops-popups/*/impressions` → `hasRole("USER")`（游客 401，VET 403）。
2. 无请求体；token 不存在 → `404 not-found`（`AppException.notFound("弹窗不存在")`）；存在（无论当前状态 / 时间窗）→ 插入一行 `ops_popup_impressions(popup_id, user_id, shown_at = 服务端当前时刻)`，响应 `204 No Content`。
3. 不去重：同一用户重复上报照插（DAILY 跨日需要多行；同日多报不影响判定）。
4. 限流：`rl:ops-popup:impression:{userId}`，30 次 / 1 分钟（常量放 `OpsPopupController` 顶部，照 `PlaceController` 写法），超限走既有 429。
5. 上报后：`ONCE` 弹窗不再下发给该用户；`DAILY` 当天 WIB 内不再下发，过 WIB 午夜恢复（L1 覆盖）。

**AC5 — 注销联动** `[L0]` `[L1]`
1. 新建 `com.tailtopia.ops.popup.service.OpsPopupImpressionDeletionService.deleteByUserId(long userId)`（`@Transactional`，物理删该用户全部曝光行，幂等）。
2. `AccountDeletionService.execute` 中在 `onboardingMarkDeletion.deleteByUserId(userId);`（现 L187）之后调用；构造器末尾追加参数并同步 `AccountDeletionServiceTest`。理由：注销**不物理删 `users` 行**（`AuthAccountDeletionService` L53 `anonymizeForDeletion`），FK 的 `ON DELETE CASCADE` 在生产注销路径上不会触发，必须显式删（D1/D2）。FK CASCADE 仍保留，是为测试库 `deleteAll` 不被阻断。

**AC6 — 以夹具数据自证** `[L1]`
`OpsPopupIntegrationTest extends ApiIntegrationTest` 用 `OpsPopup.create(...)` + 仓储（或 `JdbcTemplate`）预置数据覆盖：游客只见 ALL 且不受曝光影响；NEW / OLD 边界（`UPDATE users SET created_at = now() - interval '…'` 构造）；停用 / 未开始 / 已结束 / `ends_at IS NULL` 长期有效；商品下架即消失；EXTERNAL 非 http(s) 不下发；排序；ONCE 上报后消失；DAILY 当天消失；编辑后频次不重置；VET token 按游客；曝光 404 / 401 / 403；注销后曝光行被删。

## Tasks / Subtasks

- [ ] **T1 迁移**（AC1）
  - [ ] 按 Dev Notes「迁移全文」新建 `V20261012_1106__create_ops_popups.sql`
  - [ ] `src/test/java/com/tailtopia/ops/popup/OpsPopupMigrationTest.java`（L0，读迁移文件文本断言，照 `place/PlaceSchemaAlignmentTest` 的文件读取方式）
- [ ] **T2 统一跳转目标后端件**（AC2）
  - [ ] `ops/target/OpsTargetType.java`、`OpsAppPage.java`、`OpsTargetView.java`、`OpsTargetPolicy.java`
  - [ ] `ops/OpsTokenGenerator.java`
  - [ ] `OpsTargetPolicyTest`（L0）：五类型合法 / 非法各至少一例；`EXTERNAL` 的 `HTTPS://x.id`（大写 scheme 合法）、`javascript:alert(1)`、`ftp://x`、`https://`（无 host）、513 字符；`APP_PAGE` 的 `home`（小写非法）、`SETTINGS`（非 5 值）；`NONE` 带值非法；`PRODUCT` 下架（mock 仓储返回 empty）不可用
- [ ] **T3 实体与仓储**（AC2）
  - [ ] `ops/popup/domain/OpsPopup.java`、`OpsPopupImpression.java`、`OpsPopupStatus.java`、`OpsPopupAudience.java`、`OpsPopupFrequency.java`
  - [ ] `ops/popup/repository/OpsPopupRepository.java`：`findByPublicToken(String)`；JPQL `findLiveCandidates(Instant now, Collection<OpsPopupAudience> audiences)`（`status = ACTIVE`、`startsAt <= :now`、`(endsAt is null or :now < endsAt)`、`audience in :audiences`、`order by priority desc, createdAt desc, id desc`）
  - [ ] `ops/popup/repository/OpsPopupImpressionRepository.java`：`@Query select distinct i.popupId ... where i.userId = :uid and i.popupId in :ids` 与带 `i.shownAt >= :since` 的同名重载；`@Modifying deleteByUserId`
- [ ] **T4 候选服务与接口**（AC3 / AC4）
  - [ ] `ops/popup/service/OpsPopupCandidateService.java`：`List<OpsPopupCandidateView> candidates(Long viewerUserId)`；注入 `Clock`（照 `TailsonalityResultService` 双构造器：`@Autowired` 公开构造器用 `Clock.systemUTC()`，包可见构造器收 `Clock` 供测试）
  - [ ] `ops/popup/service/OpsPopupImpressionService.java`：`record(String token, long userId)`
  - [ ] `ops/popup/dto/OpsPopupCandidateView.java`、`OpsPopupCandidatesResponse.java`
  - [ ] `ops/popup/web/OpsPopupController.java`：`@RequestMapping("/api/v1/ops-popups")`，`GET /candidates`、`POST /{token}/impressions`；`viewerId(Jwt)` 照 `MiniProfileController` L85-96
  - [ ] `SecurityConfig`：在 `GET /api/v1/shop/banner` 放行（L222）之后加两条精确 matcher（见 AC3.1 / AC4.1），**不得**写 `/api/v1/ops-popups/**`
  - [ ] `OpsPopupCandidateServiceTest`（L0 Mockito + 固定 `Clock`）：游客只查 ALL 且**不调用**曝光仓储；VET（controller 传 null）同游客；NEW 边界恰好 N×24h → NEW、N×24h+1s → OLD；DAILY 在 `16:59:59Z` 已曝光（WIB 23:59:59）→ 次日 `17:00:00Z` 可再下发；商品下架剔除；`image_url_id` 为 key 且 CDN base 未配置 → 剔除；`http://` 图剔除；EN 图不可用 → 字段 null；空白 EN 文案 → null；超过 20 条截断；排序透传
  - [ ] `OpsPopupControllerTest`（L0，直接 new 控制器 + mock 服务）：jwt 为 null / role=VET → 服务收到 `null`；role=USER → 收到 userId；impressions 对未知 token 抛 404
- [ ] **T5 注销联动**（AC5）
  - [ ] `ops/popup/service/OpsPopupImpressionDeletionService.java` + 接入 `AccountDeletionService`
  - [ ] 更新 `account/service/AccountDeletionServiceTest`（构造器多一个 mock；断言 `deleteByUserId` 被调且在 `authDeletion.deleteByUserId` 之前，`InOrder`）
- [ ] **T6 L1 集成测试**（AC6，云端写好不运行）
  - [ ] `src/test/java/com/tailtopia/ops/popup/OpsPopupIntegrationTest.java`：`@BeforeEach` 执行 `DELETE FROM ops_popups`（曝光级联删；该表只有本测试类写入）
- [ ] **T7 L0 门禁**：后端编译 + 排除 L1 的单测全绿；`check-flyway-versions.sh` 通过

## Dev Notes

> 🔴 **开工前先对照前面 story 实际提交的代码核对本文件引用的名字，有出入先改本文件并在 Completion Notes 记一句。** 本 story 编号在 Epic 1~4 之后，期间 `SecurityConfig`、`AccountDeletionService` 行号可能已漂移，以 grep 结果为准。

### 必读：会被本 story 改到的现有代码

| 文件 | 现状（已核实） | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `petgo-backend/src/main/java/com/tailtopia/shared/security/SecurityConfig.java` | 业务 API 链 `apiFilterChain`（L144 起）；每条路径精确列出；`GET /api/v1/shop/banner` permitAll 在 L222；末尾 `anyRequest().authenticated()`（L327） | 在 L222 之后加 `GET /api/v1/ops-popups/candidates` permitAll、`POST /api/v1/ops-popups/*/impressions` hasRole USER | 不写通配；其它规则顺序不动 |
| `petgo-backend/src/main/java/com/tailtopia/account/service/AccountDeletionService.java` | 构造器 L77-117（21 个参数，末位 `TailsonalityDeletionService`）；`execute` 在 L187 调 `onboardingMarkDeletion.deleteByUserId`、L195 `authDeletion.deleteByUserId` | 构造器末尾追加 `OpsPopupImpressionDeletionService`；L187 后调用 | 既有调用顺序（尤其「user 行匿名化前」的注释约束） |
| `petgo-backend/src/test/java/com/tailtopia/account/service/AccountDeletionServiceTest.java` | `new AccountDeletionService(...)` 手工装配 | 追加 mock 参数 + 一条顺序断言 | 既有断言不改 |

### 本 story 新建（全部不存在，已 grep 核实 `ops_popup` / `OpsPopup` / `ops-popups` 在仓库零命中）

后端包 `com.tailtopia.ops`（新顶层模块，Epic 6 的运营通知也放这里）：
- `ops/OpsTokenGenerator.java`
- `ops/target/{OpsTargetType, OpsAppPage, OpsTargetView, OpsTargetPolicy}.java`
- `ops/popup/domain/{OpsPopup, OpsPopupImpression, OpsPopupStatus, OpsPopupAudience, OpsPopupFrequency}.java`
- `ops/popup/repository/{OpsPopupRepository, OpsPopupImpressionRepository}.java`
- `ops/popup/service/{OpsPopupCandidateService, OpsPopupImpressionService, OpsPopupImpressionDeletionService}.java`
- `ops/popup/dto/{OpsPopupCandidateView, OpsPopupCandidatesResponse}.java`
- `ops/popup/web/OpsPopupController.java`

### 迁移全文（`V20261012_1106__create_ops_popups.sql`）

```sql
-- V1.3.4 app 主题 · Story 5.1 · 运营弹窗（FR-137 · AD-14 · 决策日志 D-23 / D-24）。
--
-- 本主题只建表 + App 读接口 + 曝光记录；弹窗的新建 / 编辑 / 停用由 admin 主题（AB-30）写入（D-2 / AD-1）。
-- 不预置任何弹窗数据。stag L2 验收用 Story 5.4 Dev Notes 的 INSERT 手工灌。
--
-- 可空性（D-23）：ends_at 可空 = 长期有效；title / body 两语言均可空；button_text_id 必填、button_text_en 可空（App 回落 ID）；
-- image_url_en 可空（App 回落 ID 图，PRD AC6）。
-- image_url_* 存「https:// 完整地址」或「公开桶 OSS objectKey」二者之一；读接口统一换算为完整 URL（ShopImageUrlResolver）。
-- 对外标识一律 public_token（D-24：埋点 popup_id 也取它），自增 id 不外露。
CREATE TABLE ops_popups (
    id              BIGINT        GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    public_token    VARCHAR(32)   NOT NULL,
    name            VARCHAR(100)  NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'DISABLED',
    starts_at       TIMESTAMPTZ   NOT NULL,
    ends_at         TIMESTAMPTZ   NULL,
    priority        INT           NOT NULL DEFAULT 0,
    audience        VARCHAR(8)    NOT NULL DEFAULT 'ALL',
    new_user_days   INT           NOT NULL DEFAULT 7,
    frequency       VARCHAR(8)    NOT NULL DEFAULT 'ONCE',
    image_url_id    VARCHAR(512)  NOT NULL,
    image_url_en    VARCHAR(512)  NULL,
    title_id        VARCHAR(120)  NULL,
    title_en        VARCHAR(120)  NULL,
    body_id         VARCHAR(255)  NULL,
    body_en         VARCHAR(255)  NULL,
    button_text_id  VARCHAR(40)   NOT NULL,
    button_text_en  VARCHAR(40)   NULL,
    target_type     VARCHAR(16)   NOT NULL DEFAULT 'NONE',
    target_value    VARCHAR(512)  NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_ops_popups_public_token UNIQUE (public_token),
    CONSTRAINT ck_ops_popups_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_ops_popups_audience CHECK (audience IN ('ALL', 'NEW', 'OLD')),
    CONSTRAINT ck_ops_popups_frequency CHECK (frequency IN ('ONCE', 'DAILY')),
    CONSTRAINT ck_ops_popups_new_user_days CHECK (new_user_days > 0),
    CONSTRAINT ck_ops_popups_window CHECK (ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_ops_popups_target_type
        CHECK (target_type IN ('NONE', 'PRODUCT', 'CATEGORY', 'APP_PAGE', 'EXTERNAL')),
    CONSTRAINT ck_ops_popups_target_value CHECK ((target_type = 'NONE') = (target_value IS NULL))
);

CREATE INDEX idx_ops_popups_active_pick ON ops_popups (priority DESC, created_at DESC, id DESC)
    WHERE status = 'ACTIVE';

CREATE TABLE ops_popup_impressions (
    id        BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    popup_id  BIGINT       NOT NULL,
    user_id   BIGINT       NOT NULL,
    shown_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_ops_popup_impressions_popup FOREIGN KEY (popup_id)
        REFERENCES ops_popups (id) ON DELETE CASCADE,
    CONSTRAINT fk_ops_popup_impressions_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_ops_popup_impressions_user_popup
    ON ops_popup_impressions (user_id, popup_id, shown_at DESC);

COMMENT ON TABLE ops_popups IS
    '运营弹窗（V1.3.4 FR-137）：冷启动进 Diary / Social 时按人群、时间窗、频次、优先级最多弹一个。配置由后台 AB-30 写入。';
COMMENT ON COLUMN ops_popups.public_token IS '对外不可枚举标识（App 接口 token / 埋点 popup_id）';
COMMENT ON COLUMN ops_popups.name IS '仅后台可见的内部名称，不下发 App';
COMMENT ON COLUMN ops_popups.status IS 'ACTIVE / DISABLED；未开始 / 进行中 / 已结束由时间窗现算（WIB 口径同 ScheduleWindow）';
COMMENT ON COLUMN ops_popups.starts_at IS '生效开始（UTC，左闭）';
COMMENT ON COLUMN ops_popups.ends_at IS '生效结束（UTC，右开）；NULL = 长期有效（D-23）';
COMMENT ON COLUMN ops_popups.priority IS '多个同时生效时取值大的优先；同值按 created_at、id 倒序';
COMMENT ON COLUMN ops_popups.audience IS 'ALL 全部（含游客）/ NEW 注册 ≤ new_user_days 天 / OLD 注册 > new_user_days 天';
COMMENT ON COLUMN ops_popups.new_user_days IS '新 / 老用户分界天数，默认 7（PRD 补充细则）';
COMMENT ON COLUMN ops_popups.frequency IS 'ONCE 每人一次 / DAILY 每个 WIB 自然日最多一次';
COMMENT ON COLUMN ops_popups.image_url_id IS '印尼文图（4:5）：https 完整地址或公开桶 objectKey';
COMMENT ON COLUMN ops_popups.image_url_en IS '英文图；NULL = 与印尼文共用';
COMMENT ON COLUMN ops_popups.button_text_en IS 'NULL = 回落 button_text_id（D-23）';
COMMENT ON COLUMN ops_popups.target_type IS '跳转目标类型（与运营通知共用）：NONE / PRODUCT / CATEGORY / APP_PAGE / EXTERNAL';
COMMENT ON COLUMN ops_popups.target_value IS 'PRODUCT=商品 public_token；CATEGORY=ProductCategory 枚举名；APP_PAGE=HOME/PUBLISH/TOKO/KNOW_YOUR_PET/HEALTH；EXTERNAL=http(s) 链接；NONE 时为 NULL';
COMMENT ON TABLE ops_popup_impressions IS
    '运营弹窗曝光（登录用户的频次记录；游客频次只在设备本地，AD-15）。注销时由 OpsPopupImpressionDeletionService 物理删除。';
COMMENT ON COLUMN ops_popup_impressions.shown_at IS '服务端收到曝光上报的时刻（UTC）；DAILY 按 WIB 自然日判定';
```

### 可直接复用

| 要做的事 | 用这个 | 位置（已核实） |
|---|---|---|
| WIB 时区 / 时间窗语义 | `ScheduleWindow.WIB`、`ScheduleWindow.isActiveAt(now, start, end)`（end 为 null = 永不结束） | `shared/schedule/ScheduleWindow.java` L34 / L46 |
| objectKey → 公开 CDN URL | `ShopImageUrlResolver.publicUrl(String)`（CDN base 未配置返回 null） | `shop/service/ShopImageUrlResolver.java` L38 |
| 商品是否在售 | `ShopProductRepository.findByPublicTokenAndActiveTrue(String)` | `shop/repository/ShopProductRepository.java` L16 |
| 品类枚举 | `ProductCategory`（`MAKANAN, OBAT_VITAMIN, CAMILAN, PERAWATAN`） | `shop/domain/ProductCategory.java` |
| 用户注册时间 | `AccountQueryService.findUserById(long)` → `User.getCreatedAt()` | `auth/service/AccountQueryService.java` L187；`auth/domain/User.java` L352 |
| permitAll 端点识别登录用户（只认 USER） | `viewerId(Jwt)` 私有静态方法 | `auth/web/MiniProfileController.java` L85-96 |
| 限流 | `RedisRateLimiter.check(key, limit, window)` | `shared/ratelimit/RedisRateLimiter.java` L30；用法见 `place/web/PlaceController.java` L59-60 / L189-190 |
| 404 | `AppException.notFound(String)` | `shared/error/AppException.java` L69 |
| token 生成范式 | `ShopTokenGenerator`（Base62 22 位） | `shop/service/ShopTokenGenerator.java` |
| 可注入时钟 | 双构造器（`@Autowired` 公开 + 包可见收 `Clock`） | `tailsonality/service/TailsonalityResultService.java` L46-56 |
| 实体时间戳 | `@PrePersist` / `@PreUpdate` 写 `createdAt` / `updatedAt` | `shop/domain/ShopBanner.java` L99-109 |
| L1 基类 | `ApiIntegrationTest`：`newUser()`、`userBearer(id)`、`vetBearer(id)`、`mvc`、`users` | `src/test/java/com/tailtopia/support/ApiIntegrationTest.java` |
| L1 造商品 | `ShopProduct.create(token, name, brand, category, key, null, null, List.of(), Species.DOG, null, null, "<p/>", List.of(), "n", ReturnPolicy.RETURNABLE, 0)` + `list()` 上架 / `delist()` 下架 | `shop/domain/ShopProduct.java` L129 / L178 / L196；测试用法 `shop/cart/service/CartViewTotalsTest.java` L87 |

### 关键设计点

- **候选是「有序列表」不是「一个」**：App 还要用本地频次记录二次剔除（游客期间弹过的 ONCE 登录后不再弹，PRD AC4），所以服务端返回过滤后的有序列表（最多 20），客户端取第一个可用的（AD-14 / AD-15）。
- **游客不做频次过滤、VET 视同游客**：`permitAll` 端点 VET token 也进得来，VET 的 `sub` 是 vetId，会与 `users.id` 撞号——拿它查曝光 / 注册时间就是拿无关用户的数据做判断（`MiniProfileController` 同一教训）。
- **图片列名保留 AD-14 的 `image_url_*`，内容允许 objectKey**：项目惯例图片列存 objectKey（`ShopBanner.image_key`，「签名 URL 禁入库」），AD-14 定的列名是 `image_url_*`；为不改架构已批准的列名、又不逼后台存 URL，读接口两种都认（见 AC3.4）。admin 主题按任一方式写都能工作。
- **目标可用性判定只有一份**：`OpsTargetPolicy` 同时服务「后台保存校验」（admin 主题）、「候选过滤」（本 story）、「运营通知发送」（Epic 6）。品类是固定枚举（AD-14），后台保存时校验即可，候选过滤只做 well-formed。
- **频次挂 `popup_id`**：后台编辑不换 id，频次不重置（后台 PRD 2026-10-08 / AD-14）；删除弹窗则曝光级联删除（无副作用，该弹窗已不存在）。
- **曝光不去重**：DAILY 需要跨日多行；同日重复上报只是多一行，判定按「是否存在」不受影响。曝光写入时间以服务端为准，客户端不传时间（防手机时间被改）。
- **为什么 `ends_at > starts_at` 用 CHECK**：左闭右开下相等即永不生效，属配置错误，库层直接拒（admin 主题会在表单层先校验，库层兜底）。
- **注销**：`users` 行在注销时只匿名化不删除，FK CASCADE 不会触发 ⇒ 必须显式删曝光行（AC5）。

### l10n 新 key

本 story 无 App 文案；后端无新 messageSource 键（404 detail 为固定中文，不面向用户展示）。

### 必须更新的既有测试

- `account/service/AccountDeletionServiceTest`（构造器参数 + 顺序断言）。
- 其余既有测试不应受影响；若 `SecurityConfig` 相关的路由守卫测试（如 `shared/security/ShopUserEndpointsRoleGuardTest`）按「精确路径清单」断言，按其规则把两条新路径登记进去，不删既有断言。

### 验证层级与命令

- **L0（云端跑）**：AC1.1-1.7、AC2、AC3 / AC4 / AC5 的单测部分。
  ```bash
  cd petgo-backend
  LC_ALL=C.UTF-8 ./mvnw -B clean package -DskipTests
  # 排除需要 Postgres / Redis 的类：所有继承 ApiIntegrationTest 的类 + 带 @SpringBootTest 的类（含本 story 新增的 OpsPopupIntegrationTest）
  grep -rlE "extends ApiIntegrationTest|@SpringBootTest" src/test/java | sed -E 's#^src/test/java/##' > /tmp/l1-excludes.txt
  LC_ALL=C.UTF-8 ./mvnw -B test -Dsurefire.excludesFile=/tmp/l1-excludes.txt
  cd .. && bash scripts/ci/check-flyway-versions.sh origin/main
  ```
  `LC_ALL=C.UTF-8` 必加（`ScheduleWindowTest` 的中文内部类名在 POSIX locale 下写不出 class 文件，V1.3.2 已踩）。
- **L1（本地）**：AC1.8、AC3、AC4、AC5、AC6——`OpsPopupIntegrationTest`、`AccountDeletion*IntegrationTest` 回归。**云端写好测试代码但不运行**，Completion Notes 记「L1 待本地验收」。
- **L2**：无（视觉在 Story 5.4）。

### Project Structure Notes

- 新顶层包 `com.tailtopia.ops`：`ops.target` 放跨弹窗 / 通知共用的目标类型与校验，`ops.popup` 放弹窗；Epic 6 在 `ops.notification`（由 6.x 决定）复用 `ops.target` 与 `OpsTokenGenerator`。
- 跨模块依赖只读：`ops` → `shop`（`ShopProductRepository`、`ShopImageUrlResolver`、`ProductCategory`）、`ops` → `auth`（`AccountQueryService`）、`ops` → `shared`；不反向依赖。仓库无 ArchUnit 约束（已 grep 核实）。
- 后台写接口、后台页面一律不在本 story（D-2）。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.4/epics-v1.3.4-app.md#Story 5.1]
- [Source: _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-app-delta.md#AD-1, AD-14, AD-15, AD-16]
- [Source: _bmad-output/planning-artifacts/v1.3.4/决策日志-app.md#D-2, D-23, D-24]
- [Source: _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-app.md#3.6 运营弹窗（FR-137）展示规则 / 补充细则 / AC1-AC8]
- [Source: _bmad-output/implementation-artifacts/v1.3.4/cloud-rules-app.md]
- [Source: CLAUDE.md#Flyway 时间戳迁移 / 对外暴露标识不可枚举 / ProblemDetail]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

#### 待确认

- **图片列语义**：AD-14 定名 `image_url_*`，项目惯例存 objectKey；本 story 两种都认（`https://` 原样、其余按 objectKey 拼 CDN）。admin 主题落地时若统一一种写法，可在 admin 主题收窄，不影响 App。
- **文案列长度**：PRD / AD 未给弹窗标题、正文、按钮的长度上限。取标题 ≤120、正文 ≤255（与 `notifications` 表同口径，PRD §3.7 影响面）、按钮 ≤40、名称 ≤100；admin 主题表单按此校验。
- **`target_value` 与 `NONE` 的强约束**（`(target_type = 'NONE') = (target_value IS NULL)`）：后台表单选「无跳转」时必须写 NULL 而非空串。
- **候选上限 20 条**：无出处，按「客户端只取第一个可用的」推出的保守值。

### File List

### Change Log

- 2026-10-12：story 文件创建（ready-for-dev）。
