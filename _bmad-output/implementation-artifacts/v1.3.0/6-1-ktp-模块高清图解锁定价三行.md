---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 6
story: 6.1
ad: [AD-7]
decisions: [D-3, D-7]
contracts: [X-4]
---

# Story 6.1: KTP 模块高清图解锁定价三行（AB-18A）

Status: review

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`（后端→前端→联调、AC 标 L0/L1/L2、Flyway 时间戳版本号、`mvn -B clean package`）。
> 本 story 只改后端与后台模板；App 端护照样式解锁（FR-120）由 App 分支实现，本 story 只保证下发字段到位（契约 X-4）。**不依赖 2-3a/2-3b**（先在现有 `config.html` 卡片结构上落地，套模板 D 归 Story 6.3）。

## Story

As a 运营，
I want 在运营配置页一张卡里改 KTP 卡高清、护照内页、登机牌三个解锁价，
so that 护照样式上线后能自主调价，不必找工程发版。

## Acceptance Criteria

**AC1 · 两列迁移**
**Given** `pricing_config` 现只有 `id_hd_download_price`
**When** 执行 `V<yyyyMMdd_HHmm>__add_pricing_config_passport_prices.sql`
**Then** 新增 `passport_page_unlock_price`、`passport_boarding_unlock_price` 两列 `BIGINT NOT NULL`，初始值 = 当前行的 `id_hd_download_price`，各带 `CHECK (>= 1)` `[L1]`
**And** 既有 `id_hd_download_price` 加同样的 `CHECK (>= 1)`（D-7：三价一律 ≥1）——若现库该值为 0 则迁移失败，须先核 stag/prod 值 `[L1]`

**AC2 · 配置组更名重组**
**Given** 运营配置页
**When** 打开 `/admin/config`
**Then** 原「定价配置」卡内的「身份证高清图下载价」移出，新增独立卡「KTP 模块高清图解锁定价」三行：KTP 卡高清下载 / 护照·护照内页 / 护照·登机牌；每行样式名只读 + 价格输入；护照两行旁展示「参考：KTP 当前价 Rp X」 `[L2]`
**And** 「定价配置」卡剩 兽医单次咨询价 / 兽医分成 / AI 解锁价 / 每月 AI 免费额度 四项，端点不变 `[L1]`

**AC3 · 校验与保存**
**Given** 新卡表单 `POST /admin/config/ktp-pricing`
**When** 任一价 ≤0 或非整数
**Then** 422（htmx）/ 回显错误（整页）`admin.err.config.ktpPriceMin`「价格须为 ≥1 的整数（IDR），不做 0 元限免」 `[L1]`
**And** 三价独立保存、不联动；只对真变化的字段写 `config_change_logs`（`PRICING` 类型，字段名 = 列名）+ 一条审计 `CONFIG_UPDATE_PRICING`；无变化不写不审计（沿用 `AdminConfigService` 口径）`[L1]`
**And** 权限 `config.edit` 改、`config.view` 看（与既有定价卡同码，不新增）`[L1]`

**AC4 · 下发接口（契约 X-4）**
**Given** App 端读 KTP HD 价走 `GET /api/v1/pet-profiles/me/id-card/hd-pricing`（`IdCardHdPricingResponse`）
**When** 本 story 完成
**Then** 该响应新增 `passportPageUnlockPrice`、`passportBoardingUnlockPrice` 两字段（camelCase），旧字段不变 `[L1]`
**And** 改价即时生效只影响新发起的解锁；已解锁记录不受影响（`IdCardHdService` 不动）`[L1]`

**AC5 · 三语与回归**
**Then** 新增 key `admin.config.ktp.*`（卡标题 / 三行样式名 / 参考价文案 / 保存钮 / 成功 toast）+ `admin.err.config.ktpPriceMin` 三包同批 `[L0]`
**And** 既有 `AdminShareRewardConfigIntegrationTest` 与 config 相关测试全绿 `[L1]`

---

## Tasks / Subtasks

- [x] **T1 · 迁移**（AC1）
  ```sql
  -- V<yyyyMMdd_HHmm>__add_pricing_config_passport_prices.sql（取创建时刻；上一支动本表的是 V78）
  ALTER TABLE pricing_config ADD COLUMN passport_page_unlock_price     BIGINT;
  ALTER TABLE pricing_config ADD COLUMN passport_boarding_unlock_price BIGINT;
  UPDATE pricing_config SET passport_page_unlock_price     = id_hd_download_price,
                            passport_boarding_unlock_price = id_hd_download_price;
  ALTER TABLE pricing_config ALTER COLUMN passport_page_unlock_price     SET NOT NULL;
  ALTER TABLE pricing_config ALTER COLUMN passport_boarding_unlock_price SET NOT NULL;
  ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_passport_page_min     CHECK (passport_page_unlock_price >= 1);
  ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_passport_boarding_min CHECK (passport_boarding_unlock_price >= 1);
  ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_id_hd_min             CHECK (id_hd_download_price >= 1);
  COMMENT ON COLUMN pricing_config.passport_page_unlock_price IS 'FR-120 护照·护照内页样式一次性解锁价（IDR，≥1，D-7 不做 0 元）';
  COMMENT ON COLUMN pricing_config.passport_boarding_unlock_price IS 'FR-120 护照·登机牌样式一次性解锁价（IDR，≥1）';
  ```
  - [x] 🔴 迁移前在 stag / prod 库确认 `id_hd_download_price >= 1`，否则最后一条 CHECK 让启动即挂
  - [x] `bash scripts/ci/check-flyway-versions.sh origin/main`

- [x] **T2 · 实体与读服务**（AC1/AC4）
  - [x] `config/domain/PricingConfig.java` 加两字段 + getter/setter（`long`，与 `idHdDownloadPrice` 同型）
  - [x] `IdCardHdService.currentHdPrice()` 保留；新增 `passportPrices()` 或直接在 `ProfileApiController` 组装；`IdCardHdPricingResponse` 增两字段
  - [x] `PlatformConfigService.pricing()` 无缓存直读，不用改

- [x] **T3 · 写服务**（AC3）
  - [x] `AdminConfigService` 新增 `updateKtpPricing(KtpPricingForm form, long adminId)`：`require(>=1)` × 3 → diff 三字段（`id_hd_download_price` / `passport_page_unlock_price` / `passport_boarding_unlock_price`）→ `commit(logs, adminId, "PRICING", "pricing_config")`
  - [x] `updatePricing` 去掉 `idHdDownloadPrice` 参数与 diff（`PricingForm` 收窄为 4 字段）；`admin.config.pricingReadonly` 文案的 `{3}` HD 占位同步删
  - [x] 新 DTO `admin/config/dto/KtpPricingForm(long idHdDownloadPrice, long passportPagePrice, long passportBoardingPrice)`

- [x] **T4 · Controller 与模板**（AC2/AC3）
  - [x] `AdminConfigController`：`GET /admin/config` model 加 `ktpPricing`；新增 `POST /admin/config/ktp-pricing`（`EDIT_AUTH`，PRG + toast `admin.flash.config.ktpPricingSaved`）；`POST /admin/config/pricing` 去掉 `idHdDownloadPrice` 参数
  - [x] `templates/admin/config.html`：定价卡删 HD 行；紧接其后新卡 `<form class="card" th:action="@{/admin/config/ktp-pricing}">`，三行结构 `样式名(只读 span) | 价格 input[type=number,min=1,step=1] | 参考价(仅护照两行)`；沿用现有 `data-confirm` 高危确认（key `admin.config.ktp.confirm`，复述新旧值由 JS 拼——现有 `admin.js` 的 data-confirm 只支持静态文案，本 story 用静态文案「确认修改解锁定价？改价只影响新发起的解锁」即可，新旧值复述随 Story 6.3 模板 D 一起做）
  - [x] 校验失败：现状 Controller 用 `catch (AppException) → flash error` PRG 回显，沿用；htmx 422 fragment 待 6.3

- [x] **T5 · 三语**（AC5）：`admin.config.ktp.title / rowKtpHd / rowPassportPage / rowPassportBoarding / refHint / btn.save / confirm`、`admin.flash.config.ktpPricingSaved`、`admin.err.config.ktpPriceMin`，zh_CN / en / id / 默认包四份

- [x] **T6 · 测试**
  - [x] L1：迁移后两列 = 原 HD 价；`updateKtpPricing` 三价各自 diff、0/负数 422、无变化不写日志；`/api/v1/pet-profiles/me/id-card/hd-pricing` 含两新字段
  - [x] L1：`updatePricing` 不再接受 / 不再改 HD 价（回归）
  - [x] L0：`mvn -B clean package`；三包 key 集合相等（手工 diff，CI 脚本归 2.2）

- [x] **T7 · 云端执行须知**：云端只跑 L0；L1 需 scratch 库（先 flush Redis DB0），Completion Notes 标「L1/L2 待本地验收」

---

## Dev Notes

### 🔴 D-7：不做 0 元限免，三价一律 ≥1

评审 #3 证实账本 `CHECK (amount > 0)`、收款渠道不接受 0 元单，产品拍板不做限免。所以本 story **把 ≥1 落到数据库 CHECK**，而不只是表单校验——防手工改库改出 0。注意 `id_hd_download_price` 也一并加 CHECK，迁移前先核现值。

### 🔴 D-3：加样式 = 加列 + 迁移 + 小发版

`pricing_config` 是单行固定列表，不是 key-value。本 story 就是「加两列」的第一次实践；PRD 已改成「每款样式一次小发版」，**不要**顺手改成 key-value 表（架构 AD-7 明确否决）。

### 🔴 只有真变化才写日志与审计

`AdminConfigService.diff()` + `commit()` 的口径：无变化 early return。新方法照抄 `updatePricing` 结构即可，`ConfigType.PRICING` 复用，`config_change_logs.config_type` 的 CHECK（V78 + `V20260824_1655` 加 FEED_RANK）**不需要改**。

### 现状代码要点

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `config/domain/PricingConfig.java` | 单行实体 `SINGLETON_ID=1`，5 个业务字段 | + 两字段 | `vetPayout()`、`@PreUpdate` |
| `admin/config/service/AdminConfigService.java` | `updatePricing` 校验 `>= 0` + 逐字段 diff + `commit` | + `updateKtpPricing`；`updatePricing` 去 HD | `require/diff/commit` 三私有方法与其它 update* |
| `admin/config/web/AdminConfigController.java` | `VIEW_AUTH` / `EDIT_AUTH` 常量；`GET /admin/config` 塞 pricing / pawcoin / tiers / shareRewardOverview；`POST …/pricing` 五参数 | + `ktpPricing` model、`POST …/ktp-pricing`；`/pricing` 四参数 | 其余端点与 `SHARE_REWARD_*` 权限拼装方式 |
| `admin/config/dto/PricingForm.java` | 5 字段 record | 收窄 4 字段；新 `KtpPricingForm` | — |
| `templates/admin/config.html`（145 行） | 定价卡（5 项）/ PawCoin 卡 / 分享奖励卡 / 充值档位表 | 定价卡删 HD 行；新 KTP 卡 | 其余卡原样 |
| `profile/web/ProfileApiController.java:211` | `IdCardHdPricingResponse(idCardHdService.currentHdPrice())` | 响应加两字段 | 端点路径不变 |
| `config/service/PlatformConfigService.java` | `pricing()` 单行直读无缓存 | 不改 | — |
| `consult/web/ConsultPricingController.java` | `/api/v1/consult/pricing` 只下发兽医价 | **不改**（护照价走 HD pricing 端点） | — |

### 契约 X-4（与 App 分支）

App 端 FR-120 按 `hd-pricing` 响应的两个新字段取价；字段名固定 `passportPageUnlockPrice` / `passportBoardingUnlockPrice`。本 story 合入后通知 App 分支。

### 迁移约定 / 测试标准 / 云端

- 时间戳文件名，取创建时刻；上一支参考 `V20260902_1620__rebuild_ck_notifications_type_full.sql`。
- L1 沿用 `AdminShareRewardConfigIntegrationTest`（`ApiIntegrationTest` 真库）范式。
- 云端只 L0。

### References

- [Source: PRD-v1.3.0-admin.md#4. AB-18A]、[决策日志 D-3 / D-7]、[architecture-v1.3.0-admin-delta.md#AD-7 / X-4]、[epics-v1.3.0-admin.md#Story 6.1]、[逐页规格 D2]、[UI 稿 7-1 KTP 卡]
- [Source: petgo-backend …/admin/config/service/AdminConfigService.java]、[…/config/domain/PricingConfig.java]、[…/db/migration/V78__init_platform_config.sql]

## Dev Agent Record

### Agent Model Used

claude-fable-5-1（云端 headless session，2026-09-09）

### Debug Log References

- 云端 L0：`./mvnw -B clean package`（排除需真库的 Spring 上下文测试类）→ BUILD SUCCESS，1825 tests，0 failures；`AdminConfigServiceTest`（+4：三价任一 ≤0 拒绝且不写日志 / 只记真变化列且不联动 / 无变化不写 / 定价卡不再动 HD 价）、`AdminMessagesExternalizedTest` / `AdminMessagesParityTest`（`config.edit` 权限码在四包只读文案里逐字保留）、`AdminTemplateStructureTest` 全绿。
- 迁移 `V20260909_1843__add_pricing_config_passport_prices.sql`（取创建时刻）；`scripts/ci/check-flyway-versions.sh origin/main` 云端无 remote 跑不了（「取不到 origin/main」）→ **本地提交前补跑**；树内无同号。
- `check-i18n-keys.sh` OK（+11 key：`admin.config.ktp.{title,hint,rowKtpHd,rowPassportPage,rowPassportBoarding,refHint,btn.save,confirm,readonly}`、`admin.flash.config.ktpPricingSaved`、`admin.err.config.ktpPriceMin`；删无引用的 `admin.config.idHdDownloadPrice`；`admin.config.pricingReadonly` 去掉 HD `{3}` 占位并把额度改为 `{3}`，四包同步）。
- 复审（bmad-code-review，CONFIRMED 1 条已修 + PLAUSIBLE 3 条已修 + 偏差说明 8 条）：① 🔴 L1 用 `findTop100ByOrderByChangedAtDesc().size()` 做计数差，共享累积库到 99 行后每次必红 → 改 `changeLogs.count()` / `audits.count()` + `findTopByOrderByIdDesc`；② 页面断言被 Thymeleaf 原样输出的 HTML 注释兜底（删掉整卡也绿）→ story 注释改解析级 `<!--/* */-->`，断言锚到 `data-ktp-readonly` / `data-ref-hint` / `name=` 与只读三价数值；③ 审计断言三次全表拉取 → `count()` + 末条；④ `AdminPagesRenderSmokeTest.everyConfigSectionIsActuallyRenderedOnThePage` 补 `/admin/config/ktp-pricing` 锚点与「HD 行已移出」断言；⑫ `currentHdPrice()` 已无调用方 → javadoc 注明保留意图。偏差说明（不改代码）：V78 `ck_pricing_nonneg`（≥0）与新 `ck_pricing_id_hd_min`（≥1）并存、端点实际路径 `/me/id-cards/pricing`、AC3 htmx 422 留 6.3、模板复用 `pricing` 不另加 `ktpPricing` model、`data-confirm` 静态文案、参考价 `'COMMA'` 千分位与仓库一致。

### Completion Notes List

- **L1/L2 待本地验收**：① `AdminKtpPricingIntegrationTest`（真库）：迁移后两列 ≥1、`ck_pricing_passport_page_min / ck_pricing_passport_boarding_min / ck_pricing_id_hd_min` 三条 CHECK 在位（手工 `UPDATE … = 0` 被拒）；`POST /admin/config/ktp-pricing` 只改护照两价 → HD 不联动、`config_change_logs` 恰 2 条（`passport_page_unlock_price` / `passport_boarding_unlock_price`，PRICING）+ 审计 `CONFIG_UPDATE_PRICING` 一条、toast；无变化再提交不写不审计；0 / 负数 → flash error「≥1」且三价不变，`1.5` 绑定层 400；`config.view` 403 且页面只读无表单，`config.edit` 页面含三行表单与「参考」；定价卡端点即使带 `idHdDownloadPrice` 参数也不再改 HD 价；`GET /api/v1/pet-profiles/me/id-cards/pricing` 含两新字段并即时反映改价；② `IdCardHdPricingEndpointTest` 加两字段断言、`PlatformConfigIntegrationTest` / `AdminShareRewardConfigIntegrationTest` 改用四字段 `PricingForm`；③ L2：本地 stag 对照 UI 稿 7-1：定价卡剩四项，紧接其后「KTP 模块高清图解锁定价」卡三行（样式名只读 + 价格输入 + 护照两行「参考：KTP 当前价 Rp X」）、`data-confirm` 静态文案、保存后 toast、只读态提示三语。
- **AC1 迁移**：`pricing_config` +`passport_page_unlock_price` / `passport_boarding_unlock_price`（BIGINT NOT NULL，初始 = 当前行 `id_hd_download_price`），三列各 `CHECK (>= 1)`（D-7 落到 DB）；D-45 已核实 stag=5,000 / prod=100，均 ≥1，最后一条 CHECK 不会让启动挂。`PricingConfig` 实体 +两字段（`long`，getter / setter；`vetPayout()`、`@PreUpdate` 保留）。
- **AC2 配置页**：`config.html` 定价卡删 HD 行（剩兽医单价 / 分成 / AI 解锁价 / 月免费额度，端点 `POST /admin/config/pricing` 不变但不再接收 `idHdDownloadPrice`）；紧接其后新卡「KTP 模块高清图解锁定价」三行 KTP 卡高清下载 / 护照·护照内页 / 护照·登机牌（`input[type=number,min=1,step=1,required]`，护照两行旁「参考：KTP 当前价 Rp X」千分位），只读态（`config.view` 无 `config.edit`）显示三价提示。**未套模板 D**（归 6.3），沿用现有卡片结构。
- **AC3 校验与保存**：`AdminConfigService.updateKtpPricing(KtpPricingForm, adminId)`：三价 `>= 1` 否则 422 `admin.err.config.ktpPriceMin`（整页 PRG flash error 回显；htmx 422 fragment 随 6.3）→ 逐列 `diff`（`id_hd_download_price` / `passport_page_unlock_price` / `passport_boarding_unlock_price`）→ 只在有变化时 `commit`（`config_change_logs` PRICING + 审计 `CONFIG_UPDATE_PRICING`）。`updatePricing` 收窄为四字段（`PricingForm` 去 `idHdDownloadPrice`），不再 diff / 写 HD 价。权限：`POST /admin/config/ktp-pricing` `EDIT_AUTH`（`config.edit`），查看 `config.view`，不新增码。
- **AC4 下发（契约 X-4）**：`IdCardHdPricingResponse(price, passportPageUnlockPrice, passportBoardingUnlockPrice)`，由 `IdCardHdService.currentPricing()` 一次读 `pricing_config` 组装（无缓存）；`currentHdPrice()` 保留供扣费；⚠️ 实际端点路径是 `GET /api/v1/pet-profiles/me/id-cards/pricing`（架构 delta 写的 `/id-card/hd-pricing` 为笔误，已在 `CROSS-STORY-DECISIONS.md` 新增 `pricing_config` 行注明，App 分支按实际路径取）。改价只影响新发起的解锁（`IdCardHdService` 扣费逻辑未动）。
- **AC5 三语**：`admin.config.ktp.*` 九个 + `admin.flash.config.ktpPricingSaved` + `admin.err.config.ktpPriceMin` 四包同批；`pricingReadonly` 占位同步收窄。
- **文档**：`docs/reference/db-schema-reference.md` `pricing_config` 表补两列；`CROSS-STORY-DECISIONS.md` 加 `pricing_config` / X-4 行。

### File List

- petgo-backend/src/main/resources/db/migration/V20260909_1843__add_pricing_config_passport_prices.sql（新增）
- petgo-backend/src/main/java/com/tailtopia/config/domain/PricingConfig.java（+2 字段）
- petgo-backend/src/main/java/com/tailtopia/admin/config/dto/PricingForm.java（收窄 4 字段）、dto/KtpPricingForm.java（新增）、service/AdminConfigService.java（+`updateKtpPricing`）、web/AdminConfigController.java（+`POST /admin/config/ktp-pricing`；`/pricing` 四参数）
- petgo-backend/src/main/java/com/tailtopia/profile/dto/IdCardHdPricingResponse.java（+2 字段）、service/IdCardHdService.java（+`currentPricing`）、web/ProfileApiController.java
- petgo-backend/src/main/resources/templates/admin/config.html（定价卡删 HD 行 + KTP 三行卡）
- petgo-backend/src/main/resources/i18n/messages{,_zh_CN,_en,_id}.properties（+11 / −1 key，`pricingReadonly` 改占位）
- docs/reference/db-schema-reference.md、_bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md
- 测试：admin/config/AdminKtpPricingIntegrationTest（新增 L1）、admin/config/service/AdminConfigServiceTest（+4 L0）、admin/web/AdminPagesRenderSmokeTest（config 页锚点）、profile/web/IdCardHdPricingEndpointTest、config/PlatformConfigIntegrationTest、admin/config/AdminShareRewardConfigIntegrationTest（四字段表单）

## 拍板回写（2026-09-09）

- **D-45**：已核实 stag KTP 高清价 = 5,000、prod = 100，均 ≥1；三列 `CHECK (>= 1)` 可直接上线，迁移前无需改值。
