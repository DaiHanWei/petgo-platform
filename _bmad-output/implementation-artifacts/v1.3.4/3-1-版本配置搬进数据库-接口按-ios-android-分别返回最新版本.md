# Story 3.1: 版本配置搬进数据库，接口按 iOS / Android 分别返回最新版本

Status: review

## Story

As a 运营,
I want 版本配置存在数据库、iOS 与 Android 各有一个最新版本号,
so that 哪端上架就只改哪端，不用改服务器环境变量和重启。

> 范围：**纯后端**（Epic 3 的第一条；App 侧接线在 3.2）。后台配置页 AB-22 与其保存接口属 admin 主题（D-1 / D-2 / AD-1），本 story 只交付表、读接口与 `AppVersionConfigService.update(...)` 服务方法。

## Acceptance Criteria

**AC1 — 迁移：单行配置表 + 预置值** `[L0]` `[L1]`
1. 新迁移 **`V20261012_1104__create_app_version_config.sql`**（预分配号，不得另取），全文见 Dev Notes「迁移全文」，要点：
   - 表 `app_version_config`：`id BIGINT PRIMARY KEY`、`ios_latest_version VARCHAR(20) NOT NULL`、`android_latest_version VARCHAR(20) NOT NULL`、`min_supported_version VARCHAR(20) NOT NULL`、`ios_store_url VARCHAR(512) NOT NULL`、`android_store_url VARCHAR(512) NOT NULL`、`updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`、`updated_by BIGINT NULL`（admin_accounts.id，不建外键，照 V78 `config_change_logs.changed_by` 惯例）；约束 `ck_app_version_config_singleton CHECK (id = 1)`。
   - 预置一行（D-10 / D-18）：`id=1`、`ios_latest_version='1.3.3'`、`android_latest_version='1.3.3'`、`min_supported_version='1.0.0'`、`ios_store_url='https://apps.apple.com/app/id6785361196'`、`android_store_url='https://play.google.com/store/apps/details?id=com.tailtopia.app'`。
   - 文件头注释**逐字**包含：`发版检查项：上线前按商店实际版本核对；本迁移提交即冻结，改值走后台 AB-22 或另起 UPDATE 迁移，不得改本文件`（D-18）。
2. L0 文本测试 `AppVersionConfigMigrationTest`（新建，纯文件读取）钉住：文件存在、含上面那句注释、含 `CHECK (id = 1)`、两端最新版本为 `'1.3.3'`、最低版本 `'1.0.0'`、两条商店链接原文；且**不含** `'1.0.0'` 作为任一端最新版本（即 VALUES 第 2、3 个值不是 `1.0.0`）。
3. `[L1]` 本地起服务 `ddl-auto=validate` 通过（实体与表一致）；`SELECT * FROM app_version_config` 恰一行且值同上。

**AC2 — 公开读接口改字段** `[L0]` `[L1]`
1. `GET /api/v1/app-version`（游客可读，`SecurityConfig` L183-184 的 `permitAll` 不动）响应 200，JSON **恰好** 5 个键：
   ```json
   {
     "iosLatestVersion": "1.3.3",
     "androidLatestVersion": "1.3.3",
     "minSupportedVersion": "1.0.0",
     "iosStoreUrl": "https://apps.apple.com/app/id6785361196",
     "androidStoreUrl": "https://play.google.com/store/apps/details?id=com.tailtopia.app"
   }
   ```
   值全部取自 `app_version_config` 的 `id=1` 行；**不再返回 `latestVersion`**。商店链接为空串时原样返回 `""`（不转 null）。
2. 每次请求都读库（`findById(1L)`），**不加任何缓存**（不加 `@Cacheable`、不做进程内缓存字段）——配置被改后下一次请求立即返回新值。
3. 配置行缺失（被误删）→ `AppException.serviceUnavailable("App 版本配置缺失")`（503 ProblemDetail，App 侧失败放行）；不得 500、不得返回硬编码兜底值。
4. `AppVersionProperties.java` 删除；`AppVersionController` 不再 `@EnableConfigurationProperties`；`application.yml` **L106-111**（注释行 + `app-version:` 块 5 行）删除；全仓 `grep -rn "app-version\.\|APP_LATEST_VERSION\|APP_MIN_SUPPORTED_VERSION\|APP_IOS_STORE_URL\|APP_ANDROID_STORE_URL" petgo-backend/src` 为空。
5. `.env.example`：已核实 `petgo-backend/.env.example` 本来就**没有** `APP_*` 版本变量，无需改动（Completion Notes 记一句「已核实无需改」）。

