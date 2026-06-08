# ⚠️ 待评估变更候选 — 尚未生效（DO NOT use as source of truth）

> 本目录是**产品于 2026-06-06 提交的新 PRD（V1.0.0）及配套 UX**，作为「变更信号」引进，
> 供 `bmad-correct-course` 做影响评估用。**它还不是权威规格。**

## 现行权威 vs 本候选（务必区分）

| | 现行权威（继续以此为准） | 本目录（候选，待评估） |
|---|---|---|
| PRD | `../PRD.md`（V1.0, 2026-06-01，驱动 46 story） | `V1-0-0PRD20260606.md`（V1.0.0, 2026-06-06） |
| UX 设计 | `../UX_DESIGN.md` | `UX_DESIGN.new.md` |
| UX 体验 | `../UX_EXPERIENCE.md` | `UX_EXPERIENCE.new.md` |
| 决策日志 | — | `decision-log.md`（旧版 5/29 决策，随源目录带入） |

**在 `bmad-correct-course` 产出变更提案并人工确认之前，46 story / 架构 / sprint 一律继续以 `../PRD.md` 为准。**

## 来源

`/Users/dai/work/petGo/V1版本/`（独立 repo，非本 monorepo）。原文件名保留以便追溯；
两个 UX 文件加 `.new` 后缀，避免与 `../` 下同名现行文件混淆。

## 已知主要增量（初步骨架比对，详见后续变更影响报告）

- 版本号 V1.0 → **V1.0.0**
- §4.7 推送通知：~68 行 → **~273 行（大幅扩写）**
- §4.6 个人中心：~89 行 → ~154 行（扩写）
- §4.14 通知中心/问诊历史：扩写
- §4.2 攒局 Gath：移至 **V2 → 移至 1.5.0**（排期变）
- 功能区 4.0–4.14 与骨架不变 → **属精修迭代，非产品转向**

## ⚠️ 命名待定（不阻塞）

新 PRD 标题用占位 `[App Name]`，decision-log 提到 "Pomo Go"，现行代码/规格用 "PetGo"。
**产品本周才定名。** 在拍板前：

- 一律按 **TBD** 处理，**不改包名 / 品牌资产 / 文案**；
- correct-course / diff 分析照常推进，命名不是它们的前置；
- 定名后单独收口（包名、文案、品牌资源）。

## 下一步

1. 生成《逐节变更影响报告》（基于本目录 PRD vs `../PRD.md`）
2. 新开 context 跑 `bmad-correct-course`，喂入本目录 + 影响报告 → 产出变更提案
3. 按提案收口：`bmad-prd`(update) / 受影响 story 的 `bmad-create-story` / `bmad-sprint-planning`
