# V1.5.0 架构增量 · 健康记录提醒（会话 ① health-reminder）

> 日期 2026-11-14 · 分支 `feat/1.5.0-health-reminder`（从 `dev_1.5.0` 地基 Story 0.1 之后切出）
> 来源 PRD：`宠物健康记录提醒/1-5-0健康记录提醒prd.md`（Final，2026-11-10，FR-158 ~ FR-164、FR-166 ~ FR-172，AC1 ~ AC70；英文）
> UI：`宠物健康记录提醒/ui-1-5-0-health-reminder-integrated-v1.html`（页头「交研发说明 / 代码落点」）+ `拆页-App/`（53 屏，从 `00-目录.html` 进）
> 跨版本基线：`planning-artifacts/architecture.md`；跨 story 契约：`implementation-artifacts/CROSS-STORY-DECISIONS.md`「2026-11-14 V1.5.0 六会话共用地基」
> **口径优先级**：CROSS-STORY-DECISIONS（地基段）> 本文件 > PRD > UI 稿。本文件写死的表、列、版本号、接口形状，云端实现**不得自行改动**；与代码现状矛盾时按 §0 漂移表处理。

---

## §0 PRD 代码落点核对（dev_1.5.0 @ 9680309cf，2026-11-14）

PRD §2 / §9 按 2026-10-29 的 `dev_1.4.1` / `main` 核对。以下是在 `dev_1.5.0` 上重新核到的**漂移**，story 一律按右列执行：

| # | PRD 说法 | 代码现状（dev_1.5.0） | 本线处置 |
|---|---|---|---|
| Z-1 | `/profile/health` 没有 `petId` 参数，页面总用当前宠物 | 后端 `/pet-profiles/me/health-records` 自 V1.4.0 起已接受 `?petId=`；App 页面用 `effectivePetIdProvider`（当前宠物）取数，路由仍无 `petId` | 不给路由加 `petId`；提醒点开时**先** `currentPetIdProvider.select(petId)` 再导航（PRD §9 写法照旧成立） |
| Z-2 | 新增通知类型 + 重建 `ck_notifications_type` + `PushCategory.of` 归类 | **地基 0.1 已做完**：23 个 `HEALTH_*` 已在 `NotificationType`，约束已由 `V20261114_1000` 重建，`PushCategory.of` → `PET_REMINDER` | 本线**不改** `NotificationType` / `PushCategory` / `ck_notifications_type`，只用 |
| Z-3 | 按设备判版本：旧版第二台设备收不到健康推送 | 推送走腾讯 IM 离线推送，**按用户**下发，无设备维度；地基 `AppVersionGate` 只按 `users.last_app_version`（用户最后一次上报） | 只按用户判（地基已定）。旧版设备的通知中心靠「列表接口按请求头 `X-App-Version` 过滤 `HEALTH_*`」兜住（§6） |
| Z-4 | 推送 payload 带 `notification_id / pet_id / notification_kind / push_point / origin / merged_count` | 腾讯离线推送 ext 只有 `type / token / targetRef` 三个字段（`TencentImClient.pushOffline`、App `parsePushExt`） | 不改 IM 客户端。`targetRef` = 健康通知的不可枚举 `public_token`；App 点开后调 `GET /me/health-notifications/{token}/open` 一次拿到落点与埋点属性（§5.3） |
| Z-5 | 通知中心健康条目走现有 `hasServerCopy` | App `hasServerCopy` 要求有 `actorName`（互动类专用）；读后端存好的标题正文是另一条路径 `_storedTitle / _storedBody`（`OPS_ANNOUNCEMENT`、`PET_SLOT_EXPIRING` 在用） | 健康条目走 `_storedTitle / _storedBody`：后端写通知时**按收件人语言渲染好标题正文落库**，App 原样显示 |
| Z-6 | `sendWithCopy` 只支持 `{0}` 名字 `{1}` 数字 | 属实；且 `sendWithCopy` 落库的是兜底串、App 不读 | 新增专用方法 `NotificationService.sendHealthReminder(...)`（§5.2），渲染在健康模块里完成 |
| Z-7 | `push_result` 区分 sent / muted / no_token / failed | 腾讯推送对「用户没有注册设备」也返回成功，**无法识别无 token**；能识别的只有：分类被关（`pushAllowed`）、虚拟号（`skipPushForVirtual`）、HTTP / ErrorCode 失败（`pushToUserNow` 返回 false） | `muted` = 分类关闭；`no_token` = 虚拟号（无 IM 账号）；`failed` = `pushToUserNow` 返回 false；其余 `sent`。见待拍板 Q-2 |
| Z-8 | 「vet consult」可从既有数据读到 | 会话表 `consult_sessions` 不存宠物；宠物在 `consult_orders.pet_profile_id`；`health_events` 的 `VET_CONSULT` 行只在用户选择存档时才写，不能当「所有已结束问诊」用 | 本线新建 `health_vet_consult_visits`：监听 `ConsultClosedEvent` 写入 + 迁移里从 `consult_orders ⋈ consult_sessions` 一次性回填（§1.4） |
| Z-9 | 绝育状态「空和 Not sure 都存 UNKNOWN」 | `NeuterStatus` 注释明确「null = 没填过，UNKNOWN = 用户说不知道」，FR-107 依赖这个区分 | 保持现状：空存 `null`、「Tidak yakin」存 `UNKNOWN`；**提醒规则里 null 与 UNKNOWN 都按未绝育**。UI「都没选中」= null |
| Z-10 | Vet visit 完成 G-M2 | `MilestoneAutoCompleteMap`：G-M2 只在通用清单（OTHER），猫狗清单没有对应节点 | 新事件 `HEALTH_RECORD_VET_VISIT` → 猫 `null` / 狗 `null` / 通用 `G-M2` |
| Z-11 | 健康记录页 AppBar 两个按钮、商城跳转行 | 属实（`healthStartConsult`、`healthAddTop`、`_categoryJumpRow`） | 按 PRD 删 |
| Z-12 | 生日推送前 1 天 08:00 发 | 属实（`ScheduledPushPlanner`，cron 01:00 UTC）；③ 推送与召回线会改到 11:00 并把纪念日拆到 19:00 | 本线**只调用** `ScheduledPushPlanner.plan(...)` 判定「当天该宠物有没有生日 / 第一个生日推送」，不改它；纪念日只走 `RetentionPushCalendar.anniversaryPushPets`（地基，③ 实现） |
| Z-13 | 后端埋点 | 服务端只有 `AnalyticsClient`（PostHog HTTP）+ `AnalyticsEventGuard` 事件名 / 属性键白名单；App 是 `Analytics.capture`（PostHog SDK 门面）。自建埋点线未合入 `dev_1.5.0` | 两端都只走现有门面；新事件名、新属性键登记白名单。见待拍板 Q-4 |
| Z-14 | 健康记录类型列 `varchar(16)` + CHECK | 属实：`ck_health_records_type`，**只在 `V76__init_health_records.sql` 定义过一次** | 本线迁移 DROP + ADD 重列全集 + `VET_VISIT` |
| Z-15 | 推送出口 | `PushExitStructureTest` 规定 `pushToUser*` 只能出现在白名单「类#方法」里且每处配一道 `!pushAllowed(` | 新方法 `NotificationService#sendHealthReminder` 加进白名单（③ 线也可能加方法，合并时取并集） |

---

## §1 数据模型（迁移全部预分配，**不得另取号**）

本线迁移号段：`V20261115_0100` ~ `V20261115_0159`（日期 20261115，HHMM 0100–0159）。已用：

| 版本 | 文件 | story |
|---|---|---|
| `V20261115_0100` | `V20261115_0100__health_records_reminder_fields.sql` | 1.1 |
| `V20261115_0110` | `V20261115_0110__pet_profiles_health_status_fields.sql` | 1.1 |
| `V20261115_0120` | `V20261115_0120__create_health_reminder_tables.sql` | 2.1 |
| `V20261115_0130` | `V20261115_0130__create_health_vet_consult_visits.sql` | 2.2 |

