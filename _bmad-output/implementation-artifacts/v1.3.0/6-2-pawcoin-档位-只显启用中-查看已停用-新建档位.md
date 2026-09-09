---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 6
story: 6.2
ad: [AD-7]
decisions: [D-24, D-25]
---

# Story 6.2: PawCoin 档位——只显启用中、查看已停用、新建档位（AB-22A + §5 ③ 第 10 条）

Status: review

> 自包含 story。后端 + 后台模板；App 端读档位接口**不变**（回归即可）。「查看已停用」折叠区用 htmx 局部加载，**依赖 2-3a 的 fragment 响应约定**（`HxRequest` 判别 + 422 放行）；若 2-3a 尚未合入，可先用整页展开参数 `?showDisabled=1` 过渡，Completion Notes 标注。

## Story

As a 运营，
I want 档位卡默认只列启用中的最多 4 档，能展开看已停用并重新启用，还能新建档位，
so that 活动调档不用找工程。

## Acceptance Criteria

**AC1 · 只显启用中**
**Given** `/admin/config` PawCoin 档位卡
**When** 打开
**Then** 表只列 `enabled=true` 档位（`sort_order` 升序），表下注「仅展示当前启用的档位，最多 4 档」 `[L2]`

**AC2 · 查看已停用与重新启用**
**When** 点「查看已停用（N）」（N = 停用数，0 时不渲染链接）
**Then** 原地展开灰底第二张表（`GET /admin/config/tiers/disabled` 返 fragment），每行「启用」钮 `[L2]`
**And** 启用时若启用中已达 4 → 422 `admin.err.config.tierCapReached`「已有 4 个启用档位，请先停用一个」；成功后两表 htmx 局部刷新 `[L1]`
**And** 既有「至少保留 1 个启用」护栏（`keepOneTier`）不变 `[L1]`

**AC3 · 新建档位**
**Given** 卡右上「＋ 新建档位」（`config.edit`）
**When** 抽屉 / 弹层填金额（IDR 正整数）提交 `POST /admin/config/tiers`
**Then** 校验：金额 ≥1 且为整数；与任何既有档位（含已停用）金额不重复（422 `admin.err.config.tierAmountExists`）；启用中已达 4 → 422 `tierCapReached` `[L1]`
**And** 成功：`tier_key = "t" + amount_idr`（如 `t25000`）、`enabled=true`、`sort_order` 按全部档位金额升序重排（1..n）、`config_change_logs` 一条（`TOPUP_TIER`，字段 `tier.t25000.created`，old 空 new 金额）+ 审计 `TIER_CREATED`；PRG 回本页以启用态出现 `[L1]`
**And** 并发：两次同时新建用 `pg_advisory_xact_lock(hashtext('pawcoin_topup_tiers'))` 串行，第二次命中 ≤4 或重复校验 `[L1]`

**AC4 · 不提供删除；App 不变**
**Then** 无删除端点；`DbTopupTierProvider.tiers()` 仍只出启用档位、按 `sort_order`；新建后 App 端 `tiers()` 立即包含新档；`byId` 对停用档位仍拒绝 `[L1]`

**AC5 · 三语与审计码**
**Then** `AuditActions.TIER_CREATED` 新增；key `admin.config.tier.*`（新建钮 / 表单 / 已停用链接 / 注脚 / toast）与两条 err 三包同批 `[L0]`

---

## Tasks / Subtasks

- [x] **T1 · 仓储与读服务**（AC1/AC2）
  - [x] `PawCoinTopupTierRepository` 新增 `findByEnabledFalseOrderBySortOrderAsc()`、`existsByAmountIdr(long)`、`countByEnabledFalse()`
  - [x] `PlatformConfigService` 新增 `disabledTiers()`；`AdminConfigController.GET /admin/config` 的 `tiers` 改为 `enabledTiers()` + `disabledCount`

