---
baseline_commit: TBD
---

# Story 16.5: Feed 埋点补 `feed_tab` 与 `rank_mode`

Status: ready-for-dev

## Story

As a 数据，
I want Feed 埋点能区分「哪个 Tab」和「哪条排序路径」，
so that 推荐序的效果能被归因，而不是和分类 Tab 的数据混在一起。

---

> 🔴 **不加这两个属性，FR-95 的效果无法归因** —— 而这个 FR 的参数本来就要在发版后校准，
> 归因不了等于校准也做不了。

---

## Acceptance Criteria

### AC1 — 两个属性（L1）

**Then** Feed 相关埋点须带 `[L1]`：
- `feed_tab`：`all` / `moment` / `tips` / …
- `rank_mode`：`recommend` / `chrono`

**And** 🔴 两者**都要有** `[L1]`

> 只有 `feed_tab` 不够：降级链级别 4 会让 **ALL Tab 也走时间倒序**，
> 那时 `feed_tab=all` 但 `rank_mode=chrono` —— 把它算进推荐序的效果里就是错的。

### AC2 — 命名规范（L0）

**Then** 事件名与属性名符合 `模块_对象_动作`（动作在词尾且为动词）`[L0]`
**And** 🛡 契约测试必须绿；需扩白名单时**写明理由，不改产品定的名字** `[L0]`

> Story 10-1 已把白名单机制与"扩表不改名"的先例立好，照它做。

### AC3 — 回写文档（L0）

**Then** `docs/analytics-posthog-tracking.md` 补本 story 的事件与属性 `[L0]`
**And** 🛡 与 `埋点清单v116.md` 两边一致 `[L0]`

> ⚠️ Story 10-1 的对账发现过：清单里有十条事件名与实现不符（PRD 改过、清单漏同步）。
> 本 story 新增的名字**两边同时写**，不要留第二次对账的活。

### AC4 — 🛡 曝光类埋点不在本 story（L0）

**Then** 🛡 **不新增曝光埋点** `[L0]`

> ⚠️ PRD §8.3 已核实数据侧**没有曝光类埋点**，而灰度观测建议的"人均浏览深度"依赖它。
> 但本补充**没有把它列为交付项**，且 16-1 的曝光记录用的是"下发即记"、**不依赖客户端上报**。
> 🔴 **记录一处缺口**：没有曝光埋点 ⇒ "人均浏览深度"这个观测指标**做不出来**（OQ-B8 提到的四个指标里的一个）。

---

## Tasks / Subtasks

- [ ] **T1 · 两个属性接入 Feed 埋点**（AC1）
- [ ] **T2 · 契约测试与白名单**（AC2）
- [ ] **T3 · 回写两份文档**（AC3）
- [ ] **T4 · 测试**
  - [ ] L1：ALL Tab 正常态 → `rank_mode=recommend`
  - [ ] L1：🔴 ALL Tab 降级到级别 4 → `rank_mode=chrono`（钉住 AC1 那条"只有 feed_tab 不够"）
  - [ ] L1：非 ALL Tab → `rank_mode=chrono`
  - [ ] L0：契约测试绿；两份文档事件集合一致

## References

- [Source: V1.1.6/1-1-6补充prd.md#3.1] §2 第 6 条末段 · §8.3 灰度与验证
- [Source: V1.1.6/埋点清单v116.md] 命名规范与"按属性验收"的口径

---

## Dev Agent Record

### Context Reference

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

---

## Change Log

| 日期 | 变更 |
|---|---|
| 2026-08-24 | 由 1.1.6 补充 PRD 拆出（拆前做过一次代码核查，五处过期陈述已修正），status = ready-for-dev |
