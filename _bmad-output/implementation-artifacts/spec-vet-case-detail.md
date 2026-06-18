---
title: '兽医端第三屏：案例详情/抢单预览页 1:1 还原（vet-case.html）'
type: 'feature'
created: '2026-06-17'
status: 'done'
baseline_commit: 'fa97ea3'
context:
  - '{project-root}/_bmad-output/pages/vet-case.html'
  - '{project-root}/_bmad-output/fidelity-audit.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 抢单详情页 `vet_request_detail_page.dart` 当前是裸 Material AppBar + 朴素 ListView（审计 🟡）：缺原型 `vet-case.html` 的深色顶栏 #2B2540、红色倒计时框、宠物信息卡、AI 分诊框、主诉/图片卡、双钮 CTA，与已还原的兽医工作台首页/待接单卡片视觉割裂。

**Approach:** 按原型 1:1 重排页面结构，全部走 `AppColors` token（深顶栏 vetTopBar、薄荷 vetPrimary、黄框 goldTint 等），复用待接单卡片同源的宠物身份格式化逻辑（种类/性别/年龄/@主人）。**只改 UI 呈现层，不动 3 分钟预览计时 / 5s 轮询 / 接单原子写 / 三态返回这套已验证的状态机。**

## Boundaries & Constraints

**Always:** 颜色一律用 `AppColors` token（禁硬编码 hex）；文案一律走 arb（en+id 双语）；宠物身份字段全 nullable → `petName==null` 优雅降级（不显身份卡）；保留 `vetRequestAccept`、`vetPreviewCountdown` 两个 ValueKey；保留接单成功跳 `/vet/conversation/:id`、三态 `_leave` 返回逻辑原样。

**Ask First:** 若需改动计时窗口、轮询间隔、接单/轮询的 repo 调用或路由跳转语义。

**Never:** 不动后端契约 / `VetInboxItem` 字段；不引入新依赖；不实现原型里数据模型没有的字段（品种 Domestic、入驻日期 "sejak Jan 2025"）——这些无数据来源，不臆造。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| AI 升级项 | source=AI_UPGRADE, aiDangerLevel, symptomPreview | 渲染宠物卡 + AI 分诊框（按等级配色/图标/标题）+ symptomPreview 作为 AI 评估正文 + 图片卡（imageCount>0） | N/A |
| DIRECT 项 | source=DIRECT, symptomPreview 可空 | 隐藏 AI 分诊框；显示「KELUHAN PEMILIK」主诉卡（symptomPreview 或 vetRequestNoDetail 兜底） | N/A |
| 身份缺失 | petName==null（真后端未下发/dev 深链） | 跳过宠物信息卡，其余区块正常渲染 | N/A |
| 无图片 | imageCount==0 | 不渲染图片卡 | N/A |

</frozen-after-approval>

## Code Map

- `petgo_app/lib/features/vet/presentation/vet_request_detail_page.dart` -- 目标页：重写 `build()` 呈现层（状态机方法保持不动）
- `petgo_app/lib/features/vet/presentation/vet_inbox_page.dart` -- 待接单卡片：宠物身份格式化（_speciesEmoji/_metaLine、等级色/标签）的还原参照系
- `petgo_app/lib/core/theme/colors.dart` -- token：vetTopBar #2B2540、vetPrimary #5BCBBB、vetPrimaryDeep、goldTint、coralTint、triage*、ink/textSecondary/textTertiary
- `petgo_app/lib/l10n/app_en.arb` / `app_id.arb` -- 新增/复用文案键
- `petgo_app/test/vet/vet_request_detail_test.dart` -- 同步倒计时与图片断言（design 变更）

## Tasks & Acceptance

**Execution:**
- [x] `app_en.arb`/`app_id.arb` -- 新增键：`vetRequestPreviewSubtitle`（Tinjau sebelum mengambil kasus）、`vetRequestPreviewLabel`（sisa waktu）、`vetRequestComplaintLabel`（KELUHAN PEMILIK）、`vetRequestPhotosLabel`（{count}，FOTO GEJALA）、`vetRequestAcceptCta`（Ambil Kasus → Mulai Chat）、`vetRequestAiEval{Green/Yellow/Red}`（Evaluasi AI: …）；改 `vetRequestDetailTitle`→「Detail Permintaan」/「Request detail」-- 原型文案
- [x] `vet_request_detail_page.dart` -- 重写 `build()`：自定义深顶栏（返回钮 34×34 白12%圆角 + 标题+副标题 + 红 #F0425A 倒计时框：MM:SS 大字 + sisa waktu 小字，保留 `vetPreviewCountdown` key）；body 依次 = 宠物信息卡（头像渐变圈+名+标签 Wrap+主人行）/ AI 分诊框（AI_UPGRADE，按等级 goldTint/coralTint/绿tint + 图标 + 标题）/ 主诉卡（DIRECT 显 KELUHAN PEMILIK）/ 图片卡（imageCount>0，占位缩略格 Row）；底部双钮（Lewati outline flex1 + vetRequestAcceptCta 薄荷 flex2，保留 `vetRequestAccept` key）-- 1:1 还原
- [x] `vet_request_detail_test.dart` -- 更新倒计时断言为裸 MM:SS（`startsWith('02:5')`）、图片断言为新标签文案；保留 accept/轮询/超时三态用例不变 -- design 同步

**Acceptance Criteria:**
- Given AI_UPGRADE 黄态项，when 进入详情页，then 顶栏深色 #2B2540 + 红倒计时框可见、宠物卡显名与标签、AI 分诊框黄底 + symptomPreview 正文。
- Given DIRECT 项（无 aiDangerLevel），when 进入，then 不显 AI 分诊框，改显 KELUHAN PEMILIK 主诉卡。
- Given 任意态，when 点「Ambil Kasus」且接单成功，then 跳 `/vet/conversation/:id`（状态机行为不变）。
- Given `flutter analyze` 与 `flutter test test/vet/vet_request_detail_test.dart`，when 运行，then 均 0 报错通过。

## Design Notes

单字段取舍：模型仅 `symptomPreview` 一个文本字段，原型却分「AI 评估」与「KELUHAN PEMILIK」两块。**按 source 二选一**渲染（AI_UPGRADE→AI 评估框承载该文本；DIRECT→主诉卡承载），不复制同一文本到两框、不臆造第二段文本。与现有「身份 mock 先做满、缺字段优雅降级」哲学一致。

AI 分诊框配色（复用待接单卡片 `_aiBoxBg` 同款 tint 映射）：YELLOW→goldTint+triageYellow、RED→coralTint+triageRed、GREEN→triageGreen 10% 底；标题 `vetRequestAiEval*`，图标用对应 emoji（🟡/🔴/🟢）。

## Verification

**Commands:**
- `cd petgo_app && flutter analyze` -- expected: No issues
- `cd petgo_app && flutter test test/vet/vet_request_detail_test.dart` -- expected: All tests pass
- `cd petgo_app && flutter gen-l10n` -- expected: 生成无误（新键被 codegen 识别）

**Manual checks:**
- 模拟器走「待接单 → Lihat Detail」（黄态 Oyen / DIRECT Mochi）逐项对照 `vet-case.html`：深顶栏、红倒计时、宠物标签、AI 框、双钮。