**AC3 — 校验服务 `AppVersionConfigService.update(...)`** `[L0]`
供 admin 主题保存接口调用（AD-1）。签名、校验顺序、错误码全部写死（D-17）：
```java
@Transactional
public AppVersionConfig update(AppVersionConfigUpdate cmd, long adminId)
```
- 先对 5 个入参 `trim()`（null 视为 `""`）。
- 按下列**顺序**校验，命中即抛 `AppException.validation(中文原文).code(码)`（422），不再往下：
  1. 三个版本号任一不匹配 `^(0|[1-9]\d{0,3})\.(0|[1-9]\d{0,3})\.(0|[1-9]\d{0,3})$`（严格 `x.y.z`，每段 0~9999、无前导零、无 `v` 前缀、无后缀）→ `admin.err.config.version.format`，原文「版本号格式不正确，应为 x.y.z」。
  2. 两条商店链接中**非空**的任一条不以小写 `https://` 开头 → `admin.err.config.store-url.scheme`，原文「商店链接必须以 https:// 开头」。
  3. `minSupportedVersion` 按数值逐段比较**大于** `iosLatestVersion` **或**大于 `androidLatestVersion` → `admin.err.config.version.min-gt-latest`，原文「最低支持版本不能高于任一端最新版本」。
  4. `minSupportedVersion` 按数值比较**大于 `1.0.0`**，且任一商店链接为空串 → `admin.err.config.store-url.blank`，原文「上调最低支持版本前，两个商店链接都必须填写」。
- 通过后：取 `id=1` 行（缺失 → `IllegalStateException("app_version_config 缺失")`），写入 5 个值 + `updated_at = Instant.now()` + `updated_by = adminId`，保存并返回实体。**无变更也照写** `updated_at` / `updated_by`。
- 本 story **不写** `config_change_logs`、**不调** `AdminAuditService`（留痕属 admin 主题；见「待确认」）。
- 本 story **不加**四语文案（`messages_*.properties` 不动；D-17：文案随 admin 主题）。

**AC4 — 单元测试覆盖校验** `[L0]`
`AppVersionConfigServiceTest`（新建，纯 Mockito）至少覆盖：
- 合法输入（`1.3.4/1.3.3/1.0.0` + 两条 https 链接）→ 保存成功、5 值写入、`updatedBy == adminId`、`updatedAt` 非空；入参前后空格被 trim。
- 每个错误码各至少一例且断言 `getMessageCode()` 等于上面的码：`1.3`、`v1.3.3`、`1.3.3-beta`、`01.2.3` → format；`http://…` → scheme；`min=1.4.0, ios=1.3.3, android=1.5.0` → min-gt-latest（只要高于任一端就拒）；`min=1.1.0` + `iosStoreUrl=""` → blank。
- 边界：`min=1.0.0` 且两链接为空 → **通过**（默认态允许空链接）；`min == latest` → 通过；数值比较不是字符串比较（`1.10.0 > 1.9.0`：`min=1.9.0, latest=1.10.0` 通过）。
- 校验失败时 `repository.save` **从未**被调用。

**AC5 — 既有测试同步** `[L0 编译]` `[L1]`
- `AppVersionControllerEndpointTest`（L1，继承 `ApiIntegrationTest`）改为断言 AC2.1 的 5 个键与迁移预置值、`$.latestVersion` **不存在**（`jsonPath("$.latestVersion").doesNotExist()`）；带 token 用例同理。
- 新增 L1 用例（同文件）：调用 `AppVersionConfigService.update(...)` 把 `iosLatestVersion` 改成 `1.3.9` 后请求接口，立即返回 `1.3.9`，`androidLatestVersion` 仍为 `1.3.3`；`@AfterEach` 用 `update` 写回迁移预置值（测试库共享，不复原会污染其它用例）。
- `SmokeApiTest.publicAppVersionIsOpen` 不改（只断 200）。