`0140` ~ `0159` 留作本线修正用（如需新迁移按顺序取 `0140`、`0150`，并回写 sprint-status 头注释）。**禁止**改动 `ck_notifications_type`、`ck_user_push_mutes_category`。

### 1.1 `health_records` 加列（`V20261115_0100`）

```sql
ALTER TABLE health_records
    ADD COLUMN next_date               DATE,
    ADD COLUMN vaccine_preset          VARCHAR(24),
    ADD COLUMN dose                    INTEGER,
    ADD COLUMN deworm_type             VARCHAR(16),
    ADD COLUMN product_duration_months INTEGER,
    ADD COLUMN record_origin           VARCHAR(16) NOT NULL DEFAULT 'BACKFILL';
ALTER TABLE health_records ALTER COLUMN record_origin SET DEFAULT 'APP';

ALTER TABLE health_records DROP CONSTRAINT IF EXISTS ck_health_records_type;
ALTER TABLE health_records ADD CONSTRAINT ck_health_records_type
    CHECK (type IN ('VACCINE', 'DEWORM', 'MENSTRUATION', 'NEUTER', 'CUSTOM', 'VET_VISIT'));
ALTER TABLE health_records ADD CONSTRAINT ck_health_records_vaccine_preset CHECK (vaccine_preset IS NULL OR vaccine_preset IN
    ('F3','F4','RABIES','FELV','PUPPY_DP','COMBO_DHPPI_L','LEPTO','KENNEL_COUGH','CANINE_INFLUENZA','OTHER'));
ALTER TABLE health_records ADD CONSTRAINT ck_health_records_dose CHECK (dose IS NULL OR dose BETWEEN 1 AND 5);
ALTER TABLE health_records ADD CONSTRAINT ck_health_records_deworm_type CHECK (deworm_type IS NULL OR deworm_type IN
    ('INTERNAL','EXTERNAL','FLEA_WORM','HEARTWORM'));
ALTER TABLE health_records ADD CONSTRAINT ck_health_records_duration CHECK (product_duration_months IS NULL OR product_duration_months IN (1,3,8));
ALTER TABLE health_records ADD CONSTRAINT ck_health_records_origin CHECK (record_origin IN ('APP','LEGACY_APP','BACKFILL'));
CREATE INDEX ix_health_records_pet_type ON health_records (pet_profile_id, type, event_date DESC, id DESC);
```

- `record_origin`：存量行一次性落 `BACKFILL`（ADD COLUMN 默认值），之后默认 `APP`；旧版 App（请求头 < 1.5.0）新建的记录写 `LEGACY_APP`。
- `dose` / `product_duration_months` 用 `INTEGER`（**禁 SMALLINT**：实体 `Integer` 会被 Hibernate 映成 int4，validate 全红，V1.1 事故）。
- `vaccine_name` 保留：1.5.0 客户端选预设时**也写** `vaccine_name` = 预设标签（`F4 (Tetracat)` 等，见 §3.1 标签表），选「Lainnya」写自由文本。
- `HealthRecordType` 枚举末尾加 `VET_VISIT`。

### 1.2 `pet_profiles` 加列（`V20261115_0110`）

```sql
ALTER TABLE pet_profiles
    ADD COLUMN cat_in_home      BOOLEAN,
    ADD COLUMN pregnant_nursing BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN pregnant_set_at  TIMESTAMPTZ,
    ADD COLUMN sex_set_at       TIMESTAMPTZ;
```

- `cat_in_home`：只对 DOG 有意义；null = 没填（按「否」处理，不出 permethrin 警示）。
- `pregnant_nursing`：只对 FEMALE 或 sex=null 有意义；sex=MALE 时读取一律当 false。`pregnant_set_at` = 最近一次被设为 true 的时刻；**自动清除**：WIB 日期 ≥ `pregnant_set_at`（WIB）+ 4 个日历月（月末钳制）时，在当天日跑开头视为 00:00 WIB 清除（§4.6）。
- `sex_set_at`：sex 从 null 变为非 null 的时刻（只记第一次）；存量 sex 非空的宠物保持 null（视作「建档时就有」）。用于「性别空着时绝育建议顺延」。
- 存量 `neuter_status` 不动（见 Z-9）。

### 1.3 提醒相关表（`V20261115_0120`）

