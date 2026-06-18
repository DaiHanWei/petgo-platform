---
title: '兽医端第四屏：进行中会话页 1:1 还原（vet-chat.html）'
type: 'feature'
created: '2026-06-18'
status: 'done'
baseline_commit: '4312cfb'
context:
  - '{project-root}/_bmad-output/pages/vet-chat.html'
  - '{project-root}/_bmad-output/fidelity-audit.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 兽医进行中会话页 `vet_conversation_page.dart` 当前是标准 Material AppBar + 黄色辅助面板（审计 🟡，兽医端 44% 最低）：缺原型 `vet-chat.html` 的深色顶栏 #2B2540（宠物/主人身份 + Akhiri Sesi）、工具条 #1A2B28（FR-5 四工具）；且共享的 `ImChatPlaceholder` 气泡/发送钮用 `AppColors.mint`（实为 #845EC9 紫）→ 兽医页气泡渲染成紫而非薄荷（审计「兽医端该薄荷却用紫」）。

**Approach:** 重写会话页的「顶栏 + FR-5 区」呈现层：自定义深色顶栏承载身份与结束会话钮，深色工具条承载 FR-5 四工具（点 Template 展开辅助参考面板）。消息区/输入栏沿用已对齐原型的 `ImChatPlaceholder`，仅把其气泡/发送色由硬编码 `AppColors.mint` 改为 `Theme primary`（用户侧→紫、兽医侧→薄荷各自正确）。给 `VetSession` 补 nullable 宠物身份（Mock 先做满、后端随后补，同待接单卡片决策），喂顶栏身份行。

## Boundaries & Constraints

**Always:** 颜色走 `AppColors` token / `Theme primary`（禁裸 hex）；文案走 arb（en+id 双语）；保留 `vetEndSession`、`vetAssistAdopt`、`vetAssistPanel`、`vetChatSend` 四个 ValueKey 与 `_endSession`（含 AppTheme.vet 弹窗）/IM 登录登出逻辑原样；宠物身份字段全 nullable → 缺失时顶栏优雅降级（显 `consultConversationTitle` 兜底，不崩）。

**Ask First:** 改 IM 登录/登出、`_endSession` 服务端调用或 `/vet/workbench` 跳转语义；改 `ImChatPlaceholder` 的种子内容/收发逻辑（本次仅许动其气泡/发送**颜色**）。

**Never:** 不动后端真实契约字段（仅给前端模型 + mock 加 nullable 身份）；不引入新依赖；不实现无数据来源的工具功能（Daftar Obat / Darurat 仅做 chip + 「V1 未提供」提示，不臆造药品库/急诊流）；不动消息气泡的种子文案与 L2 真机收发（仍 deferred）。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| AI 会话 | session.source=AI_UPGRADE, aiContext.dangerLevel=YELLOW/GREEN, 身份齐 | 顶栏显「名(主人)」+「等级 · 种类 · 性别 · 年龄」状态行；工具条 Template 默认激活展开辅助参考 | N/A |
| DIRECT 会话 | source=DIRECT, hasAiContext=false | 顶栏状态行省略等级（仅种类/性别/年龄或兜底）；无 AI 上下文卡 | N/A |
| 身份缺失 | petName==null（真后端未下发） | 顶栏标题回落 `consultConversationTitle`，状态行隐藏 | N/A |
| 点 Daftar Obat / Darurat | 无数据来源 | SnackBar 提示「V1 belum tersedia」，不导航、不崩 | N/A |
| 点 Template Saran | assist.aiReferenceReply 有值 | 展开辅助参考面板（薄荷左边框卡 + Pakai 钮，保留 key） | reply 空→隐藏面板 |

</frozen-after-approval>

## Code Map

- `petgo_app/lib/features/vet/presentation/vet_conversation_page.dart` -- 目标页：重写顶栏（深 #2B2540 + 身份 + Akhiri Sesi）+ FR-5 工具条（深 #1A2B28 + 四 chip + 展开 Template 面板）；状态机/IM 逻辑不动
- `petgo_app/lib/features/consult/presentation/im_chat_placeholder.dart` -- 气泡 `me` 底色 + 发送钮底色：`AppColors.mint`→`Theme.of(context).colorScheme.primary`（仅颜色）
- `petgo_app/lib/features/vet/domain/vet_inbox_item.dart` -- `VetSession` 加 nullable petName/ownerHandle/petSpecies/petSex/petAgeMonths + fromJson
- `petgo_app/lib/core/mock/mock_backend.dart` -- `_vetSessionView` 补宠物身份（按 sessionId 取既有队列样本，如 8101=Oyen）
- `petgo_app/lib/core/theme/colors.dart` -- token：vetTopBar #2B2540、vetToolbar #1A2B28、vetPrimary #5BCBBB、triage*、coral #F0425A
- `petgo_app/lib/l10n/app_en.arb` / `app_id.arb` -- 新增工具/标签键
- `petgo_app/test/consult/vet_chat_test.dart` -- 验证仍绿（断言文本，不受配色变更影响）