- [x] **T2 · createTier 服务层**（AC3）
  ```java
  @Transactional
  public PawCoinTopupTier createTier(long amountIdr, long adminId) {
      require(amountIdr >= 1, "档位金额须为 ≥1 的整数（IDR）", "admin.err.config.tierAmountMin");
      em.createNativeQuery("select pg_advisory_xact_lock(hashtext('pawcoin_topup_tiers'))").getSingleResult(); // 事务级锁，串行化并发新建/启用
      require(!tierRepo.existsByAmountIdr(amountIdr), "已存在相同金额的档位", "admin.err.config.tierAmountExists");
      require(tierRepo.countByEnabledTrue() < 4, "已有 4 个启用档位，请先停用一个", "admin.err.config.tierCapReached");
      PawCoinTopupTier t = PawCoinTopupTier.create("t" + amountIdr, amountIdr);   // enabled=true，sort_order 先置 0
      tierRepo.save(t);
      resortAll();                                                                 // 全部档位按 amount_idr 升序重排 1..n
      List<ConfigChangeLog> logs = List.of(ConfigChangeLog.of(ConfigType.TOPUP_TIER,
              "tier." + t.getTierKey() + ".created", "", String.valueOf(amountIdr), adminId));
      changeLogs.saveAll(logs);
      audit.record(adminId, AuditActions.TIER_CREATED, "config", "tier:" + t.getTierKey(), "新建充值档位 " + amountIdr + " IDR");
      return t;
  }
  ```
  - [x] `PawCoinTopupTier` 加静态工厂 `create(tierKey, amountIdr)` 与 `setSortOrder`（现无 setter）
  - [x] `setTierEnabled(enabled=true)` 分支前加同一 advisory lock + `countByEnabledTrue() >= 4 → tierCapReached`（AC2）
  - [x] `resortAll()`：`findAll` 按金额排序后逐个 `setSortOrder(i+1)`

- [x] **T3 · Controller**（AC2/AC3）
  - [x] `POST /admin/config/tiers`（`EDIT_AUTH`，`@RequestParam long amountIdr`）→ PRG + toast `admin.flash.config.tierCreated`
  - [x] `GET /admin/config/tiers/disabled`（`VIEW_AUTH`）→ 返 `admin/fragments/config-tiers-disabled :: table` fragment；启停端点成功后若为 `HX-Request` 返两表 fragment（oob），否则 PRG（现状）

- [x] **T4 · 模板**（AC1/AC2/AC3）
  - [x] `config.html` 档位卡：主表 `th:each="t : ${enabledTiers}"`；注脚；`<a hx-get="/admin/config/tiers/disabled" hx-target="#tiers-disabled" hx-swap="innerHTML">查看已停用（N）</a>`（N=0 不渲染）；右上「＋ 新建档位」→ `<details>` 内联表单（金额 input[min=1,step=1]）过渡；抽屉版随 6.3 模板 D
  - [x] 新 fragment `fragments/config-tiers-disabled.html`：灰底表 + 每行 `POST …/tiers/{id}/enabled(enabled=true)` 启用钮

- [x] **T5 · 三语与审计码**（AC5）：`AuditActions.TIER_CREATED`；key 三包 + 默认包

- [x] **T6 · 测试**
  - [x] L1：新建成功 → key/排序/日志/审计；重复金额 422；第 5 个启用 422；停用后再启用达上限 422；`DbTopupTierProvider.tiers()` 含新档且不含停用；两线程并发新建只成功一个（用 `ExecutorService` + latch）
  - [x] L0：`GET /admin/config/tiers/disabled` 无权限 403；有权限 200 fragment

- [x] **T7 · 云端执行须知**：同 6.1

---

## Dev Notes

### 🔴 现状只有启停端点，「新建」是本版新增功能（AB-22A，D-24）

`AdminConfigService.setTierEnabled` 是唯一档位写入口；档位由 `V78` 种子四档（`10k/25k/50k/100k`）写死。UI 稿 7-3 已画交互稿，PRD §8 AB-22A 定了规则。

### 🔴 tier_key 规则 = `"t" + amount_idr`（就绪度评审修正）

存量 key 是 `10k` 这类人工字串；新建一律 `t<金额>`，与 `uq_pawcoin_topup_tiers_key` 天然不冲突，也不会出现 `25k` vs `25500` 歧义。App 端 `TopupTierDto.id` 就是 `tier_key`，旧客户端只会看到启用档位，不会因新 key 格式出错（它只做 `equalsIgnoreCase` 回传）。

### 🔴 ≤4 上限在服务层，用事务级 advisory lock