**AC6 — 文档同步** `[L0]`
`docs/reference/api-reference.md` §8.4（L363-364）改为新 5 字段，并注明「V1.3.4 起读 `app_version_config` 表，改值走后台 AB-22，无需重启」。

## Tasks / Subtasks

- [x] **T1 迁移**（AC1）
  - [x] 新建 `petgo-backend/src/main/resources/db/migration/V20261012_1104__create_app_version_config.sql`（全文见 Dev Notes）
  - [x] `bash scripts/ci/check-flyway-versions.sh origin/main` 通过
- [x] **T2 实体 / 仓储 / 服务**（AC2、AC3）
  - [x] `config/domain/AppVersionConfig.java`（照 `SupportContactConfig` 写法：`SINGLETON_ID = 1L`、`protected` 无参构造、getters、`apply(...)` 改值方法）
  - [x] `config/repository/AppVersionConfigRepository.java`（`JpaRepository<AppVersionConfig, Long>`）
  - [x] `config/service/AppVersionConfigUpdate.java`（record，5 个 String）
  - [x] `config/service/AppVersionConfigService.java`：`Optional<AppVersionConfig> current()`、`update(cmd, adminId)`；版本比较私有方法 `compare(String a, String b)`（逐段 `Integer.parseInt`，只在格式校验通过后调用）
- [x] **T3 改接口、删旧配置**（AC2）
  - [x] `shared/config/AppVersionController.java`：注入 `AppVersionConfigService`；`AppVersionResponse` 改为 5 字段 record（顺序同 AC2.1）；缺行抛 503
  - [x] 删除 `shared/config/AppVersionProperties.java`
  - [x] 删除 `application.yml` L106-111
  - [x] 全仓 grep 无残留（AC2.4 命令）
- [x] **T4 测试**（AC1.2、AC4、AC5）
  - [x] `src/test/java/com/tailtopia/config/service/AppVersionConfigServiceTest.java`（L0）
  - [x] `src/test/java/com/tailtopia/config/AppVersionConfigMigrationTest.java`（L0，读 `src/main/resources/db/migration/V20261012_1104__create_app_version_config.sql`）
  - [x] 改 `src/test/java/com/tailtopia/shared/config/AppVersionControllerEndpointTest.java`（L1，云端只编译不跑）
- [x] **T5 文档**（AC6）

## Dev Notes

> ⚠️ **开工前先对照前面 story 实际提交的代码核对本文件引用的名字，有出入先改本文件并在 Completion Notes 记一句。**

### 必读：会被本 story 改到的现有代码

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `petgo-backend/src/main/java/com/tailtopia/shared/config/AppVersionController.java`（L1-32） | `@RestController` + `@EnableConfigurationProperties(AppVersionProperties.class)`；record `AppVersionResponse(latestVersion, minSupportedVersion, iosStoreUrl, androidStoreUrl)`；`@GetMapping("/api/v1/app-version")` 直接读 props，无 service 层 | 改注入 `AppVersionConfigService`；record 换 5 字段；缺行 503 | 路径 `/api/v1/app-version`、GET、无鉴权 |
| `petgo-backend/src/main/java/com/tailtopia/shared/config/AppVersionProperties.java`（L1-48） | `@ConfigurationProperties(prefix="petgo.app-version")`，默认 `1.0.0` / 空串 | **删除整个文件** | — |
| `petgo-backend/src/main/resources/application.yml` **L106-111** | L106 注释「App 版本更新提醒（Story 6.5…）」+ L107 `app-version:` + L108-111 `latest` / `min-supported` / `ios-store-url` / `android-store-url`（读 `APP_LATEST_VERSION` 等 env） | **删除这 6 行** | 上下文 `consult:` 块（L101-105）与下面的 H5 下载 CTA 注释（L112 起）原样 |
| `petgo-backend/src/main/java/com/tailtopia/shared/security/SecurityConfig.java` L183-184 | `.requestMatchers(HttpMethod.GET, "/api/v1/app-version").permitAll()` | **不改** | 游客可读 |
| `petgo-backend/src/test/java/com/tailtopia/shared/config/AppVersionControllerEndpointTest.java`（L1-41，继承 `ApiIntegrationTest`） | 断言 `latestVersion=1.0.0`、两 URL 空串 | 按 AC5 改 | 两个既有用例名可保留，断言内容换 |
| `petgo-backend/src/test/java/com/tailtopia/support/SmokeApiTest.java` L14-18 | 只断 `/api/v1/app-version` 200 | 不改 | — |
| `docs/reference/api-reference.md` L363-364 | §8.4 写旧 4 字段 | 按 AC6 改 | 附录 A 速查表第 49 行（`GET /app-version` 公开）不变 |

