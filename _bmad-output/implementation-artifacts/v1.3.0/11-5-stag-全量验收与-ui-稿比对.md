---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 11
story: 11.5
ad: [AD-2, AD-3, AD-9, AD-11, AD-12]
decisions: [D-8, D-15, D-17, D-29]
depends_on: [全部 Epic 1~10, 11-1, 11-3]
---

# Story 11.5: stag 全量验收与 UI 稿比对

Status: ready-for-dev

> 自包含 story，**L2 为主，必须本地 + stag**（云端 headless 不能做）。与用户沟通用中文。
> ⚠️ 只允许操作 staging 资源（`petgo-server-stag` / 8085 / `petgo_stag` / Redis `-n 3` / `~/.env.petgo-stag` / `scripts/deploy-backend-stag.sh`）；生产一律禁止（CLAUDE.md 部署纪律，`stag-guard` 会拦）。

## Story

As a 产品，
I want 在 staging 上按 UI 稿 8 泳道逐页比对形态、核对看板数字与待办角标，
so that 上线前有一份与 1.1.6 同等级的 L2 视觉验收报告。

## Acceptance Criteria

**AC1 · stag 部署**
**Given** 本分支合入 `dev_1.3.0` 且 Epic 10 已完成（电商线已合入）
**When** 在 `dev_1.3.0` 执行 `./scripts/deploy-backend-stag.sh`
**Then** `curl -s https://api-stag.tailtopia.id/actuator/health` = UP；Flyway 本版 8 支迁移全部应用（`flyway_schema_history` 查到 `add_admin_accounts_security_version` … `add_pricing_config_passport_prices`）`[L1]`
**And** 顶栏出现黄色「STAG」角标（`@StagOnly`）`[L2]`

**AC2 · 逐页形态比对（UI 稿 8 泳道）**
**When** 按 Story 11.2 Dev Notes 的路由清单逐页打开
**Then** 每页对照 UI 稿对应帧与逐页规格节：摘要条指标集合 / 表格列集合与顺序 / 抽屉页签与区块 / 操作表（按钮 / 端点 / 确认 / 成功后行为）四项一致；差异逐条记入报告，分「设计缺陷 / 实现缺陷 / 有意偏离（附 D 号）」`[L2]`
**And** 模板 A 六页：处置成功 toast 2s + 自动选中下一条 + 左栏行与页签计数同步刷新；键盘 ↑/↓ **无**效果（D-14）`[L2]`
**And** 模板 B 页：行点开抽屉 / ESC 关闭 / `?open=<id>` 深链自动开 `[L2]`

**AC3 · 看板数值核对（AD-2/3，D-15/D-29）**
**Given** stag 库已有 2026-07-17 至昨天的 `ops_daily_metrics`（首次跑批或 `POST /admin/dashboard/materialize` 触发）
**When** 任取 3 个日期（含一个数据稀疏日）
**Then** 18 项指标 × ALL 口径：页面数值 = `ops_daily_metrics` 行值 = 口径表定义（对 stag 库直跑 `MetricQueryParityTest` 同款 SQL）；9 项双口径的 REAL 值 ≤ ALL 值 `[L2]`
**And** 缺数据日在图上为断点不为 0；7/30 天切换不整页刷新；付费卡对无 `payment.view` 账号不渲染（D-17）`[L2]`

**AC4 · 待办中心角标同源**
**When** 在任一待办页处置一条
**Then** 侧导航组角标、该项计数、页内页签计数三处同时变化且数值相等；`GET /admin/nav/badges` 返回值与三处一致 `[L2]`

**AC5 · 关键流闭环抽查**
**Then** ① 超管换绑某测试账号邮箱 → 该账号在另一浏览器的下一次点击被踢到 `?relogin`（D-1）；② 角色配置页给「客服」加一个码 → 客服账号重登后可见对应菜单（D-2）；③ 暖评页签二发一条 → 显示「已提交，审核通过后显示」→ A1 送审页签或机审通过后可见（D-4）；④ 场所新建手填坐标 → 列表 / 抽屉正确显示；合并两场所 → 子表归入保留方 `[L2]`

**AC6 · 报告**
**Then** 产出 `_bmad-output/implementation-artifacts/v1.3.0/L2-视觉验收报告-v1.3.0.md`（骨架见 Dev Notes），含日期 / 环境 / 范围 / 结论 / 验收方法 / 通过项 / 缺陷与修复 / 未能确认项；截图存 `_bmad-output/_archive/l2-shots-v130/`（png gitignore） `[L2]`