## Tasks & Acceptance

**Execution:**
- [x] `app_en.arb`/`app_id.arb` -- 新增：`vetChatToolsLabel`(TOOLS/ALAT)、`vetChatToolTemplate`(📋 Template Saran)、`vetChatToolDrugs`(💊 Daftar Obat)、`vetChatToolHistory`(📁 Riwayat)、`vetChatToolEmergency`(🔴 Darurat)、`vetChatToolUnavailable`(Belum tersedia di V1 / Not available in V1) -- 原型工具文案
- [x] `vet_inbox_item.dart` -- `VetSession` 补 5 个 nullable 身份字段 + fromJson 解析 -- 喂顶栏
- [x] `mock_backend.dart` -- `_vetSessionView` 注入对应 sessionId 的宠物身份（复用待接单样本数据）-- dev/mock 顶栏可见真身份
- [x] `im_chat_placeholder.dart` -- `me` 气泡 + 发送钮底色改 `colorScheme.primary`（兽医子树→薄荷，用户子树→紫）-- 修「兽医用紫」
- [x] `vet_conversation_page.dart` -- 重写 `build()`：深顶栏（返回钮 34×34 白12% + 宠物头像圈 + 「名(主人)」+ 状态行「等级·种类·性别·年龄」+ Akhiri Sesi 红描边钮，保留 `vetEndSession`）；FR-5 深工具条 #1A2B28（TOOLS 标签 + 四 chip，Template 激活薄荷态）；点 Template 展开辅助参考面板（薄荷左边框 + Pakai，保留 `vetAssistPanel`/`vetAssistAdopt`）；Obat/Darurat 点击 → SnackBar 未提供；保留 `ImChatPlaceholder` 消息区 -- 1:1 还原
- [x] AI 上下文卡 -- **CHECKPOINT 决议 A2：严格按原型移除** `VetAiContextCard` 不再挂载于会话页（等级折进顶栏）；`vet_ai_context_card.dart` 文件保留（待接单详情等他处可能复用），仅本页不引用

**Acceptance Criteria:**
- Given AI_UPGRADE 黄态会话，when 进入，then 顶栏深色 #2B2540 + 身份行含「AI: pantau ketat · Kucing · …」、工具条 #1A2B28 含四 chip、Template 激活。
- Given 兽医子树渲染消息气泡，when 自己发消息，then 气泡与发送钮为薄荷 #5BCBBB（非紫）。
- Given 点 Akhiri Sesi 确认，when 结束，then 走原 `_endSession` → `/vet/workbench`（逻辑不变）。
- Given `flutter analyze` + `flutter test test/consult/vet_chat_test.dart` + 兽医全套，when 运行，then 全绿。

## Spec Change Log

- **实现期微调（accent 参数替代 Theme primary）**：原 Approach 拟用 `colorScheme.primary` 给气泡/发送上色，但 M3 `fromSeed` 对种子色调偏移 → 渲染暗青绿而非品牌薄荷 #5BCBBB（模拟器验收发现）。改为给 `ImChatPlaceholder` 加显式 `accent` 参数（默认 `AppColors.mint`，兽医页传 `AppColors.vetPrimary`），直取品牌 token 避免失真。意图不变且更准。KEEP：显式 accent，勿回退 colorScheme.primary。

## Design Notes

**AI 上下文卡（CHECKPOINT 已决：A2 移除）：** 原型 chat 不含独立 AI 上下文卡（等级折进顶栏，症状/图片假定兽医已在详情页看过）。Dai 选 A2——严格按原型，会话页不再挂 `VetAiContextCard`；等级仅在顶栏状态行体现。`VetAiContextCard` 组件文件保留不删（他处复用）。

**配色映射（紫→薄荷）：** 工具条激活 chip、Template 面板左边框用 `vetPrimary`（原型用户侧紫 #845EC9 在兽医页换薄荷，与全局 mint 换肤一致）；Akhiri Sesi 用 `coral`(#F0425A) 描边。

**身份行构造：** `${petName} (${ownerHandle})` + 副行 `[等级]·[种类]·[性别]·[年龄]`，各段缺则跳过；等级取 `aiContext.dangerLevel`，其余取 `session` 新增字段。复用 `vetAiContextLevel*` / `vetSpecies*` / `vetSex*` / `vetAge*` 键。

## Verification

**Commands:**
- `cd petgo_app && flutter analyze` -- expected: No issues
- `cd petgo_app && flutter test test/consult/vet_chat_test.dart test/vet/` -- expected: All tests pass
- `cd petgo_app && flutter gen-l10n` -- expected: 新键被 codegen 识别

**Manual checks:**
- 模拟器走「待接单 → 接单 → 会话页」或 dev 深链 `/vet/conversation/8101`，对照 `vet-chat.html`：深顶栏身份、工具条四 chip、薄荷气泡、Akhiri Sesi。
