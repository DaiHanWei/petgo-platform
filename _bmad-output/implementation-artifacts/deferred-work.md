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

## 2026-06-16 · ⑥个人中心 & 设置流：原型与 PRD/V1 冲突项（spec-me-settings-align：本条无代码改动）
⑥ 已完整且合规（注销双重确认+立即级联删除+i18n 全到位），以下原型元素**故意不实现**：
- **P-43「30 天冷静期 + 可撤销」**：与 story 7.3 AC2「立即级联删除」**直接冲突**（合规核心，CLAUDE.md 护栏点名勿埋违反点）。若产品确要引入宽限期删除，需：① 正式修订 PRD 7.3 删除决策；② 后端加 PENDING_DELETION 软删态 + @Scheduled 到期硬删 + 重登撤销；③ 前端 UI。属跨前后端 + 改合规决策的**专门后端为主 story**，不在 UI 对齐范围。当前实现（立即删除）正确，勿擅改。
- **P-43「永久删除项清单」展示**：合理 UX 增强、不涉契约，可后续在注销确认框补「将删除：宠物档案/成长记录/分诊记录…」清单 + arb 键（本条未做，低优）。
- **P-41 深色模式开关**：V1 仅浅色（无 dark theme），画开关=死按钮，违反「不留死按钮」。待 V2 dark theme 实现再加。
- **P-41 档案公开开关**：无 isPublic 字段/无后端契约/涉 PII 政策，需后端与隐私决策配合，延后。

---

## 兽医端薄荷主题（P0 色彩系统层）—— 审查 defer 项

> 来源：spec-vet-mint-theme.md step-04 三方审查。以下为 defer（非本步缺陷，留 P1 逐屏处理）。

- **vet 主题化组件 M3 色调偏差**：`AppTheme.vet` 仅以 `vetPrimary #5BCBBB` 种子化，M3 由种子推导 `colorScheme.primary/onPrimary`（紫… 实为薄荷的色调变体），与手绘 `backgroundColor: vetPrimary` 的按钮（如 vet_login）可能有细微色调/对比差。属 seed-based 主题固有行为；P1 逐屏精调 vet 组件时统一（或在 `vet` 主题显式 `copyWith(primary: vetPrimary, onPrimary: vetOnAccent)`）。
- **vet SnackBar 主题逃逸**：vet 屏 SnackBar 走 app 级 `ScaffoldMessenger`（在 `_vetScoped` 主题子树之上），解析到用户侧紫主题。当前 vet SnackBar 均为纯文本无强调色 → **无可见色差**，故 defer。若 P1 给 SnackBar 加薄荷强调/Action，需在 vet 子树内置独立 `ScaffoldMessenger` 或逐处包 `Theme`。
- **vet 预留 token 未接线**：`vetPrimaryDeep / vetSurface / vetSurface2 / vetTopBar / vetToolbar / vetOnAccent` 本步仅落 token，待 P1「兽医端深色顶栏 #2B2540 + 统计卡/队列卡/对话工具栏」结构性逐屏还原时接线（审计 P1 项）。

---

## 兽医工作台首页 dashboard（P1 第1屏）—— 审查 defer 项

> 来源：spec-vet-dashboard-home.md step-04 三方审查。非本单元缺陷/预存问题，留后续。

- **「完成」统计卡依赖未实现端点**：`done` 取 `vetRepository.history()` 长度；但 `/vet/consult-sessions/history`（及 `/in-progress`）**真后端 `VetConsultController` 未实现，仅 mock 应答**。真后端（`PETGO_MOCK=false`）下 history() → 404 → 优雅降级显示「—」（不崩）。同时 Active/History 两 tab 真后端也非功能态。属预存后端契约缺口（见 `backend-remaining.md` 范畴），需后端补 `/history`、`/in-progress` 端点；若要「今日完成」语义还需 server 端按当日过滤（当前 history 为全量）。
- **`waitingList` 加载失败被当空态**：dashboard `FutureBuilder` 用 `snapshot.data ?? []`，网络错误（401/500/断网）与「队列为空」展示相同的「No incoming requests」，无 error 分支/重试。**非本次回归**（旧 VetInboxPage 同款行为）。留「兽医端错误态专项」补 `snapshot.hasError` 分支 + 重试。
- **统计卡分色**：原型 3 卡有不同 tint（薄荷/黄浅底）；本步统一贴 `vetSurface` 薄荷浅底（token 化、不造色）。如需逐卡分色（队列薄荷/完成绿/评分黄），P1 精修时再细化。