```sql
CREATE TABLE health_reminder_settings (            -- 单行 id=1：上线开关、上线日、排队暂停
    id               BIGINT PRIMARY KEY CHECK (id = 1),
    enabled          BOOLEAN     NOT NULL DEFAULT FALSE,
    switched_on_at   TIMESTAMPTZ,
    launch_date      DATE,                           -- 开关打开后第一次 10:00 WIB 日跑的日期
    queue_paused     BOOLEAN     NOT NULL DEFAULT FALSE,
    backfill_done_at TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO health_reminder_settings (id) VALUES (1);

CREATE TABLE health_notifications (                -- 一条「通知」= 一次推送 + 一条通知中心条目（可承载多条提醒）
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    public_token          VARCHAR(32)  NOT NULL UNIQUE,   -- 不可枚举，= notifications.target_ref = 埋点 notification_id
    user_id               BIGINT       NOT NULL,
    pet_profile_id        BIGINT       NOT NULL REFERENCES pet_profiles (id) ON DELETE CASCADE,
    notification_kind     VARCHAR(32)  NOT NULL,          -- 附录 C 的 ID（不带 HEALTH_ 前缀）
    push_point            VARCHAR(16)  NOT NULL,
    origin                VARCHAR(16)  NOT NULL,
    merged_count          INTEGER      NOT NULL,
    deferred_days         INTEGER      NOT NULL DEFAULT 0,
    route                 VARCHAR(8)   NOT NULL,          -- FORM / PAGE（发出时决定；安全情形点开时再判）
    push_result           VARCHAR(16)  NOT NULL,          -- SENT / MUTED / NO_TOKEN / FAILED
    inbox_notification_id BIGINT,                         -- notifications.id（不加 FK，通知删除不阻断）
    send_date             DATE         NOT NULL,          -- WIB
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_health_notifications_route CHECK (route IN ('FORM','PAGE')),
    CONSTRAINT ck_health_notifications_result CHECK (push_result IN ('SENT','MUTED','NO_TOKEN','FAILED'))
);
CREATE INDEX ix_health_notifications_user_date ON health_notifications (user_id, send_date);

CREATE TABLE health_reminders (                    -- 一条「提醒」= 某宠物某日的一个计划项（PRD 词表 Reminder）
    id                     BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    pet_profile_id         BIGINT       NOT NULL REFERENCES pet_profiles (id) ON DELETE CASCADE,
    owner_id               BIGINT       NOT NULL,
    source_record_id       BIGINT,                  -- 记录类 / 事件类的来源记录；不加 FK（记录删掉后已发行要留作 SM-7）
    reminder_kind          VARCHAR(32)  NOT NULL,   -- 附录 C 23 个 ID
    push_point             VARCHAR(16)  NOT NULL,   -- D_MINUS_7 / D_MINUS_1 / D_DAY / D_PLUS_3 / D_PLUS_7 / RESUME_CATCH_UP / NONE
    origin                 VARCHAR(16)  NOT NULL,   -- RECORD / PROFILE / EVENT / LAUNCH_QUEUE / RESUME_CATCH_UP
    next_date_ref          DATE,                    -- 该提醒对应的 Next date（记录类）；档案 / 事件类为 null
    due_date               DATE         NOT NULL,   -- 原定日期（去重键的一部分，永不因顺延改变）
    send_date              DATE         NOT NULL,   -- 当前计划发送日（顺延后变化）
    deferred_days          INTEGER      NOT NULL DEFAULT 0,
    tier                   INTEGER      NOT NULL,
    movable                BOOLEAN      NOT NULL,
    due_vaccine_preset     VARCHAR(24),             -- 文案 / 落点快照
    due_vaccine_name       VARCHAR(30),
    due_dose               INTEGER,
    deworm_type            VARCHAR(16),
    custom_name            VARCHAR(20),
    status                 VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    cancel_reason          VARCHAR(24),
    displaced_by           VARCHAR(32),
    health_notification_id BIGINT REFERENCES health_notifications (id) ON DELETE SET NULL,
    sent_at                TIMESTAMPTZ,
    satisfied_at           TIMESTAMPTZ,
    satisfied_by_record_id BIGINT,
    queue_position         INTEGER,                 -- 仅 LAUNCH_QUEUE
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_health_reminders_kind CHECK (reminder_kind IN (
        'VACCINE_D_MINUS_7','VACCINE_D_DAY','VACCINE_D_PLUS_7','FIRST_VACCINE',
        'DEWORM_INTERNAL_D_DAY','DEWORM_EXTERNAL_D_DAY','DEWORM_FLEA_WORM_D_DAY','DEWORM_HEARTWORM_D_DAY',
        'DEWORM_INTERNAL_D_PLUS_3','DEWORM_EXTERNAL_D_PLUS_3','DEWORM_LEGACY_D_DAY','DEWORM_LEGACY_D_PLUS_3',
        'FIRST_DEWORM','VET_VISIT_D_MINUS_1','VET_VISIT_D_DAY','STITCH_CHECK','HEAT_DOG_D_MINUS_7',
        'PYOMETRA_WATCH','HEAT_CAT_SECOND','HEAT_MALE_FIRST','SPAY_NEUTER_SUGGESTION','CHECKUP','CUSTOM_D_DAY')),
    CONSTRAINT ck_health_reminders_point CHECK (push_point IN
        ('D_MINUS_7','D_MINUS_1','D_DAY','D_PLUS_3','D_PLUS_7','RESUME_CATCH_UP','NONE')),
    CONSTRAINT ck_health_reminders_origin CHECK (origin IN ('RECORD','PROFILE','EVENT','LAUNCH_QUEUE','RESUME_CATCH_UP')),
    CONSTRAINT ck_health_reminders_status CHECK (status IN ('PENDING','SENT','CANCELLED')),
    CONSTRAINT ck_health_reminders_reason CHECK (cancel_reason IS NULL OR cancel_reason IN (
        'satisfied','skipped_cap','skipped_scheduled_push','expired_defer','missed_run','record_deleted','rebuilt',
        'neutered','pet_deleted','read_only','paused','catch_up_sent','queue_recheck','old_app'))
);
-- 「最多送一次」：同一 (宠物, 来源记录, 种类, 推送点, 原定日) 只能有一行 PENDING 或 SENT
CREATE UNIQUE INDEX ux_health_reminders_once ON health_reminders
    (pet_profile_id, COALESCE(source_record_id, 0), reminder_kind, push_point, due_date)
    WHERE status IN ('PENDING', 'SENT');
CREATE INDEX ix_health_reminders_send ON health_reminders (send_date, status);
CREATE INDEX ix_health_reminders_pet ON health_reminders (pet_profile_id, status);

CREATE TABLE health_reminder_user_launch (         -- 每个用户自己的「上线日」与排队节奏（FR-164 个人上线）
    user_id               BIGINT PRIMARY KEY,
    launch_date           DATE        NOT NULL,
    queue_start_date      DATE        NOT NULL,   -- launch_date + (user_id mod 7)
    last_queue_sent_date  DATE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- `cancel_reason` 比 PRD §4 多一个 `old_app`：用户当天不满足 1.5.0 门槛时到期行作废用，**不发埋点**（PRD 只要求「旧版不发」）。
- 所有日期列都是 **WIB 日历日**。`health_reminder_user_launch` 不加 FK（注销级联由 `ProfileDeletionService.deleteAllForAccount` 路径显式删，§4.8）。
- 无 JPA 也可（`NamedParameterJdbcTemplate`）；若建实体，列类型一律 `Integer/Boolean/LocalDate/Instant`，与 `ddl-auto=validate` 对齐。

### 1.4 兽医问诊到访记录（`V20261115_0130`）

```sql
CREATE TABLE health_vet_consult_visits (
    session_id     BIGINT PRIMARY KEY,           -- consult_sessions.id（一次会话一行，幂等）
    pet_profile_id BIGINT NOT NULL REFERENCES pet_profiles (id) ON DELETE CASCADE,
    visit_date     DATE   NOT NULL,              -- WIB
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_health_vet_consult_visits_pet ON health_vet_consult_visits (pet_profile_id, visit_date DESC);
-- 一次性回填：已结束（CLOSED）的兽医会话，宠物取该会话最新一张问诊单上的 pet_profile_id
INSERT INTO health_vet_consult_visits (session_id, pet_profile_id, visit_date)
SELECT s.id, o.pet_profile_id, ((COALESCE(s.pending_close_started_at, s.updated_at)) AT TIME ZONE 'Asia/Jakarta')::date
FROM consult_sessions s
JOIN LATERAL (SELECT co.pet_profile_id FROM consult_orders co
              WHERE co.consult_session_id = s.id AND co.pet_profile_id IS NOT NULL
              ORDER BY co.id DESC LIMIT 1) o ON TRUE
WHERE s.status = 'CLOSED'
ON CONFLICT (session_id) DO NOTHING;
```

> ⚠️ 实现时**先核对** `consult_orders` 的列名（`consult_session_id` / `pet_profile_id`）与 `consult_sessions` 的结束时间列（`ConsultSession.terminalAt()` 用的列），以实际列为准改写回填 SQL；`pet_profile_id` 指向已删宠物的行要用 `JOIN pet_profiles` 过滤，避免 FK 失败。运行时由 `ConsultClosedEvent` 监听写入（`petId` 为 null → 不写；`visit_date` = 事件的 `eventDate`）。AI 分诊不写。

---

## §2 模块与包结构

| 包 | 职责 | 依赖方向 |
|---|---|---|
| `com.tailtopia.profile.health`（新） | **纯规则**：日期工具、疫苗系列规则、驱虫间隔、发情周期、疫苗关键词匹配、覆盖组、记录显示状态、表单预填；无 Spring 依赖或只有 `@Component` 包装 | 被 profile 服务与 healthreminder 共用 |
| `com.tailtopia.profile`（改） | 记录 / 档案 CRUD 的新字段、旧版兼容、预填接口、列表显示字段；发布变更事件 | 不依赖 healthreminder |
| `com.tailtopia.healthreminder`（新） | `domain/`（表映射）、`rules/`（提醒生成：记录类 / 事件类 / 档案类 / 体检到期）、`service/`（重建、恢复补发、上线排队、文案、点开解析、埋点）、`schedule/`（日跑 Job / Planner / Dispatcher）、`web/`（点开接口）、`backfill/`（上线回填） | 依赖 profile（读仓储 / 实体 / 规则）、notify（`NotificationService`、`AppVersionGate`、`RetentionPushCalendar`、`ScheduledPushPlanner`） |
| `com.tailtopia.admin.stag`（或现有 stag 控制器所在包） | `@StagOnly` QA 端点 | — |

- 规则类全部**纯函数**（入参快照、出参列表），PRD 的 AC 全部能写成 L0 单测。
- 宠物物种判断一律 `petType == CAT` / `== DOG`，**其他全部按 OTHER 规则**（PRD FR-158「新物种」规则；④ 物种线会加新的 `PetType` 值）。禁止对 `PetType` 写不带 default 的穷尽 switch。
- 所有「今天」= `LocalDate.now(ScheduleWindow.WIB)`；所有 `+N 个月` = `plusMonths(N)`（Java 自带月末钳制），永远从记录自己的 event date 起算。

---

## §3 记录侧规则（profile.health）

### 3.1 预设与标签

| `vaccine_preset` | 物种 | 系列长度 young / adult | 单剂 | 写入 `vaccine_name` 的标签 |
|---|---|---|---|---|
| `F3` | CAT | 3 / 2 | | `F3 (Tricat)` |
| `F4` | CAT | 3 / 2 | | `F4 (Tetracat)` |
| `RABIES` | CAT、DOG | — | ✓ | `Rabies` |
| `FELV` | CAT | 2 / 2 | | `FeLV` |
| `PUPPY_DP` | DOG | 2 / 2 | | `Puppy DP` |
| `COMBO_DHPPI_L` | DOG | 3（有 Puppy DP 记录则 2）/ 2 | | `Combo DHPPi+L` |
| `LEPTO` | DOG | 2 / 2 | | `Leptospirosis` |
| `KENNEL_COUGH` | DOG | — | ✓ | `Kennel Cough` |
| `CANINE_INFLUENZA` | DOG | — | ✓ | `Canine influenza` |
| `OTHER` | CAT、DOG | — | — | 用户自由文本（≤30） |

OTHER 物种：不存预设（`vaccine_preset = null`），只有自由文本。

### 3.2 Next date 预填（`NextDateRules`，FR-158/159/160/163/171，附录 B.1）

- **疫苗**（age = event date 时的年龄；young = 未满 6 个月；adult = ≥6 个月或无生日）按 PRD FR-159 表**自上而下第一条命中**：单剂 → +1 年；Puppy DP 剂次 2 或空（>2 当 2）→ +3 周，到期疫苗 = Combo 第 1 剂；剂次 < 系列长度 → +4 周；剂次 ≥ 系列长度 → +1 年；空剂次 young <16 周 → +4 周；空剂次 young ≥16 周 → +1 年；空剂次 adult → +1 年。`OTHER` 预设无预填。输出同时给**到期疫苗 / 到期剂次**（+4 周分支 = 同预设、剂次+1，空剂次则空；+1 年分支 / 单剂 / OTHER = 同预设、空剂次；DP → Combo 第 1 剂）。
- **驱虫**：INTERNAL 按 event date 年龄 <8 周 +14 天 / 8 周–6 个月 +1 个月 / ≥6 个月或无生日 +3 个月；EXTERNAL = +`product_duration_months`（默认 1）；FLEA_WORM +1 个月；HEARTWORM +1 个月；未分型（Legacy）+3 个月。
- **发情**：只给 DOG + FEMALE + 未绝育（NEUTERED 以外）且为**本次发情的第一条**：前 3 次发情（只算各次第一天）的间隔，去掉 <120 天的，均值加到最近一次发情；没有可用间隔 → +6 个月；结果钳在 4–12 个月。同一次发情的后续记录：不存 next_date，表单只读显示本次第一天的 Next date。
- **发情周期归属**（`HeatEpisodes`）：按 event date 升序，第一条开一个周期；与本周期第一条相差 < 10 天（CAT）/ < 21 天（DOG）的归入；否则开新周期（不链式延长）。OTHER 物种按 21 天。
- **Vet visit / Custom / 疫苗 OTHER**：无预填。**NEUTER**：无 Next date。
- OTHER 物种：任何类型都不预填，字段照有（Heat 除外按上条）。
- 合法范围：`eventDate < nextDate ≤ eventDate + 3 年`；Event date 仍 `@PastOrPresent`。

### 3.3 覆盖组与「最新记录」（`CoverageGroups`）

- 记录种类（kind）：疫苗 = 预设（OTHER 预设 / OTHER 物种 = 规范化名字；规范化 = trim + 小写 + 空白折叠；与某预设标签规范化后相同的「其他」名字算该预设）；驱虫 = deworm_type（null = `LEGACY`）；Custom = 规范化 custom_name；其他类型 = 类型本身。
- 覆盖组：`{F3, F4}` 合一组；其余每个 kind 自成一组；**Vet visit 每条记录各自一组**。
- 最新记录：组内 event date 最大，同日按 `created_at`（再按 id）最大。
- 「覆盖」（单向，状态）：Combo 覆盖 Puppy DP；FLEA_WORM 覆盖 INTERNAL 与 EXTERNAL；同一宠物 INTERNAL 与 EXTERNAL event date 相差 ≤3 天（成对，对的日期 = 两者较晚者）覆盖 FLEA_WORM；任意已分型驱虫覆盖 LEGACY。组 G 处于「被覆盖」⇔ 存在覆盖它的记录（或成对）其 event date ≥ G 最新记录的 event date。
- Vet visit：一条 Vet visit 的 Next date 在「存在另一条 Vet visit 的 event date ≥ 它的 Next date − 1 天」时视为已满足。

### 3.4 列表显示状态（`RecordDisplayStatus`，服务端算，FR-158）

对每条记录输出 `displayNextDate`（要显示的 Next date，可空）与 `recordStatus`：

- 不是组内最新、组被覆盖、Vet visit 已满足、无 Next date → `DONE`、`displayNextDate=null`。
- **回填静默**：`record_origin = BACKFILL` 且 `next_date < effectiveLaunchDate` → `DONE`、不显示（`effectiveLaunchDate` = settings.launch_date，未上线前 = 今天 WIB）。
- 发情：同一周期后续记录显示本周期第一条的 Next date（规则同上）；**发情永不显示 OVERDUE**（过了日期 → `DONE`）。
- 否则：`nextDate < today` → `OVERDUE`；`today ≤ nextDate ≤ today+7` → `DUE_SOON`；更晚 → `UPCOMING`。
- 问诊只读行 → `DONE`。

### 3.5 存量疫苗名关键词匹配（`VaccineKeywordMatcher`，FR-168）

按物种过滤（CAT 只认猫预设、DOG 只认狗预设），大小写不敏感，按「词」切（非字母数字分隔）：整词 `tricat|f3` → F3、`tetracat|f4` → F4、`rabies` → RABIES、`felv` → FELV、`kennel` → KENNEL_COUGH；词以 `lepto` 开头 → LEPTO；词以 `dhpp` 开头 → COMBO_DHPPI_L。有 Combo 时丢弃 LEPTO。剩余命中恰好 1 个 → 该预设；0 个或 ≥2 个 → `OTHER`（保留原文，不出提醒）。用于：上线回填、旧版客户端新建 / 改名。`vaccine_name` 原文不动。

---

## §4 提醒引擎（healthreminder）

### 4.1 提醒目录（`ReminderKind` 枚举，附录 C 原样）

枚举字段：`tier`、`movable`、`route`（FORM / FORM_DATE_EMPTY / PAGE）、`catalogueOrder`（=附录 C 行序）、`notificationType()` = `NotificationType.valueOf("HEALTH_" + name())`。档位：PYOMETRA_WATCH 1；VET_VISIT_D_MINUS_1 / VET_VISIT_D_DAY / CUSTOM_D_DAY 2；VACCINE_* 3；DEWORM_* 4；STITCH_CHECK 5；HEAT_DOG_D_MINUS_7 6；CHECKUP 7；FIRST_VACCINE / FIRST_DEWORM / SPAY_NEUTER_SUGGESTION / HEAT_CAT_SECOND / HEAT_MALE_FIRST 8。不可移动：所有 D_DAY、D_MINUS_1；PYOMETRA_WATCH「永远发」。其余可移动。

### 4.2 「下一次日跑」

`nextRunDate(now)` = 若 `now`（WIB）< 当天 10:00 → 当天，否则次日。任何「只保留未来的点」= 只保留 `send_date ≥ nextRunDate(now)`；回溯录入时早于它的推送点不生成（FR-163 back-dated）。

### 4.3 全量重建（`HealthReminderRebuilder.rebuildPet(petId, trigger)`）

**粒度是「整只宠物」**：每次触发都按当前记录 + 档案 + 已发历史，用纯规则算出该宠物**全部应有的未来提醒**，再与库里该宠物的 `PENDING`（且 `origin` 不是 `LAUNCH_QUEUE` / `RESUME_CATCH_UP`）行对账：

1. 期望集合中键相同的已有行 → 保留（**保留顺延状态 send_date / deferred_days**）。
2. 已有行不在期望集合 → `CANCELLED`，原因：组里出现更新记录或被覆盖 → `satisfied`（`by_record_id` = 那条记录）；被删记录引起 → `record_deleted`；因 neuter_status=NEUTERED → `neutered`；宠物只读 → `read_only`；其余 → `rebuilt`。
3. 期望集合里的新键 → 插入 `PENDING`；若同键已有 `SENT` 行（唯一索引）→ 跳过（最多送一次）。
4. 已发（`SENT`）且 `satisfied_at` 为空的行，若其组现在有更新 / 覆盖记录 → 写 `satisfied_at = now`、`satisfied_by_record_id`（SM-7）。

与 PRD「按覆盖组重建」在可观察行为上等价（覆盖是状态而非一次性取消，PRD 词表），且更不容易漏组。**仅改备注**的编辑**不触发**重建（保持 PRD 字面）。

期望集合的生成（`ReminderPlanRules`，纯函数）：

- **记录类**（每个覆盖组的最新记录，组未被覆盖、有 next_date、不是回填静默）：按附录 C 推送点生成（疫苗 D-7 / D-Day / D+7；驱虫 D-Day / D+3（INTERNAL、HEARTWORM、LEGACY → `*_INTERNAL_D_PLUS_3` / `DEWORM_LEGACY_D_PLUS_3`；EXTERNAL、FLEA_WORM → `DEWORM_EXTERNAL_D_PLUS_3`）；Vet visit 每条 D-1 / D-Day（未满足时）；Custom D-Day；母狗发情周期首条 D-7 → `HEAT_DOG_D_MINUS_7`）。种类快照写入 `due_vaccine_*`、`deworm_type`、`custom_name`。
- **疫苗恢复已用**：某 (记录, next_date_ref) 已有 `RESUME_CATCH_UP` 行（非 rebuilt 取消）→ 该 Next date 不再生成任何点。
- **事件类**（只看 `record_origin <> 'BACKFILL'` 的记录）：
  - NEUTER（任意物种为 CAT/DOG）：`STITCH_CHECK` due = event+10，未来才有。
  - DOG + FEMALE + 未绝育：每个发情周期首条 → `PYOMETRA_WATCH` due = event+42。
  - CAT + FEMALE + 未绝育：某周期首条与上一周期首条间隔 ≤ 60 天、且首条 event date ≥ nextRunDate−7、且 6 个月内没有已发 `HEAT_CAT_SECOND`、且上一周期也非回填 → `HEAT_CAT_SECOND` due = 该记录保存时的 nextRunDate（存成 due_date，保证幂等）。
  - MALE + 未绝育：第一个发情周期（且宠物**没有**任何 BACKFILL 发情记录）首条 event ≥ nextRunDate−7、从未发过 → `HEAT_MALE_FIRST`。
  - sex 为 null：无任何发情相关提醒。已发过的一次性提醒保持 SENT，不重发。
- **档案类**：见 §4.5（同一入口一并生成）。
- **全局过滤**：用户无 `health_reminder_user_launch` 行（从未被视为 1.5.0 用户）→ 不生成档案类（记录类照生成，日跑时按门槛处理）；宠物 READ_ONLY → 期望集合为空；neuter_status = NEUTERED → 去掉发情 D-7、PYOMETRA_WATCH、SPAY_NEUTER_SUGGESTION、HEAT_*；OTHER 物种 → 只保留记录类点（OTHER 永不预填、回填也不写 next_date，所以凡有 next_date 都是用户手填的）；无事件类、无发情、无档案类。

**触发点**（全部 `@TransactionalEventListener(AFTER_COMMIT)` + 另一个 bean 上的 `@Transactional(REQUIRES_NEW)`，可加 `@Async`）：

| 事件（新增在 profile 包，过去式命名） | 发布处 |
|---|---|
| `HealthRecordChangedEvent(ownerId, petId, recordId, change=CREATED/UPDATED/DELETED, reminderRelevant)` | `HealthRecordService` create / update / delete（update 时 `reminderRelevant` = 除 note 外任一字段变了） |
| `PetHealthProfileChangedEvent(ownerId, petId, birthdayChanged, sexChanged, neuterChanged, pregnantSetYes, pregnantCleared, clearedAt)` | `ProfileService.update`（及创建） |
| `ProfileCreatedEvent`（已有） | 建档 |
| `ConsultClosedEvent`（已有） | 写 `health_vet_consult_visits` 后重建该宠物 |
| `PetStatusChangedEvent(ownerId, petId, newStatus)`（新增） | `PetSlotStateService` 中 `markActive()` / `markReadOnly()` **实际改变状态**处 |

`pregnantCleared` 不走普通重建，走 §4.6 恢复表。`HealthRecordCreatedEvent`（里程碑）保持原样另发，**上线回填不发任何事件**。

### 4.4 体检到期（`CheckupDueRule`，FR-164 原文算法）

1. base = 最近一次「到访」：max(该宠物 `VET_VISIT` 记录 event_date, `health_vet_consult_visits.visit_date`)；若上次**已发** CHECKUP 的 send_date 晚于它 → 用该 send_date；都没有 → 建档日（WIB）。
2. 无生日 → due = base + 12 个月。
3. base 当天已 senior（CAT ≥10 岁、DOG ≥7 岁）→ due = base + 6 个月。
4. 否则 due = max(base + 6 个月, min(base + 12 个月, senior 生日))。
5. 文案：due 当天 senior → senior 文案，否则 adult。区间 (base, due] 有到访 → 不发（自然由 base 推进实现）。

### 4.5 档案类建议（FR-164，仅 CAT/DOG）

| 种类 | normal date | 跳过 |
|---|---|---|
| `FIRST_VACCINE` | 生日 + 42 天；若建档时已 6 周–6 个月 → 建档后的 nextRunDate | 有任何 VACCINE 记录、无生日、发送日已满 6 个月 |
| `FIRST_DEWORM` | 建档日 + 7 天 | 有任何 DEWORM 记录 |
| `SPAY_NEUTER_SUGGESTION` | 触发日 = 生日 + 5 个月（CAT）/ 6 个月（DOG）；建档时已过触发日 → 建档日 + 14 天；sex 为 null 时 = max(上述, `sex_set_at` 之后的 nextRunDate) | NEUTERED、有 NEUTER 记录、无生日、发送日已满 12 个月、sex 仍为 null |
| `CHECKUP` | §4.4 | 见 §4.4 |

- 一次性：已有 SENT（任一 origin）→ 不再生成；CHECKUP 每周期一次（键含 due_date）。
- **与上线排队的分界**：normal date < 该用户 `launch_date` 的建议**不**由此生成（归 §4.7 排队）；≥ launch_date 的照此生成。
- 发送日再校验（日跑时）：跳过条件成立 → `CANCELLED satisfied`（有记录）或 `rebuilt`（超龄）。

### 4.6 怀孕 / 哺乳

- **暂停**（日跑当天 `pregnant_nursing=true`）：`VACCINE_*` → `CANCELLED paused`；`PYOMETRA_WATCH`、`HEAT_DOG_D_MINUS_7` → `CANCELLED paused`；`FIRST_VACCINE` → **保持 PENDING、send_date 顺延 1 天、不计入 deferred_days**（「挂起」）；驱虫照发。
- **清除**（用户改为否，或自动清除）：R = `nextRunDate(清除时刻)`（自动清除时刻 = 当天 00:00 WIB → R = 当天）。对每个**疫苗覆盖组的最新记录** N = next_date：
  - R ≤ N → 普通重建（只保留未来点；错过的 D-7 不补）。
  - N+1 ≤ R ≤ N+14 → 插入一行 `VACCINE_D_PLUS_7`、`push_point=RESUME_CATCH_UP`、`origin=RESUME_CATCH_UP`、`due_date=send_date=R`、`next_date_ref=N`、可移动；同时把该 N 其余 PENDING 点（D+7）`CANCELLED catch_up_sent`。
  - R > N+14 → 什么都不发。
  - 挂起中的 FIRST_VACCINE：下一次日跑再校验（未满 6 个月且无疫苗记录才发，否则 `rebuilt` 作废）。
- 文案 `{hari}` = 实际发送日 − N（顺延后按实际）。补发被丢弃后什么都不补，D+7 保持已取消。

### 4.7 上线与排队（FR-164「Launch rules」、§0 Launch）

- **部署时**：`HealthLaunchBackfillRunner`（`ApplicationRunner`，`settings.backfill_done_at` 为空才跑，一个事务内分批）：对 `record_origin='BACKFILL'` 的记录补 `vaccine_preset`（§3.5）、`next_date`（§3.2；空剂次按规则）、绝育记录 → `neuter_status=NEUTERED`；不走用户 API、不限流、不发事件；完成写 `backfill_done_at`。Legacy 驱虫保持 `deworm_type=null`。
- **开关**（无后台页，PRD §6.1）：运维 SQL `UPDATE health_reminder_settings SET enabled=true, switched_on_at=now() WHERE id=1;`（runbook 写在 story 2.2）。打开后**第一次日跑**：写 `launch_date = 当天`；为所有「最后上报 ≥1.5.0」的用户建 `health_reminder_user_launch(launch_date, queue_start_date = launch_date + user_id mod 7)` 与排队行；全量重建所有活跃宠物（记录类）。
- **个人上线**：此后每次日跑，对新出现在 ≥1.5.0 名单、且没有 launch 行的用户同样建行（`launch_date` = 当天）并排队。
- **排队内容**（normal date < 该用户 launch_date 且当下仍满足条件）：FIRST_VACCINE（6 周–6 个月且无疫苗记录）> SPAY_NEUTER（未满 12 个月、已过触发日、sex 已填、未绝育）> FIRST_DEWORM（无驱虫记录）> CHECKUP（due < launch_date）；同类按宠物建档先后。行：`origin=LAUNCH_QUEUE`、`queue_position`、`due_date=launch_date`、`send_date=queue_start_date`、`push_point=NONE`、tier 按种类。
- **排队出队**（每次日跑、每个用户、在该用户所有宠物的常规提醒处理**之后**）：`queue_paused=false`、今天 ≥ queue_start_date、`last_queue_sent_date` 为空或 ≤ 今天−3、**该用户今天没有任何推送**（本次日跑已发的健康通知、任一宠物的生日 / 第一个生日推送、任一宠物的纪念日推送、召回日）→ 取最小 `queue_position`，发送日再校验（跳过条件成立或 FIRST_VACCINE 遇怀孕 → `CANCELLED queue_recheck`，继续下一条），发出一条后写 `last_queue_sent_date`。排队行**永不过期、不受 3 天顺延上限**；未出队的行每天把 `send_date` 改成次日。排队发出的 CHECKUP 以发送日为下一周期 base。

### 4.8 宠物 / 账号删除、只读

- `ProfileDeletionService.deletePetRows` 在删宠物前调用 `HealthReminderCleanup.deleteForPet(petId)`：PENDING 行逐条发 `health_reminder_cancelled reason=pet_deleted` 埋点后删除该宠物的 reminders / notifications / vet visits 行；`deleteAllForAccount` 额外删 `health_reminder_user_launch`。
- 只读：重建得到空集（`read_only`）；恢复 ACTIVE（续租）→ `PetStatusChangedEvent` 重建，只保留未来点。

---

## §5 日跑、投递、点开

### 5.1 日跑（`HealthReminderJob`）

- `@Scheduled(cron = "${petgo.health-reminder.cron:0 0 3 * * *}", zone = "UTC")` = 10:00 WIB；`D = LocalDate.now(ScheduleWindow.WIB)`。`settings.enabled=false` → 只记日志返回。
- 同日重跑安全：只处理 `status=PENDING AND send_date = D`；一切写入幂等；失败的宠物不影响其他宠物（每宠物一个 `REQUIRES_NEW` 事务）。
- 步骤：① 自动清除到期的怀孕标记（§4.6）；② 首跑写 launch_date / 新的个人上线建行与排队；③ 把 `PENDING AND send_date < D` 的非排队行作废 `missed_run`（用户 ≥1.5.0 才发埋点）；④ 取 D 当天待发行，按 owner 分组，`AppVersionGate.usersOnV150OrLater` 一次批量；不满足的 → `CANCELLED old_app`（无埋点）；⑤ 一次批量取占用：生日 / 第一个生日（`ScheduledPushPlanner.plan(D, 快照, Set.of())` 中类型为 `PET_BIRTHDAY` / `MILESTONE_NODE` 的宠物）、纪念日（`RetentionPushCalendar.anniversaryPushPets(petIds, D)`）、召回日（`RetentionPushCalendar.recallNodeUsers(userIds, D)`）；⑥ 每宠物 `HealthReminderPlanner.decide(...)`（纯函数）；⑦ Dispatcher 投递；⑧ 排队出队。
- QA：`@StagOnly` `POST /admin/stag/health-reminders/run?date=yyyy-MM-dd`（同步跑一次，返回各计数）与 `POST /admin/stag/health-reminders/rebuild?petId=`；L1 测试直接调 Job 的 `runFor(LocalDate)`。

### 5.2 每宠物决策（`HealthReminderPlanner.decide`，纯函数，FR-166）

输入：当天该宠物 PENDING 行、宠物快照（物种、性别、绝育、怀孕、只读）、占用（birthdayOrFirstBirthday、anniversary、recallDay）、该宠物未来 3 天已有 PENDING 的日期集合。输出：发送组列表 + 每行处置（取消原因 / 顺延到哪天）。

1. 只读 → 全部 `read_only`；怀孕处置 §4.6；档案类发送日再校验。
2. **同类同推送点合并**：疫苗 / 驱虫 / Vet visit / Custom 内，`push_point` 相同的合并为一组（不同推送点**永不合并**）。
3. 档 1–2：全部发送（不占名额、不受生日 / 纪念日影响）。
4. 档 3–8：若当天有生日 / 第一个生日 / 纪念日占用 → 不可移动的 `skipped_scheduled_push`（`displaced_by=scheduled_push`），可移动的顺延；档 8 另在召回日顺延。否则选**一个**赢家组：档位小者胜 → 同档不可移动者胜 → `next_date_ref`（无则 due_date）早者胜 → 目录序。输家：不可移动 → `skipped_cap`（`displaced_by` = 赢家 kind）；可移动 → 顺延。
5. 顺延：从 D+1 起找第一个「该宠物没有 PENDING 行、且非生日 / 纪念日占用（档 8 再加非召回日）」的日子，`send_date − due_date ≤ 3` 内找不到 → `expired_defer`（`displaced_by` 同上）。后来有新提醒落到该日 → 当日正常比较，输了可再顺延（仍受 3 天上限）。

### 5.3 投递与文案（`HealthReminderDispatcher` + `HealthCopyRenderer`）

- 每个发送组：生成 `public_token`（32 位 base62，`SecureRandom`），插 `health_notifications`；渲染标题正文；调用新方法：

```java
// NotificationService（加进 PushExitStructureTest 白名单："NotificationService#sendHealthReminder"）
@Transactional(propagation = Propagation.REQUIRES_NEW)
public HealthPushOutcome sendHealthReminder(long recipientUserId, NotificationType type,
        String title, String body, String targetRef)
// 落库 notifications(type, title, body = 已渲染文案, deepLinkType = type.name(), targetRef = public_token)
// + 角标；虚拟号 → NO_TOKEN；!pushAllowed → MUTED；pusher.pushToUserNow(...) false → FAILED；否则 SENT
// 返回 (notificationId, pushResult)
```

- 行状态：`SENT`、`sent_at`、`health_notification_id`；通知行回写 `push_result`、`inbox_notification_id`、`deferred_days` = 组内最大。
- `notification_kind`：单条 = 自身 kind；疫苗合并 = 共同 kind；驱虫 D-Day 合并含 EXTERNAL 或 FLEA_WORM → `DEWORM_FLEA_WORM_D_DAY`，否则 `DEWORM_INTERNAL_D_DAY`；驱虫 D+3 合并含 EXTERNAL/FLEA_WORM → `DEWORM_EXTERNAL_D_PLUS_3`，否则 `DEWORM_INTERNAL_D_PLUS_3`；`NotificationType` = `HEALTH_` + notification_kind。
- `route`：合并（merged_count>1）→ PAGE；否则按 kind 的 route；安全情形在点开时再判（§5.4）。
- **文案**（PRD 附录 A 原文、附录 C 选行规则、FR-169 规则）：i18n 键 `notify.health.<ROW>.title/body`，ROW 取下表；语言按 `accountQuery.localeOf(userId)`（`en` → EN，其余 → ID）。带参数走 `MessageFormat` → 串里单引号写 `''`；EN 计数用 choice（`{n,choice,1#1 day|1<{n} days}`），ID 不变。

| ROW | 用于 |
|---|---|
| `vaccineDMinus7` / `vaccineDMinus7Rabies` | VACCINE_D_MINUS_7（RABIES 单条用后者；合并一律前者，`{vaksin}` 用「 & 」连接） |
| `vaccineDDay` / `vaccineDDaySeries` | VACCINE_D_DAY（单条且有到期剂次 → Series；合并一律 generic） |
| `vaccineDPlus7` | VACCINE_D_PLUS_7、恢复补发 |
| `firstVaccine` / `firstDeworm` | 档案建议 |
| `dewormInternalDDay` / `dewormInternalDDayYoung` | INTERNAL D-Day（到期日早于满 6 个月 → Young，`{kucing/anjing}` 按物种；非猫狗用 generic） |
| `dewormExternalDDay` / `dewormFleaWormDDay` / `dewormHeartwormDDay` | 对应 D-Day（驱虫合并见上） |
| `dewormDPlus3Internal` / `dewormDPlus3External` / `dewormDPlus3ExternalDog` | D+3（EXTERNAL/FLEA_WORM：DOG 用 Dog 行，其余用 External 行；INTERNAL / HEARTWORM / LEGACY 用 Internal 行） |
| `dewormLegacyDDay` | DEWORM_LEGACY_D_DAY |
| `spayFemale` / `neuterMale` | SPAY_NEUTER_SUGGESTION 按性别 |
| `stitchCheck` / `heatDogDMinus7` / `pyometraWatch` / `heatCatSecond` / `heatMaleFirst` | 事件类 |
| `checkupAdult` / `checkupSenior` | CHECKUP |
| `vetVisitDMinus1` / `vetVisitDDay` / `customDDay` | — |

- 占位符：`{nama}` 宠物名（缺 → `hewanmu` / `your pet`）；`{vaksin}` 到期疫苗名（预设标签或原文）；`{dosis}`；`{hari}` = 发送日 − Next date（合并取最大）；`{hari_lagi}` = Next date − 发送日（合并取最小）；`{interval}` = 最新记录 event date → 发送日，<8 周按整周（向下取整、最小 1）否则按四舍五入整月，作为「数字 + 单位」两个参数传；`{judul}` custom 名。用户文本去换行与控制字符、保持字段上限。缺值 → 用不含该占位符的变体。
- 落库与推送同一句（OQ-1 豁免：健康提醒可含健康数据明文）；**日志仍禁记**宠物名 / 文案 / 健康字段。

### 5.4 点开接口（FR-167）

`GET /api/v1/me/health-notifications/{token}/open?source=push|inbox`（JWT；token 不属于本人 → 404；同时把对应通知中心条目标已读，复用现有已读逻辑）。

```json
{
  "status": "OK | PET_UNAVAILABLE",
  "petId": 123,
  "route": "FORM | PAGE | PAGE_SATISFIED",
  "focusRecordId": 456,
  "prefill": { "type": "VACCINE", "vaccinePreset": "F4", "vaccineName": "F4 (Tetracat)", "dose": 2,
               "dewormType": null, "customName": null, "eventDateEmpty": false },
  "safetyBoxes": ["PREGNANT", "PERMETHRIN"],
  "analytics": { "notificationId": "<token>", "notificationKind": "VACCINE_D_DAY", "pushPoint": "d_day",
                 "origin": "record", "mergedCount": 1 }
}
```

判定顺序：宠物已删 / 只读 / 非本人 → `PET_UNAVAILABLE`（App 落通知中心 + toast）；承载的提醒全部已满足（`satisfied_at` 非空，或其组现在有比该提醒 `next_date_ref` 期间更新的记录）→ `PAGE_SATISFIED` + `focusRecordId` = 那条更新记录；驱虫类（任何 `DEWORM_*`、`FIRST_DEWORM`、含驱虫的合并）且（当前怀孕 → `PREGNANT`；DOG + `cat_in_home=true` 且类型为 EXTERNAL / FLEA_WORM / 未知（LEGACY、FIRST_DEWORM）或合并含其一 → `PERMETHRIN`）→ `PAGE` + safetyBoxes（怀孕在前）；否则按 `health_notifications.route`：FORM 带 `prefill`（D-Day / D+3 / D+7 / 补发 = 今天日期、到期疫苗 / 剂次 / 驱虫类型 / custom 名；建议类与 CHECKUP `eventDateEmpty=true`，CHECKUP 的 type = VET_VISIT，FIRST_DEWORM 不带 dewormType，SPAY_NEUTER → NEUTER，LEGACY → DEWORM 无类型），PAGE（D-7、D-1、事件类、合并）带 `focusRecordId`（合并不带）。`analytics` 里的枚举值一律小写蛇形（与 PRD §4 一致）。

---

## §6 API 契约与旧版兼容

所有新字段都是**可选、增量**；Jackson 全局 NON_NULL。客户端版本由 `@RequestHeader(ClientAppVersion.HEADER)` → `ClientAppVersion.isV150OrLater`。

### 6.1 健康记录

- `HealthRecordCreateRequest` / `HealthRecordUpdateRequest` 末尾追加：`vaccinePreset`、`dose`（1–5）、`dewormType`、`productDurationMonths`（1/3/8）、`nextDate`、`clearNextDate`（Boolean，仿 `PetProfileUpdateRequest.clearWeight`）。`type` 允许 `VET_VISIT`。
- **1.5.0 客户端**（请求头 ≥1.5.0）：DEWORM 必须带 `dewormType`（含编辑旧的 Legacy 记录，哪怕只改备注）→ 否则 400 `validation`；`nextDate` 校验 `eventDate < nextDate ≤ eventDate+3年`；`nextDate=null` 且 `clearNextDate!=true` → 不改（编辑）/ 不设（新建，App 已把预填值放在 `nextDate` 里发上来）；`vaccine_name` 由客户端写标签。
- **旧版客户端**（无头或 <1.5.0）：新建 → `record_origin=LEGACY_APP`；DEWORM 无类型 = Legacy；疫苗名走 §3.5 关键词匹配；按 §3.2 写默认 next_date。编辑 → 新字段一律保留原值；若改了 `vaccineName` → 重新匹配预设、`dose=null`；若新 eventDate 使 next_date ≤ eventDate → 重算默认值。拒绝旧版写入 `VET_VISIT`（它本来也发不出）。
- `GET` 列表（`HealthListItemResponse` 追加）：`vaccinePreset`、`dose`、`dewormType`、`productDurationMonths`、`nextDate`（存储值，编辑表单用）、`displayNextDate`、`recordStatus`（OVERDUE / DUE_SOON / UPCOMING / DONE）、`nextDateLocked`（发情周期后续记录 = true）。旧版客户端：**过滤掉 `type=VET_VISIT`**。
- 新增 `POST /api/v1/pet-profiles/me/health-records/preview?petId=`（只读、限流 60 次 / 分钟）：入参 `{recordId?, type, vaccinePreset?, vaccineName?, dose?, dewormType?, productDurationMonths?, eventDate?}` → 出参 `{suggestedNextDate, nextDateAllowed, nextDateLocked, lockedNextDate, nextDateMin, nextDateMax, dueVaccinePreset, dueDose, hints: [...]}`。`hints` 取值：`VACCINE_PREGNANT`、`RABIES_YEARLY`、`DEWORM_UNDER_8_WEEKS`（<8 周 + EXTERNAL/FLEA_WORM/HEARTWORM）、`DEWORM_PREGNANT`、`PERMETHRIN`（DOG + 家里有猫 + EXTERNAL/FLEA_WORM）、`HEARTWORM_TEST`（DOG >6 个月 + 无 HEARTWORM 记录）、`HEAT_NEUTERED`。**App 不复制规则**，预填与提示全由该接口给出。

### 6.2 宠物档案

- `PetProfileUpdateRequest` 追加 `catInHome`（Boolean）、`pregnantNursing`（Boolean）；`neuterStatus` 已有。`PetProfileResponse` 追加 `catInHome`、`pregnantNursing`、`pregnantAutoOffDate`（`pregnant_set_at` + 4 个月的 WIB 日期，否则 null）。
- 设为 true（含从 true 再设 true）刷新 `pregnant_set_at`；sex=MALE 时写入忽略。sex 首次从 null 变非 null → 写 `sex_set_at`。
- 保存 NEUTER 记录（新建，或改成 NEUTER 类型）→ `neuter_status=NEUTERED`（同一事务）；删 / 改该记录不回退。

### 6.3 问诊历史（FR-161）

`GET /api/v1/consult/history`：仅 1.5.0 客户端追加第三种 `type=VET_VISIT` 条目，字段 `recordId`、`petId`、`petName`、`eventDate`、`note`、`nextDate`（= displayNextDate）、`overdue`、`recordStatus`；`date`（排序与游标用 Instant）= `eventDate` 当天的 WIB 时刻取 `created_at` 的 WIB 时分秒。来源 = 该账号全部宠物（宠物切换器里的宠物，含只读）的 VET_VISIT 记录，与 AI / VET 同一游标合并。旧版客户端不返回该类型。

### 6.4 通知中心

`NotificationCenterService` 列表：请求头 <1.5.0 → 过滤掉 `type LIKE 'HEALTH_%'`（未读角标不变，旧版本就收不到这些）。

### 6.5 Diary 时间线 / 日历

不过滤 `VET_VISIT`（PRD 未要求）；旧版 App 对未知类型走图标兜底。4.1 联调用 1.4.2 包验证显示（见待拍板 Q-6）。

---

## §7 App 侧决定

- **不复制业务规则**：预填 / 提示 / 状态胶囊 / 覆盖 / 回填静默一律读后端（§6.1）；App 只按 `recordStatus` 选胶囊样式（OVERDUE 红 `coralTint`+`healthEventText`；DUE_SOON 金 `goldTint`+`tipsBadgeText`；UPCOMING 紫 `violet100`+`mint700`；DONE 无胶囊、类型灰、标题常规字重次级色、图标淡化）。日期显示：本年不写年，跨年写年（`intl`，`id` / `en` 本地化月份）。
- 新物种：App 判断物种只认 `CAT` / `DOG`，其余一律走 OTHER 分支（疫苗只给自由文本、驱虫无 Heartworm、档案页三项不显示）。
- 推送点开：`kPushDirectTypes` 追加 23 个 `HEALTH_*`（**只加自己的值**，③⑥ 也在加）；`pushPayloadToLocation` 对 `HEALTH_` 前缀返回 `/health-reminder/open?t=<targetRef>&src=push|inbox`；新的轻量路由页 `HealthReminderOpenPage` 调 §5.4 接口 → `currentPetIdProvider.select(petId)`（`pet_switched source=health_reminder`，同一只不报）→ `go`/`push` 到 `/profile/health?add=..&preset=..&dose=..&deworm=..&custom=..&dateEmpty=1&focus=..&safety=PREGNANT,PERMETHRIN`；`PET_UNAVAILABLE` → 通知中心 + toast「Hewan ini sudah tidak tersedia.」；接口失败 → 通知中心。
- **表单有未保存内容时点提醒**：全局 `healthFormDirtyProvider`；推送点击与通知中心点击在导航**之前**检查，为 true → 只弹 toast（E4 文案），不切宠物、不导航。
- 归因：内存保存「petId → (notificationId, notificationKind, openedAt)」，30 分钟内同宠物保存记录 → `health_record_saved source=health_reminder`。
- 通知中心：`HEALTH_` 前缀走 `_storedTitle / _storedBody`；正文 `maxLines: 2`（其余类型仍 1 行）；图标 `health_and_safety_rounded`、`AppColors.mint` on `violet100`。
- 推送设置：只改 `pushSettingsPetReminderDesc` 两份 arb；**不改**「Hewan yang kamu ikuti」那一行（③ 推送与召回线负责改名）。

---

## §8 埋点

- 后端经 `AnalyticsClient`（`distinctId = AnalyticsDistinctId.of(userId)`，不传明文 user_id）：`health_notification_sent`、`health_reminder_sent`、`health_reminder_cancelled`；事件名常量 + 属性键全部登记 `AnalyticsEventGuard` 白名单（漏登记 = 静默丢）。幂等：只在状态真正迁移的那一次写库后发（同日重跑无重复）。
- App 经 `Analytics.capture` 字面量事件名（前缀 `health_` 已在 `v112_events_test.dart` 白名单）：`health_notification_opened`、`health_record_saved`、`health_record_deleted`、`health_profile_field_updated`、`health_consult_card_tapped`、`health_category_filtered`；`pet_switched` 加 `source=health_reminder`。属性只放枚举 / 数字 / 布尔（禁宠物名、备注、疫苗原文）。
- 属性值一律小写蛇形（`d_day`、`record`、`skipped_cap` …），与 PRD §4 一致。
- 停用 `triage_category_jump_tapped`（随商城跳转行删除）。

---

## §9 L0 / L1 / L2 分层（本线通用）

- **L0（云端）**：规则类纯单测（PRD AC1–AC57 中可纯函数化的全部写成参数化用例）、迁移文本守卫、`PushExitStructureTest`、`AnalyticsEventGuard` 白名单测试、App `flutter analyze` + widget / unit 测试。
- **L1（本地 Docker）**：迁移在 scratch 库跑通（validate）、重建 / 日跑 / 排队 / 点开接口的集成测试（`ApiIntegrationTest` 子类）、回填 Runner。
- **L2（本地真机）**：推送真实到达与三种点开（冷启动 / 后台 / 前台，Android + iOS）、通知中心显示、UI 还原度（≥95%）。

---

## §10 刻意不做

管理后台页、提醒时间设置、免打扰、日历视图 / 提醒列表、商城链接、IM 客户端改造、按设备版本门槛、`ck_notifications_type` 改动、修改 `RetentionPushCalendar` / `AppVersionGate` / `ScheduledPushPlanner` 方法体。