表上没有「启用数 ≤4」的约束，只能服务层数；两个运营同时新建 / 启用会双双通过 `count < 4`。`pg_advisory_xact_lock` 随事务自动释放，不需要 `unlock`。同一把锁同时保护 `createTier` 与 `setTierEnabled(true)`。

### 🔴 「查看已停用」是 D-25 的产品决定：停用不能变单向

`config.html` 现状把全部档位平铺一张表，本 story 拆成两张。别把已停用行直接藏掉——那样运营再也点不回「启用」。

### 现状代码要点

| 文件 | 现状 | 改什么 | 保留 |
|---|---|---|---|
| `config/domain/PawCoinTopupTier.java` | 字段 tierKey / amountIdr / enabled / sortOrder；只有 `setEnabled` | + `create()` 工厂、`setSortOrder` | — |
| `config/repository/PawCoinTopupTierRepository.java` | `findAllByOrderBySortOrderAsc / findByEnabledTrueOrderBySortOrderAsc / findByTierKey / countByEnabledTrue` | + 三个派生查询 | — |
| `admin/config/service/AdminConfigService.java` | `setTierEnabled`（保底 ≥1、变更日志 `tier.<key>.enabled`、审计 `CONFIG_UPDATE_TOPUP_TIER`） | + `createTier`、`resortAll`、启用上限 | 保底 ≥1 护栏 |
| `admin/config/web/AdminConfigController.java` | `POST /admin/config/tiers/{id}/enabled` PRG | + `POST …/tiers`、`GET …/tiers/disabled` | 启停端点路径 |
| `pay/service/DbTopupTierProvider.java` | 只出启用档位，空则回退内置默认，`byId` 只认启用 | **不改** | 全部 |
| `templates/admin/config.html:119-140` | 全部档位一张表 + 行内切换钮 | 两表 + 新建入口 | 切换钮端点 |

### 迁移约定

本 story **无 Flyway 迁移**（表结构够用）。

### 测试标准 / 云端

沿用 `AdminShareRewardConfigIntegrationTest` 范式；并发测试放 L1。云端只 L0。

### References