`petgo-backend/.env.example`：已核实没有 `APP_LATEST_VERSION` 等变量（仅 L60 `TENCENT_IM_SDK_APP_ID`、L178 `APP_ENV` 等无关项），**不改**。`docs/ops/rollout-plan.md` L52-54、`docs/ops/retention-push-copy-matrix.md` L102-105 是历史运维记录，提到旧 env，**不改**（不是本 story 交付物）。

### 可直接复用

| 要做的事 | 用这个 | 位置 |
|---|---|---|
| 单行配置表范式（CHECK(id=1) + 种子 INSERT + COMMENT） | `support_contact_config` 迁移 | `db/migration/V20260916_0925__init_support_contact_config.sql` L17-35 |
| 单行实体写法（`SINGLETON_ID`、`protected` 构造、改值方法不含校验） | `SupportContactConfig` | `config/domain/SupportContactConfig.java` L19-73 |
| 单行仓储 | `SupportContactConfigRepository` | `config/repository/SupportContactConfigRepository.java` |
| 422 + 文案码 | `AppException.validation(detail).code(code)` | `shared/error/AppException.java` L41-45、L65-67 |
| 503 | `AppException.serviceUnavailable(detail)` | `shared/error/AppException.java` L106 |
| `require(cond, msg, code)` 写法 | `AdminConfigService.require` | `admin/config/service/AdminConfigService.java` L525-530（私有，**照抄一份**到新服务，不要跨包调用） |
| 纯 Mockito 配置服务测试写法 | `AdminConfigServiceTest` | `src/test/java/com/tailtopia/admin/config/service/AdminConfigServiceTest.java` L36-60 |

### 迁移全文（照抄，不得改值）

```sql
-- V1.3.4 app 主题 Story 3.1（FR-124 / AD-10）：App 版本配置从环境变量搬进单行表。
-- 时间戳版本号（决策 E7，预分配 V20261012_1104）。
--
-- 🔴 发版检查项：上线前按商店实际版本核对；本迁移提交即冻结，改值走后台 AB-22 或另起 UPDATE 迁移，不得改本文件
--    （决策日志 D-18：两端最新版本号预置 1.3.3；D-10：商店链接用标准地址、最低支持版本 1.0.0）。
--
-- 为什么 iOS / Android 各一列（PRD §3.3 规则 g）：两端上架不同步，共用一个最新版本号会让
-- 先上架那端改值后、另一端用户被提示去更新商店里还不存在的版本。
-- min_supported_version 双端共用，默认 1.0.0 永不上调（PRD §3.3 规则 d）。
--
-- 范式照 support_contact_config / pricing_config：单行强类型表 + CHECK(id=1) + 种子 INSERT，不建 key-value 表。

CREATE TABLE app_version_config (
    id                      BIGINT       PRIMARY KEY,
    ios_latest_version      VARCHAR(20)  NOT NULL,
    android_latest_version  VARCHAR(20)  NOT NULL,
    min_supported_version   VARCHAR(20)  NOT NULL,
    ios_store_url           VARCHAR(512) NOT NULL,   -- 空串 = 未配置（App 不出横幅、弹窗无「前往更新」）
    android_store_url       VARCHAR(512) NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by              BIGINT       NULL,       -- admin_accounts.id；迁移种子为 NULL，后台保存时写入
    CONSTRAINT ck_app_version_config_singleton CHECK (id = 1)
);

COMMENT ON TABLE app_version_config IS
    'App 版本配置（单行 id=1）。V1.3.4 Story 3.1：GET /api/v1/app-version 每次读本表，改值即生效、无需重启。';
COMMENT ON COLUMN app_version_config.min_supported_version IS
    '低于此版本 = 强制更新。默认 1.0.0 永不上调，仅安全问题 / 数据不兼容时临时上调，上调前两个商店链接必须已填。';

INSERT INTO app_version_config
    (id, ios_latest_version, android_latest_version, min_supported_version, ios_store_url, android_store_url)
VALUES
    (1, '1.3.3', '1.3.3', '1.0.0',
     'https://apps.apple.com/app/id6785361196',
     'https://play.google.com/store/apps/details?id=com.tailtopia.app');
```