---

## Tasks / Subtasks

- [ ] **T1 · 部署与冒烟**（AC1）：`git checkout dev_1.3.0 && git pull && ./scripts/deploy-backend-stag.sh`；health；`docker exec petgo-postgres psql -U petgo -d petgo_stag -c "select version, description from flyway_schema_history order by installed_rank desc limit 12"`
- [ ] **T2 · 造测试数据**：stag 建 2 个测试后台账号（CUSTOM / 客服角色）、1 个仅 `payment.view` 缺失的账号、2 个待合并场所、1 篇冷帖；虚拟账号池已有则复用
- [ ] **T3 · 逐页比对**（AC2）：Playwright 脚本批量截图（与 11.2 共用），人工对照 UI 稿逐帧勾表
- [ ] **T4 · 看板核对**（AC3）：跑 `POST /admin/dashboard/materialize`（SUPER_ADMIN，stag）→ 取 3 日 → 页面 / 表 / SQL 三方对照表
- [ ] **T5 · 角标与关键流**（AC4 / AC5）
- [ ] **T6 · 写报告**（AC6）；缺陷回对应 story 修后回归，报告更新
- [ ] **T7 · 云端执行须知**：本 story 全部本地；云端不可执行

---

## Dev Notes

### 报告骨架（照 1.1.6 `L2-视觉验收报告-Epic2.md` 风格）

```markdown
# V1.3.0 后台「AB-15A~22A + AB-19A 重构」L2 视觉验收报告

- **日期**：YYYY-MM-DD
- **环境**：stag（api-stag.tailtopia.id / petgo_stag / dev_1.3.0 @ <commit>）
- **范围**：8 泳道 45 路由页面 + 看板数值 + 待办角标 + 5 条关键流
- **结论**：发现 N 个缺陷，已修复 M 个并回归；K 项未能实机确认（原因）

## 一、验收方法
（部署 → 造数 → 逐页比对四项 → 看板三方对照 → 角标同源 → 关键流）

## 二、通过的项（按泳道分表：页 | 摘要条 | 列 | 抽屉 | 操作表 | 结果）

## 三、缺陷与修复（编号 | 页 | 现象 | 归属 story | 修复 commit | 回归）

## 四、有意偏离 UI 稿（附 D 号 / 就绪度报告条目）

## 五、未能确认项
```

### 🔴 stag 专用件门控

`STAG` 角标、B12 模拟回调三钮、`POST /admin/dashboard/materialize` 都靠 `@StagOnly`（2.3a）。验收时确认**生产 profile 下不注册**：本地以 `prod` profile 起一次 `mvn spring-boot:run`，`GET /admin/dashboard/materialize` 应 404/405 而非 403。

### 🔴 看板对照的 SQL

`MetricQueryParityTest`（3.2）用的 SQL 就是对照口径；stag 上直接对 `petgo_stag` 跑同一批 SQL（只读，`ops_readonly` 账号即可，memory：ops-readonly-db-account）。**不要**对生产库跑任何东西。

### 🔴 Epic 10 门控

AD-12：商城组 17 帧的比对要等电商线合入 `dev_1.3.0`；若合入晚于其余 Epic，本 story 分两轮：第一轮 7 泳道 + 看板 + 角标 + 关键流，第二轮商城组，报告分节标注。

### 与 11.2 共用

同一次部署、同一套截图脚本；11.2 出语言报告，本 story 出形态报告。

### References

- [Source: epics-v1.3.0.md#Story 11.5] · [Source: ui-v1.3.0-admin.html 全部泳道] · [Source: 后台重构逐页规格.md 全文] · [Source: architecture-v1.3.0-delta.md#Development Workflow Integration / AD-2 AD-3 AD-12] · [Source: 决策日志.md D-1 D-2 D-4 D-8 D-14 D-15 D-17 D-29]
- [Source: CLAUDE.md#staging 部署纪律] · [Source: scripts/deploy-backend-stag.sh] · [Source: _bmad-output/implementation-artifacts/v1.1.6/L2-视觉验收报告-Epic2.md（报告范式）]

## Dev Agent Record

### Agent Model Used

（dev-story 填写）

### Debug Log References

### Completion Notes List

### File List