- [Source: PRD-v1.3.0-admin.md#8. AB-22A、#5 ③ 第 10 条]、[决策日志 D-24 / D-25]、[architecture-v1.3.0-admin-delta.md#AD-7]、[epics-v1.3.0-admin.md#Story 6.2]、[UI 稿 7-1 / 7-2 / 7-3]
- [Source: petgo-backend …/admin/config/service/AdminConfigService.java#setTierEnabled]、[…/pay/service/DbTopupTierProvider.java]、[…/db/migration/V78__init_platform_config.sql]

## Dev Agent Record

### Agent Model Used

claude-fable-5-1（云端 headless session，2026-09-09）

### Debug Log References

- 云端 L0：`./mvnw -B clean package`（排除需真库的 Spring 上下文测试类）→ BUILD SUCCESS，1828 tests，0 failures；`AdminConfigServiceTest`（+3：新建档位 key / 全量重排 / 日志 / 审计，重复金额 · 达上限 · ≤0 拒绝且不落库，第 5 个启用拒绝而停用照常）、`AdminMessagesExternalizedTest` / `AdminMessagesParityTest`、`AdminTemplateStructureTest`（新 fragment 过守卫）全绿。无迁移（表结构够用）。
- `check-i18n-keys.sh` OK（+14 key：`admin.config.tier.{note,showDisabled,disabledTitle,disabledEmpty,newButton,newTitle,amount,newHint,btn.create}`、`admin.flash.config.tierCreated`、`admin.err.config.{tierAmountMin,tierAmountMax,tierAmountExists,tierCapReached}`）。
- 复审（bmad-code-review，CONFIRMED 1 条已修 + PLAUSIBLE 7 条全部处理 + 偏差说明 2 条）：① 🔴 L1 用 `findTop100ByOrderByChangedAtDesc().size()` 做计数差，共享库过百行必红 → `changeLogs.count()` + `findTopByOrderByIdDesc`；② 并发测试只捕 `AppException`，其它异常会留孤儿档位连锁打红「默认 4 档」断言 → worker 捕 `Exception`、`finally shutdownNow`，清理改按本测试用过的 `tier_key`（`t<金额>`）删，不依赖运行时收集的 id；③ `freeOneSlot()` 假设库里无停用档位、「查看已停用（1）」硬编码 → 已有停用档位直接复用、链接断言改正则 `（\d+）`；④ `setTierEnabled` 停用分支不持锁、启用分支锁前已读 → `lockTiers()` 移到方法第一行（两个运营同时停用不会减到 0，同时启用不会重复记日志）；⑤ htmx refresh 后「查看已停用（N）」过期 / 未展开却被动展开 → 链接抽成 fragment `tiers-disabled-link` 随两表 oob；表单带 `expanded`（已停用表内启用钮固定 1，主表停用钮由 `hx-vals` 读折叠区状态）→ 展开态才替换已停用表，否则只回空占位；⑥ 金额无上限（`tier_key VARCHAR(16)` 溢出成 500、超 QRIS 单笔上限）→ `MAX_TIER_AMOUNT = 100,000,000` + `admin.err.config.tierAmountMax` + input `max`；⑦ 注释兜底断言 / refresh 主目标留空 `<span>` 让 `:empty` 失效 → 注释改解析级、断言锚 `<summary class=…>`、主目标无内容；⑧ `<details>` 内联表单在 flex 标题行里挤到 h2 右侧 → `.wb-title-row` 换行 + `details[open]` 占满整行；`.tiers-disabled` 灰底。⑨ `resortAllForTest` 测试专用 public 方法 → 删除，测试侧自行重排；`lockTiers()` 的 `em == null` 分支保留（L0 单测直接 `new` 服务，无 EntityManager；生产由 Spring 注入必非 null），记为偏差。⑩ 偏差说明：AC3「抽屉 / 弹层」= `<details>` 内联表单（story T4 允许过渡，抽屉版随 6.3 模板 D）；新建走原生 POST + PRG（重复 / 达上限 = flash error 回显，只有启停 htmx 端点出真 422，与 T3 一致）；`config.html` 首次引入 `admin-core.js`，6.1 KTP 卡的 `data-confirm` 由此真正生效（6.1 本意）；主表停用钮改纯 `hx-post`，无 JS 降级；并发用例为弱验证（无锁时窗口也极小）。

### Completion Notes List

- **L1/L2 待本地验收**：① `AdminTopupTierIntegrationTest`（真库；档位表全局共享且无删除端点 → 测试新建的档位在 `@AfterEach` 直接从仓储删除并恢复排序 / 启用态，避免污染 `PawCoinTopupOptionsIntegrationTest` 的「默认 4 档」断言）：新建成功 → `tier_key = t<金额>`、`enabled=true`、全部档位 `sort_order` 按金额升序 1..n、`config_change_logs` 一条（`TOPUP_TIER` / `tier.t<金额>.created` / old 空 new 金额）+ 审计 `TIER_CREATED`、toast；`DbTopupTierProvider.tiers()` 立即含新档且不含停用档、`byId` 对停用档位拒绝；重复金额（含已停用）422、第 5 个启用 422、停用后再启用达上限 422（htmx 行内 err 422）、金额 0 → error、`12.5` 绑定层 400；`GET /admin/config/tiers/disabled` `config.view` 200 fragment / 无权限 403；整页主表只列启用中 + 「查看已停用（1）」链接 + 「＋ 新建档位」；htmx 再启用 → 200 两表 oob + toast；两线程并发新建只成功一个（`ExecutorService` + latch，advisory 锁串行）。② L2：本地 stag 对照 UI 稿 7-1 / 7-2 / 7-3：主表 ≤4 行 + 注脚、点「查看已停用（N）」原地展开灰底第二表、每行「启用」、右上「＋ 新建档位」展开内联表单、创建后以启用态出现在主表、达上限 / 重复金额行内 err、toast 三语。
- **AC1 只显启用中**：`GET /admin/config` 的 `tiers` 改为 `enabledTiers()`（`sort_order` 升序），主表 `#tiers-enabled`（fragment `admin/config :: tiers-enabled`）；表下注脚 `admin.config.tier.note`「仅展示当前启用的档位，最多 4 档」；金额千分位。
- **AC2 查看已停用与重新启用**：`disabledCount > 0` 才渲染「查看已停用（N）」链接（`hx-get /admin/config/tiers/disabled` → `fragments/config-tiers-disabled :: table` 原地换 `#tiers-disabled`，灰底 `.row-muted`）；每行「启用」= 既有 `POST /admin/config/tiers/{id}/enabled`（`enabled=true`）；`setTierEnabled` 第一行取 advisory 锁再读状态，启用分支查 `countByEnabledTrue() < 4`，否则 422 `admin.err.config.tierCapReached`；既有保底 ≥1（`keepOneTier`）不变。htmx 请求成功回 `:: refresh`（主目标 `#tiers-inline-error` 清空 + 主表 / 「查看已停用（N）」链接按 id oob + 折叠区已展开（`expanded=1`）时已停用表 oob、未展开只回空占位 + toast），失败由 `AdminBusinessExceptionAdvice` 出 422 行内 err；非 htmx 维持 PRG。`config.html` 补引 `admin-core.js`（CSRF 头注入 / toast / data-confirm）。
- **AC3 新建档位**：卡右上「＋ 新建档位」`<details>` 内联表单（`config.edit`；抽屉版随 6.3 模板 D）→ `POST /admin/config/tiers`（`amountIdr`）→ `AdminConfigService.createTier`：`1 ≤ amountIdr ≤ 100,000,000`（`tierAmountMin` / `tierAmountMax`）→ `pg_advisory_xact_lock(hashtext('pawcoin_topup_tiers'))`（事务级，随提交 / 回滚释放；与启用共用同一把锁）→ `existsByAmountIdr`（含已停用，`tierAmountExists`）→ `countByEnabledTrue() < 4`（`tierCapReached`）→ `PawCoinTopupTier.create("t" + amount, amount)`（enabled=true，sort 0）→ `resortAll()`（`findAllByOrderByAmountIdrAsc` 逐个 `setSortOrder(i+1)`）→ 变更日志一条 + 审计 `AuditActions.TIER_CREATED`（target `config` / `tier:t<金额>`）→ PRG + toast `admin.flash.config.tierCreated`；失败 flash error 回显。
- **AC4 不提供删除；App 不变**：无删除端点；`DbTopupTierProvider` 未改（只出启用档位、按 `sort_order`、`byId` 只认启用）。
- **AC5 三语与审计码**：`AuditActions.TIER_CREATED`；14 个 key 四包同批。
- **结构说明**：`PawCoinTopupTier` +`create()` 工厂、`setSortOrder`；仓储 +`findByEnabledFalseOrderBySortOrderAsc / existsByAmountIdr / countByEnabledFalse / findAllByOrderByAmountIdrAsc`；`PlatformConfigService` +`disabledTiers() / disabledTierCount()`；`AdminConfigService` +`createTier / resortAll / lockTiers`（`@PersistenceContext EntityManager`，L0 单测下为 null 跳过锁）、`MAX_ENABLED_TIERS / MAX_TIER_AMOUNT`；`AdminConfigController` +`GET /admin/config/tiers/disabled`、`POST /admin/config/tiers`，`setTierEnabled` 加 htmx 分支。

### File List

- petgo-backend/src/main/java/com/tailtopia/config/domain/PawCoinTopupTier.java、repository/PawCoinTopupTierRepository.java、service/PlatformConfigService.java
- petgo-backend/src/main/java/com/tailtopia/admin/config/service/AdminConfigService.java、web/AdminConfigController.java、admin/audit/service/AuditActions.java（+`TIER_CREATED`）
- petgo-backend/src/main/resources/templates/admin/config.html（档位卡两表 + 链接 fragment + 新建入口 + `admin-core.js`）、fragments/config-tiers-disabled.html（新增）；static/admin/admin.css（标题行换行 / 灰底）
- petgo-backend/src/main/resources/i18n/messages{,_zh_CN,_en,_id}.properties（+14 key）
- 测试：admin/config/AdminTopupTierIntegrationTest（新增 L1）、admin/config/service/AdminConfigServiceTest（+3 L0）
- **L1 清理提醒**：档位表无删除端点，`AdminTopupTierIntegrationTest` 按 `tier_key` 删本测试新建档位并恢复启用态 / 排序；若本地某轮中途失败留下 `t1xxxxxx` 孤儿档位，手工 `DELETE FROM pawcoin_topup_tiers WHERE tier_key LIKE 't1%'` 后再跑 `PawCoinTopupOptionsIntegrationTest`。