### 关键设计点

- **服务放 `com.tailtopia.config.service`，不放 `admin` 包**：`AdminMessagesExternalizedTest.everyReferencedCodeExistsInAllLocales`（`src/test/java/com/tailtopia/admin/web/AdminMessagesExternalizedTest.java` L97-126）只扫 `src/main/java/com/tailtopia/admin/**` 里的 `admin.err.*` 字面量并要求三语齐全。本 story 不加文案（D-17），服务若放进 admin 包这条测试会红。`noOrphanDynamicMessageCodes`（L128-154）是「properties 有 → 代码须引用」方向，本 story 不加 key，不受影响。admin 主题接入时由它补 `messages_{zh_CN,en,id}.properties` 四个码（`messages.properties` 是否同步按该主题的 `check-i18n-keys.sh` 规则）。
- **错误码命名用点分段**（`admin.err.config.version.format`），与既有驼峰式（`admin.err.config.supportEmailBlank`）不同——这是 D-17 拍板的名字，**照写，不改成驼峰**。
- **版本比较按数值逐段**：字符串比较会把 `1.10.0` 判成小于 `1.9.0`。格式校验先行，保证 `compare` 入参都是三段纯数字。
- **`store-url.blank` 只在上调最低版本时生效**：最低版本 = `1.0.0`（默认态）允许链接为空（PRD §3.3 规则 g + epics 3.1：「把最低支持版本调高到 1.0.0 以上但任一商店链接为空」才拒）。理由：防止用户被锁进没有「前往更新」按钮的强制弹窗。
- **读接口不缓存**：`findById` 走 JPA 一级缓存仅限单次请求的事务内，跨请求无缓存；不要加 Spring Cache（架构护栏禁通用缓存层）。
- **`AppVersionController` 留在 `shared/config` 包**：保持 URL 与 `SecurityConfig` 注释位置不变；仓库无 ArchUnit 包依赖规则（已核实 `pom.xml` 无 archunit），`shared → config` 依赖可接受。
- 响应 record 字段名即 JSON 键（Jackson 默认），**不加** `@JsonInclude(NON_NULL)`：五个值均非空列。

### 必须更新的既有测试

- `AppVersionControllerEndpointTest`（L1）——按 AC5 改断言。云端只需编译通过。
- 无 L0 测试引用 `AppVersionProperties`（已 grep：仅 `AppVersionController` 与 `AppVersionControllerEndpointTest` 注释引用），删除后编译若报错按实际修。

### 验证层级

- **L0（云端跑）**：AC1.2 迁移文本测试、AC3/AC4 服务单测、AC2.4 grep、AC6 文档、全量编译。
- **L1（本地）**：AC1.3 `ddl-auto=validate` 启动、AC2.1-2.3 真接口、AC5 端点集成测试。
- **云端 L0 命令**（照抄）：
  ```bash
  cd petgo-backend
  LC_ALL=C.UTF-8 ./mvnw -B clean package -DskipTests
  # 排除需要 PostgreSQL / Redis 的测试：继承 ApiIntegrationTest 的类、@SpringBootTest 类、*IntegrationTest
  EXCL=$(grep -rlE 'extends ApiIntegrationTest|@SpringBootTest' src/test/java \
          | sed 's#.*/##; s#\.java$##' | sort -u | sed 's/^/!/' | paste -sd, -)
  LC_ALL=C.UTF-8 ./mvnw -B test -Dtest="${EXCL},!*IntegrationTest" -Dsurefire.failIfNoSpecifiedTests=false
  ```
  若仍有类因连库失败（间接继承等），把它补进排除清单并在 Completion Notes 列出类名；**不得**改断言迁就。