---

## 兽医待接单卡片（P1 第2屏）—— defer 项

> 来源：spec-vet-queue-cards.md step-04。

- **抢单卡缺宠物 meta（名/种类/年龄/主人）**：原型 vet-queue 卡含「Mochi · Kucing · Betina · 1 thn · @aditya」，但 `VetInboxItem` 仅 sessionId/source/aiDangerLevel/symptomPreview/imageCount/waitingElapsedSeconds，**后端无这些字段契约** → 本步 omit（不造结构化假数据；symptomPreview 里的宠物描述属症状叙述文本、非结构化字段）。补全需：后端 `VetInboxItem` DTO 加 petName/species/sex/ageLabel/ownerHandle + 前端 model + mock，再在卡顶渲染。属跨前后端 contract 扩展。
- **等待时间无小时进位**：`waitingElapsedSeconds ~/ 60` 仅显分钟（如 3600s→「60 min ago」），原型也只用分钟，V1 可接受；若需 >60min 显小时后续加。

## OSS 媒体地基（spec-2-1-oss-single-bucket-l2.md step-04 审查带出，既有/非本次引入）

> 来源：Story 2.1 单桶适配 L2 审查。以下为 review 暴露的既有债，非本次改动引入，不阻断本次验收。

- **前端 `OssUploader.put` 非 2xx 上传错误无领域映射**：OSS 直传返回 403/404/3xx 时 dio 裸抛 `DioException`，未 try/catch 映射为本地化上传失败（UX「底部 toast 3s」）。本次新增 public-read ACL 使 403 更易触发，但错误处理是既有路径。补全：在 `put`/`MediaUploadUseCase` 包装 OSS 失败为领域错误 + i18n toast；`followRedirects:false` 对 3xx 显式失败。
- **直传无 Content-MD5 完整性校验**：预签名上传未带 Content-MD5，在途字节截断/篡改 OSS 不拒收。V1 可接受；如需强一致补传 Content-MD5。


---

## PostHog 分析基建接入（2026-06-29，spec-posthog-analytics-integration）

> 来源：三路对抗式 review（Blind Hunter / Edge Case Hunter / Acceptance Auditor）。以下为本期「基建层」范围外、刻意延后的项，不阻断本次验收；多数为 `capture()` 业务事件铺开后才生效的潜伏项。

- **[重要] 底部 4 主 Tab 不发 `$screen`（Edge#10）**：`PosthogObserver` 挂 root navigator，靠 push/pop 取页面名；但 `StatefulShellRoute.indexedStack` 的 home/profile/triage/me 走 `goBranch` 切 IndexedStack 子树、各在 branch navigator（未挂 observer），切 Tab 不产生 push → **使用率最高的四个主屏自动埋点缺失**。仅 shell 外 push 的二级页正常追踪。后续需在 Tab 切换回调里手动 `Posthog().screen(...)` 补埋（属新增埋点工作，非本期基建范围）。
- **冷启动登出残留身份（Edge#1）**：用户登录后强杀 → token 失效 → 冷启动 `_restoreSession` 失败保持 guest（无状态转换）→ 监听器不触发 reset，PostHog 仍持久着上次 distinctId。常见情形是「同人自己的登出后浏览被归到自己」（个人设备，影响小）；仅共享设备才跨用户。修法（如需）：splash `ensureRestored()` 完成后若非登录态且曾 identify 过（prefs 标志）则 reset，避免纯游客每启动 churn 匿名 id。V1 暂接受。
- **事件 props 的 id 类键导出策略（Edge#9）**：`scrub` 黑名单未含 `petId`/`sessionId`/`postId` 等自增 id 类键；CLAUDE.md 护栏「自增 id 不外露」。当前 `capture` 未铺业务事件故未触发。铺埋点前需定策略：要么哈希后再带、要么禁带。
- **自由文本值含 PII（Edge#8）**：`scrub` 仅按键剥离，不查值；`{'query':'我的狗吐血'}` 这类值里的健康/联系文本仍会上报。约定：埋点时 query/note 等自由文本不入 props，或入前在调用点脱敏。
- **A→B 直切 / reset↔identify 抖动竞态（Edge#3/#13）**：已用「id 变化才触发 + 换人先 reset」缓解；极端快速 guest↔authenticated 交错下 method channel 保序但无序号保护，需端到端核实（低）。
- **USER 态 profile.id 为 null（Edge#4）**：后端某响应未回 id 时该用户既不 identify 也不 reset。属后端不变量，监听器 `id != null` 守卫有意为之。

