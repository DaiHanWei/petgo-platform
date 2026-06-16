# Deferred Work

延后处理项（评审中发现、非当前 story 阻断）。

## 2026-06-16 · 原型 V1.0.0 逐屏对齐（quick-dev 多目标拆分）
源真相：`_bmad-output/pages/`（拆分单页）+ `core-pages-UX.md` + `tokens.css`。本次 quick-dev 只做「① 认证 & 引导流」，以下 5 个流延后，之后一流一跑：
- **② 社区内容流**（~11 屏）：Feed 登录态 + 空/错态、内容详情、发布及审核中/被拒、通知中心 + 空态
- **③ AI 分诊流**（~6 屏）：问诊 Tab、症状上传、三态结果（绿/黄/红，含红态 5 秒锁）
- **④ 兽医问诊流**（~18 屏）：入口/等待/对话/评分/存档 + 兽医端工作台全套（V-*）
- **⑤ 成长档案 + 里程碑**（~13 屏）：档案/日历/建档/编辑/名片 + 里程碑列表/抽屉/图鉴/三级庆祝
- **⑥ 个人中心 & 设置**（~3 屏）：我的/设置/注销
另有 2 个原型缺页（他人迷你主页卡 3-8、版本更新弹窗 6-5）见 `prototype-supplement-list.md`，待设计补稿后实现。

## 2026-06-16 · 既有测试失败（非本次引入，属兽医流 ④）
- `petgo_app/test/consult/vet_chat_test.dart` 2 条用例在 **baseline（7e688b6）即失败**：断言旧种子文案 `puasakan makanan`（小写）、`Terima kasih dok`、`foto anabul`，但 `im_chat_placeholder.dart` 的 mock 种子已更新（`Puasakan…` 大写、文案重写、无 `foto anabul` 占位）。测试未同步。**不在「认证&引导流」spec 范围**，留兽医流（④）实现时一并更新断言。

## 2026-06-16 · auth-onboarding 评审 defer 项（spec-auth-onboarding-ui-align 评审产出）
- **Deeplink 冷启动经 Splash 的 L2 实测**：理论上 go_router 中平台 deeplink 会绕过 `initialLocation`（splash 不显示、直达目标），故无回归；但 headless 无法验证平台深链，需本地/真机实测「冷启动带推送深链 → 不被 Splash 的 `go('/home')` 冲掉」。
- **兽医登录态冷启动经用户品牌 Splash**：兽医冷启 `/splash` → `go('/home')` → redirect 同步重定向 `/vet/workbench`（不挂 HomePage）。功能正确，但兽医会看 ~2.2s 用户侧品牌过场。属兽医流（④）打磨项；若要兽医跳过/缩短 Splash，需让 Splash 完成回调感知 auth（当前刻意不耦合）。
- **`maybeRequestAfterFirstConsult` 无生产调用点**：「双时机取最早」里**首次问诊那一路从未在 lib/ 接线**（仅测试引用），目前建档时机是唯一实际触发。既有债（非本次引入），留 AI 分诊/问诊流实现时补接线。
- **push sheet 与 401 全局登录引导竞态**：两者都用 `rootNavigatorKey` 弹层；建档庆祝页弹 P-09 期间若恰逢 401 续期失败弹登录引导，可能 pop 错对象。正常流不触发，低风险，留观察。

## 2026-06-03 · spec-ui-mockup-alignment 评审遗留
- **问诊严重度胶囊配色一致性**：`triage_page.dart` `_SeverityChip` 的 GREEN/YELLOW 用实底+白字（沿用 `_FreeBadge` 既有模式），而 `UX_DESIGN.md:44-47` 为 green/yellow 定义的是浅底+深字对（`triage-green-bg`/`triage-green-text`）。RED 实底白字与稿一致。非阻断（在 spec Design Notes「三色映射」授权范围内）；YELLOW(#E0A458)+白字对比度可能 <AA 4.5:1，后续可改浅底深字并补 token。

## 2026-06-16 · ②社区内容流剩余项（i18n 收口已完成，spec-social-content-i18n-align）
- **发布审核状态流 P-39 / P-39b / P-39c**：原型画的是「异步 AI 审核中 ~5s → 通过/被拒（拒因列表+修正重提）」，但现状是**发布时同步 422 拦截**（`content-text-blocked`/`image-blocked` 已实现）。两模型不一致：忠实还原 P-39b 异步审核态需改后端为 @Async 审核状态机（动契约，超 UI 对齐范围、与 V1 轻量姿态冲突）。待产品确认走「同步态美化」还是「异步审核流」后再做。发布成功反馈（P-39）当前为 toast，可一并定。

## 2026-06-16 · ④兽医问诊流剩余项（i18n+测试修复+推送接线已完成，spec-vet-consult-i18n-align）
- **P-25 存档确认页**：原型有「存进宠物档案/私人不分享」bottom sheet；后端 `POST /archive-decisions`（ArchiveDecisionRequest/Response）已存在，可建。本条未做，待排期。
- **V-08 兽医最终诊断表单**：原型有诊断/建议/是否需药/药物/复查时间/恶化征兆表单，但**后端无对应端点**（仅 `/{id}/end`，无诊断字段契约）。建它需先定后端契约，超「不动后端」范围，延后到 Epic 5 后端补诊断 API 后再做。