- 被排除的 L1 测试照样要写好、能编译；Completion Notes 记「L1 待本地验收：AC1.3 / AC2 真接口 / AC5」。

### Project Structure Notes

- 新文件：`config/domain/AppVersionConfig.java`、`config/repository/AppVersionConfigRepository.java`、`config/service/AppVersionConfigUpdate.java`、`config/service/AppVersionConfigService.java`（全在 `petgo-backend/src/main/java/com/tailtopia/` 下，与 `SupportContactConfig` 同组）。
- **不改** `PlatformConfigService`（它聚合定价 / PawCoin / 客服配置给后台页用；版本配置的后台页属 admin 主题，届时由该主题决定是否并入）。
- 命名映射：DB `ios_latest_version` ↔ Java `iosLatestVersion` ↔ JSON `iosLatestVersion`。

### 待确认

1. **长度校验**：商店链接列宽 512、版本列宽 20；`update` 不做长度校验（D-17 只给了 4 个码，不自造第 5 个）。版本号正则已限定最长 14 字符；商店链接超 512 会在保存时触发 DB 错误（500）。建议 admin 主题表单加 `maxlength=512`，或 admin 主题评审时补码。
2. **变更留痕**：本 story 不写 `config_change_logs`。admin 主题若要写，`config_change_logs.config_type` 的 CHECK `ck_config_change_type` 必须 DROP + ADD 全量重建，现行全集取自最后一次重建它的 `V20260916_0925__init_support_contact_config.sql` L49-51（`PRICING, PAWCOIN, TOPUP_TIER, FEED_RANK, SUPPORT_CONTACT`）再加新值。
3. **`store-url.blank` 触发条件**：D-17 只写「为空」，本 story 按 epics 3.1 原文落为「最低版本 > 1.0.0 且任一链接为空」才拒；默认态允许空链接。若产品要求任何时候都不得为空，改 `update` 第 4 步条件即可（迁移种子两链接已非空，不受影响）。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.4/epics-v1.3.4-app.md#Story 3.1]
- [Source: _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-app-delta.md#AD-1, AD-10, §11, §12]
- [Source: _bmad-output/planning-artifacts/v1.3.4/决策日志-app.md#D-2, D-10, D-17, D-18]
- [Source: _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-app.md#3.3 规则 d / g，AC6，OQ-3]
- [Source: _bmad-output/implementation-artifacts/v1.3.4/cloud-rules-app.md]

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (1M context)（claude-opus-5-5[1m]）

### Debug Log References

- `./mvnw -B clean package -DskipTests`：BUILD SUCCESS。
- 排除连库测试的全量 `./mvnw -B test`：Tests run 3035，Failures 1，Errors 0，Skipped 7。唯一失败为已知基线红 `MilestoneBadgeAssetSyncTest.everyBackendWebpIsAKnownKey`（等 Story 4.1 补 key），与本 story 无关。
  - 新增 `AppVersionConfigMigrationTest` 4/4 绿，`AppVersionConfigServiceTest` 18/18 绿。
- `bash scripts/ci/check-flyway-versions.sh origin/main`：OK（树内 219 个迁移，无重号）。
- AC2.4 grep（`app-version\.|APP_LATEST_VERSION|APP_MIN_SUPPORTED_VERSION|APP_IOS_STORE_URL|APP_ANDROID_STORE_URL`，范围 `petgo-backend/src`）：无结果。
- 无前端改动，未跑 flutter。

### Completion Notes List

- 开工前核对：Dev Notes 引用的类名、行号（`AppVersionController` L1-32、`AppVersionProperties`、`application.yml` L106-111、`SupportContactConfig`、`AppException.validation/.code/.serviceUnavailable`、`AdminConfigService.require`、`AdminConfigServiceTest`、`ApiIntegrationTest.adminActorId()`、`api-reference.md` §8.4）都和现状一致，story 文件没有需要修订的地方。
- 迁移 `V20261012_1104__create_app_version_config.sql` 照抄 Dev Notes 全文。
- 实体 `AppVersionConfig.apply(...)` 一次写入 5 个值和 `updatedAt` / `updatedBy`，由服务传入 `Instant.now()`。没用 `@PreUpdate`，这样值没变时也一定会写 `updated_at`。
- 服务的校验顺序和 4 个错误码按 AC3 实现，另有一个用例钉住顺序。null 视为 `""`，所以版本号为 null 时报 format。`AdminConfigService.require` 在新服务里照抄了一份私有副本。
- `AppVersionControllerEndpointTest` 的 `@AfterEach` 用 `adminActorId()` 作为 adminId 写回种子值（`updated_by` 没有外键，用哪个真实 id 都可以）。
- `.env.example`：已核实无需改（本来就没有 `APP_*` 版本变量）。
- 已按 D-17 处理：`messages_*.properties` 没动，没写 `config_change_logs`，也没调审计。
- **L1/L2 待本地验收**：
  1. AC1.3：本地起服务 `ddl-auto=validate` 通过；`SELECT * FROM app_version_config` 恰好一行，值等于预置值。
  2. AC2.1-2.3：真接口 `GET /api/v1/app-version` 返回 5 个键、值来自表；改表后下一次请求立即生效；删掉该行后返回 503 ProblemDetail（不是 500）。
  3. AC5：`AppVersionControllerEndpointTest` 3 个用例（游客、带 token、改值立即生效 + `@AfterEach` 复原）真跑通过；`SmokeApiTest.publicAppVersionIsOpen` 仍绿。

- 复审（bmad-code-review 口径）无 HIGH/MED；已修：新增 L0 `AppVersionControllerTest.missingConfigRowIs503`（AC2.3 不再只靠 L1）。**交接 admin 主题**：4 个 `admin.err.config.*` 码当前在 messages properties 里没有文案（D-17：四语文案随 admin 主题落），`useCodeAsDefaultMessage` 下 422 detail 会显示键名，admin 接入保存接口时必须补齐。L1 首跑留意：`jsonPath("$.length()").value(5)` 作用在 JSON 对象上的行为；`@AfterEach` 写回会改动 `updated_by` / `updated_at`。

#### 待确认

- 沿用 Dev Notes「待确认」1–3（长度校验、变更留痕、`store-url.blank` 触发条件）。实现按 story 写定的口径，没有另加处理。
- scheme 校验区分大小写（只认小写 `https://`），所以 `HTTPS://…` 会被拒并报 scheme 码，这与 AC3 写的「小写 `https://`」一致。如果 admin 表单希望容忍大写，需要 admin 主题自己决定。

### File List

- `petgo-backend/src/test/java/com/tailtopia/shared/config/AppVersionControllerTest.java`（新，复审补）

- 新增 `petgo-backend/src/main/resources/db/migration/V20261012_1104__create_app_version_config.sql`
- 新增 `petgo-backend/src/main/java/com/tailtopia/config/domain/AppVersionConfig.java`
- 新增 `petgo-backend/src/main/java/com/tailtopia/config/repository/AppVersionConfigRepository.java`
- 新增 `petgo-backend/src/main/java/com/tailtopia/config/service/AppVersionConfigUpdate.java`
- 新增 `petgo-backend/src/main/java/com/tailtopia/config/service/AppVersionConfigService.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/shared/config/AppVersionController.java`
- 删除 `petgo-backend/src/main/java/com/tailtopia/shared/config/AppVersionProperties.java`
- 修改 `petgo-backend/src/main/resources/application.yml`（删 L106-111）
- 新增 `petgo-backend/src/test/java/com/tailtopia/config/AppVersionConfigMigrationTest.java`
- 新增 `petgo-backend/src/test/java/com/tailtopia/config/service/AppVersionConfigServiceTest.java`
- 修改 `petgo-backend/src/test/java/com/tailtopia/shared/config/AppVersionControllerEndpointTest.java`
- 修改 `docs/reference/api-reference.md`（§8.4）

## Change Log

- 2026-10-12：story 创建（ready-for-dev）。
- 2026-10-12：实现完成（review）。版本配置改由 `app_version_config` 单行表提供，`/api/v1/app-version` 改为 5 个字段，删除 env 配置，新增校验服务和 L0 测试；L1/L2 待本地验收。