## 2026-06-30 · bug-system 第一期评审延后项（spec-bug-system-pull-and-analyze）

来源：quick-dev step-04 三路评审（盲审/边界/验收）。均非阻断，已修的为 patch，下列 defer：

- **token 中途过期无刷新**：全流程复用一个 tenant_access_token，无过期重取；大表多图/慢网下，下载侧过期点之后截图全进 `_failures.json`，`list_records` 翻页中途过期会丢已累积记录。V1 表小、token ~2h 足够，暂不做 token 刷新/分页容错。表规模变大或拉取耗时拉长时再补。
- **download_media 对 Bitable 附件可能需 `extra` 参数**：`/drive/v1/medias/{file_token}/download` 个别场景需附 extra 定位信息。L2 不可离线验证，已在 `larkapi.py:download_media` 留注释；L2 实跑若截图全数失败优先查此处。

## 2026-07-01 · bug-system 第二期（反向同步）评审延后项

来源：spec-bug-system-reverse-sync step-04 三路评审。均非阻断，已修的为 patch，下列 defer：

- **写按名 / 校验按 ID 的维度错配**：`_update_record` 写 body 按字段名下发，FR9 白名单校验按字段 ID；二者一致性依赖 config 的 name↔id 与线上保持同步。`validate_writeback_config` 只保证白名单名在 [field_ids] 有映射，未校验该 ID 与线上真实字段一致。缓解：写前可加「拉取线上字段列表比对 config name↔id」的自检；或若 Lark 支持按 field_id 写则改按 ID 下发。正常运营（config 准确）下不触发。

## 2026-07-06 · 里程碑「点赞/评论」语义 —— 待产品拍板（bug 20260706-253）

来源：bug 20260706-253「首次喜欢和评论对应的里程碑没解锁」深挖定位。**非代码缺陷，是里程碑目标语义与提报期望不一致，需产品定口径后再决定是否改码。**

- **现状（代码事实）**：里程碑只定义**「接收方」语义** —— `MilestoneCatalog.java` 的 `C/D-S14「第一次被评论」`、`C/D-S15「第一次收到点赞」`、`G-S7/S8`（均 SYSTEM_AUTO），解锁目标是**内容作者（被点赞/被评论的人）**：`MilestoneAutoCompleteListener.onContentLiked` → `completeForOwner(authorId,"S15")`、`onContentCommented` → `completeForOwner(contentAuthorId,"S14")`。**全仓不存在「行为者本人首次点赞/评论」这一类里程碑。**
- **为何单账号测必不解锁（叠加抑制）**：`LikeService.like` 自赞不发事件（`post.authorId==userId` 直接 return）、`MilestoneAutoCompleteListener.onContentCommented` 自评 `commenterId==contentAuthorId` 直接 return。故用**同一账号**给自己内容点赞/评论，S14/S15 双重抑制、永不解锁；而 S1/S2/S5（行为者本人触发）正常，正好造成「只有这两个不解锁」的观感。
- **待产品选口径（二选一）**：
  1. **维持「接收方」语义** → 当前实现基本正确，属**测试方式问题**：需用**两个不同账号**互相点赞/评论验证（A 给 B 的内容点赞 → B 解锁「收到点赞」）。若确认，此 bug 关闭为「非缺陷/需正确复现」。
  2. **改为「行为者」语义（我首次点赞/评论就解锁）** → 需 `MilestoneCatalog` 新增行为者版里程碑节点 + `MilestoneAutoCompleteListener` 改用 `likerId`/`commenterId` 作为 `completeForOwner` 目标、且不排除自互动。属跨 catalog + 监听器的功能新增，需重新排期。
- **建议**：先与产品确认里程碑文案本意（"被评论/收到点赞" vs "首次评论/点赞"），再决定走 1 还是 2。定位置信度：高。

## Deferred from: code review of story 1.1-1.3 联审 (2026-07-11)
- PENDING 支付意图无本地过期扫描 → 僵尸意图堆积 + 极晚 settlement 仍入账。超出 1.3 AC，留未来 story（建议并入 Epic 3 限时支付的 @Scheduled 扫描范式统一做）。[PaymentIntentService]
