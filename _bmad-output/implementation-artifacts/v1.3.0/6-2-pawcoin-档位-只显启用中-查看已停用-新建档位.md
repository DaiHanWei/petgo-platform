---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 6
story: 6.2
ad: [AD-7]
decisions: [D-24, D-25]
---

# Story 6.2: PawCoin 档位——只显启用中、查看已停用、新建档位（AB-22A + §5 ③ 第 10 条）

Status: ready-for-dev

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

- [ ] **T1 · 仓储与读服务**（AC1/AC2）
  - [ ] `PawCoinTopupTierRepository` 新增 `findByEnabledFalseOrderBySortOrderAsc()`、`existsByAmountIdr(long)`、`countByEnabledFalse()`
  - [ ] `PlatformConfigService` 新增 `disabledTiers()`；`AdminConfigController.GET /admin/config` 的 `tiers` 改为 `enabledTiers()` + `disabledCount`

- [ ] **T2 · createTier 服务层**（AC3）
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
  - [ ] `PawCoinTopupTier` 加静态工厂 `create(tierKey, amountIdr)` 与 `setSortOrder`（现无 setter）
  - [ ] `setTierEnabled(enabled=true)` 分支前加同一 advisory lock + `countByEnabledTrue() >= 4 → tierCapReached`（AC2）
  - [ ] `resortAll()`：`findAll` 按金额排序后逐个 `setSortOrder(i+1)`

- [ ] **T3 · Controller**（AC2/AC3）
  - [ ] `POST /admin/config/tiers`（`EDIT_AUTH`，`@RequestParam long amountIdr`）→ PRG + toast `admin.flash.config.tierCreated`
  - [ ] `GET /admin/config/tiers/disabled`（`VIEW_AUTH`）→ 返 `admin/fragments/config-tiers-disabled :: table` fragment；启停端点成功后若为 `HX-Request` 返两表 fragment（oob），否则 PRG（现状）

- [ ] **T4 · 模板**（AC1/AC2/AC3）
  - [ ] `config.html` 档位卡：主表 `th:each="t : ${enabledTiers}"`；注脚；`<a hx-get="/admin/config/tiers/disabled" hx-target="#tiers-disabled" hx-swap="innerHTML">查看已停用（N）</a>`（N=0 不渲染）；右上「＋ 新建档位」→ `<details>` 内联表单（金额 input[min=1,step=1]）过渡；抽屉版随 6.3 模板 D
  - [ ] 新 fragment `fragments/config-tiers-disabled.html`：灰底表 + 每行 `POST …/tiers/{id}/enabled(enabled=true)` 启用钮

- [ ] **T5 · 三语与审计码**（AC5）：`AuditActions.TIER_CREATED`；key 三包 + 默认包

- [ ] **T6 · 测试**
  - [ ] L1：新建成功 → key/排序/日志/审计；重复金额 422；第 5 个启用 422；停用后再启用达上限 422；`DbTopupTierProvider.tiers()` 含新档且不含停用；两线程并发新建只成功一个（用 `ExecutorService` + latch）
  - [ ] L0：`GET /admin/config/tiers/disabled` 无权限 403；有权限 200 fragment

- [ ] **T7 · 云端执行须知**：同 6.1

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

- [Source: PRD-v1.3.0-admin.md#8. AB-22A、#5 ③ 第 10 条]、[决策日志 D-24 / D-25]、[architecture-v1.3.0-delta.md#AD-7]、[epics-v1.3.0.md#Story 6.2]、[UI 稿 7-1 / 7-2 / 7-3]
- [Source: petgo-backend …/admin/config/service/AdminConfigService.java#setTierEnabled]、[…/pay/service/DbTopupTierProvider.java]、[…/db/migration/V78__init_platform_config.sql]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
