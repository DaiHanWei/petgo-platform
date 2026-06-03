# Investigation: 登录弹窗 vs 独立登录页——设计依据溯源

## Hand-off Brief

> ⚠️ **2026-06-03 重大更正**：初版结论「UX 没有设计登录页」**错误**。见 Follow-up——用户提供的 `PetGo_V1_mockups.html` 中 **S01 就是独立全屏登录页**（且为第一屏）。根因实为**规划产物自相矛盾**，非 AI 即兴。

1. **What happened.** 弹窗本身有正当依据（PRD §4.0 FR-0B/0C → Story 1.4）；但 UX 可视化 mockup（`PetGo_V1_mockups.html` S01）设计的是**独立全屏登录页**，二者冲突。实现时（Story 1.3）把已建好的登录页降级为「仅自测」、改用 Story 1.4 弹窗，**静默选边、未标记冲突**（Confirmed）。
2. **Where the case stands.** 根因 Confirmed：**两份规划产物对「登录形态」存在未解决的冲突**——mockup S01=独立登录页（无游客跳过）vs PRD FR-0A/0B/0C=游客优先+弹窗。dev 选了 PRD，把对应 S01 的 `login_page.dart` 架空成孤儿路由（`/login` 无人导航）。
3. **What's needed next.** 仍无需立即改代码。需用户做**产品决策 + 修订规划单一事实源**：登录到底走 S01 独立页还是 FR-0C 弹窗？定了之后走 `bmad-correct-course` 改 PRD/mockup 之一并记入 CROSS-STORY-DECISIONS。

## Case Info

| Field            | Value                                                          |
| ---------------- | ------------------------------------------------------------- |
| Ticket           | N/A（用户口头提问溯源）                                          |
| Date opened      | 2026-06-03                                                     |
| Status           | Concluded                                                     |
| System           | petgo_app (Flutter) · petgo-platform monorepo · branch main   |
| Evidence sources | 源码（auth feature + router）、PRD.md、UX_DESIGN.md、UX_EXPERIENCE.md、Story 1.3/1.4/1.5、epics.md |

## Problem Statement

用户（Dai）认为：「为什么登录页做成弹窗的形式？在我眼里 UX 有明确设计登录页。AI 做弹窗的依据是什么？如果是 epic，那 epic 的依据是什么。溯源。」——隐含前提是「弹窗 = AI 擅自决定，违背了 UX 设计的独立登录页」。**只调查不改代码。**

## Evidence Inventory

| Source   | Status      | Notes     |
| -------- | ----------- | --------- |
| 弹窗实现源码 | Available | `login_guide_controller.dart` / `login_soft_sheet.dart` / `login_hard_dialog.dart` |
| 独立登录页源码 | Available | `login_page.dart`（Story 1.3 产物）+ 路由 `/login` |
| 触发接线 | Available | `dio_client.dart:44`、`auth_guard.dart:24`、`home_page.dart:54` |
| PRD 需求 | Available | PRD.md §4.0 FR-0A/0B/0C/0D（PRD.md:110-153） |
| UX 设计系统 | Available | UX_DESIGN.md（无登录页组件） |
| UX 体验/屏幕图 | Available | UX_EXPERIENCE.md Screen Map（无登录页屏幕） |
| Story 文件 | Available | 1-3 / 1-4 / 1-5 |

## Confirmed Findings

### Finding 1: 弹窗的代码自带「Story 1.4」溯源标注

**Evidence:** `petgo_app/lib/features/auth/domain/login_guide_controller.dart:17`

**Detail:** 类注释明写「登录引导协调器（Story 1.4）」，暴露 `showSoftSheet`（`showModalBottomSheet`，41 行）与 `showHardDialog`（`showDialog`，64 行）。组件本身不含触发条件，由调用方注入。

### Finding 2: Story 1.4 把需求依据直接指向 FR-0B / FR-0C

**Evidence:** `_bmad-output/implementation-artifacts/1-4-登录引导浮层与登录后回跳.md:13-25`

**Detail:** Story 标题即「登录引导浮层」。正文：「实现两个自包含组件——软性登录推荐浮层（**FR-0B**，底部半屏…每 session 最多一次）与强登录引导弹窗（**FR-0C**，含『登录后继续使用该功能』）」。明确「无后端改动」「登录态由 Story 1.3 提供」。

### Finding 3: FR-0B / FR-0C 是 PRD 的原始产品设计（弹窗写死在需求里）

**Evidence:** `_bmad-output/planning-artifacts/PRD.md:124-139`（§4.0 用户登录与引导）

**Detail:**
- FR-0A（PRD:116）：未登录用户可直接浏览首页，**无强制登录拦截**。
- FR-0B（PRD:124-131）：浏览至第 3 页 → 软性登录推荐浮层，「**底部弹起半屏**，主 CTA『Google 登录』」，每 session 最多一次。
- FR-0C（PRD:133-139）：点击核心功能（问诊/咨询/发布/建档）→ 登录引导**弹窗**，「视觉强度高于 FR-0B」「登录后自动回到触发点」。
- FR-0D（PRD:142）：Google 登录为唯一方式，**首次授权自动建号，无需区分登录/注册入口**。

→ 弹窗（bottom sheet + dialog）是 PRD 白纸黑字的设计，**不是 AI 即兴**。

### Finding 4: 两份 UX 文档都没有「独立登录页」屏幕——用户前提与证据相反

**Evidence:**
- `UX_DESIGN.md` 组件清单（标题扫描）：Bottom Tab Bar / Masonry Card / Triage Result Card / Publish Compose / Do's and Don'ts——**无登录页组件**。
- `UX_EXPERIENCE.md:30-79`（Navigation Model + Screen Map）：「**首页是唯一无需登录可访问的 Tab**；其余四个未登录点击触发登录引导（FR-0C）」。Screen Map 从 🏠 首页 起，**全图无任何登录页/Login Screen 节点**。

**Detail:** UX 设计的是「**游客优先 + 上下文登录弹窗**」体验，与弹窗实现完全一致。用户记忆中的「UX 明确设计了登录页」在两份 UX 规格里都找不到对应物。

### Finding 5: 独立 LoginPage 是「孤儿路由」——存在但流程中无人跳转

**Evidence:**
- 路由定义：`app_router.dart:61` `GoRoute(path: '/login', builder: (c, s) => const LoginPage())`。
- 全仓搜索 `go('/login')` / `push('/login')` / `replace('/login')` → **0 命中**（无任何代码导航到它）。
- 实际登录入口接线全部走弹窗：`dio_client.dart:44`（401 拦截 → showHardDialog）、`auth_guard.dart:24`（受控入口门控 → showHardDialog）、`home_page.dart:54`（滚动深度 → showSoftSheet）。

**Detail:** `login_page.dart` 是 Story 1.3「Google 登录与 JWT 签发」的产物（一个可独立渲染的登录页），但在 Story 1.4/1.5 确立弹窗门控后，主流程没有任何路径导航到 `/login`。它是被弹窗机制取代的、事实上的死路由。

## Deduced Conclusions

### Deduction 1: 弹窗的完整依据链（自下而上可追溯）

**Based on:** Finding 1 → 2 → 3 → 4

**Reasoning + Conclusion:**
```
弹窗代码 (login_soft_sheet / login_hard_dialog / login_guide_controller)
   ↑ 代码注释自标 Story 1.4
Story 1.4「登录引导浮层与登录后回跳」
   ↑ 正文依据
FR-0B（软浮层·底部半屏）/ FR-0C（强弹窗·门控）
   ↑ 出处
PRD §4.0「用户登录与引导」(PRD.md:110-153)
   ↑ 一致佐证
UX_EXPERIENCE Navigation Model + Screen Map（游客优先·无登录页·FR-0C 弹窗）
```
每一环都有 `path:line` 级 Confirmed 证据。**AI 没有发挥**——它执行的是 Story 1.4，而 Story 1.4 忠实于 PRD FR-0B/0C，PRD 又与 UX 体验图一致。

### Deduction 2: 「弹窗 vs 登录页」不是 bug，是产品策略选择

**Based on:** Finding 3（FR-0A 游客优先）+ Finding 4（UX 无登录页）

**Conclusion:** 这是经典「游客先用、上下文转化」策略：不在启动时立登录墙，让用户先浏览，在『滚动到一定深度』（软）或『点核心功能』（硬）时才弹登录。把它做成独立全屏登录页反而违背 FR-0A 与 UX 体验图。所以争议点是**产品方向偏好**，不是实现缺陷。

## Hypothesized Paths

### Hypothesis 1: 用户记忆中的「UX 登录页」可能来自早期稿/外部稿/兽医登录页混淆

**Status:** Open

**Theory:** 用户确信 UX 设计过登录页，但现仓两份 UX 文档都没有。可能来源：① 某版被删/未入库的早期 UX 草稿；② 外部设计稿（Figma 等）未同步进仓；③ 与 **兽医登录页**（FR-29，PRD:758-765，确为「独立页面」账号密码登录、`/vet/login`、`vet_login_page.dart` 真实存在并接线）记忆混淆；④ 与孤儿 `LoginPage`（Story 1.3）混淆。

**Would confirm:** 用户指出具体 UX 文件/截图/Figma 链接显示用户侧独立登录页。

**Would refute:** 已部分 refute——现仓 UX_DESIGN + UX_EXPERIENCE 全文无用户侧登录页屏幕。

## Source Code Trace

| Element       | Detail                                                                 |
| ------------- | --------------------------------------------------------------------- |
| 弹窗起点      | `login_guide_controller.dart:41`(softSheet) / `:64`(hardDialog)        |
| 触发来源      | `dio_client.dart:44`(401) · `auth_guard.dart:24`(Tab门控) · `home_page.dart:54`(滚动) |
| 设计条件      | PRD FR-0A(游客优先)+FR-0B(滚动软浮层)+FR-0C(门控强弹窗)                  |
| 孤儿登录页    | `login_page.dart` @ `app_router.dart:61` `/login`——0 处导航引用         |
| 相关 Story    | 1.3(登录页+JWT) · 1.4(弹窗引导) · 1.5(受控门控接线)                       |

## Conclusion

**Confidence:** High（根因 Confirmed，依据链每环 `path:line` 可证，可重现）

**结论：登录弹窗不是 AI 即兴，是 PRD §4.0（FR-0B/FR-0C）的原始产品设计**，经 Story 1.4 实现，与两份 UX 文档「游客优先、无登录墙、上下文弹窗」的体验设计完全一致。**用户「UX 明确设计了独立登录页」的前提，在现仓 UX_DESIGN.md 与 UX_EXPERIENCE.md 中均无对应屏幕**，证据与前提相反。

附带发现：代码里确实存在一个独立 `LoginPage`（Story 1.3 产物，`/login` 路由），但它是**孤儿路由**——主流程无人跳转，已被弹窗门控取代。

## Recommended Next Steps

### Fix direction（仅在用户确认要改产品方向时；当前不改代码）

若用户希望「登录走独立全屏页」而非弹窗——这是**改 PRD/UX 决策**，应走 `bmad-correct-course`：
- 选项 A：维持现状（弹窗），符合 FR-0A 游客优先策略。
- 选项 B：把孤儿 `LoginPage` 接入流程（FR-0C 触发时 `context.push('/login')` 取代 hardDialog），并相应改 PRD FR-0C + UX——这是产品级变更，影响转化漏斗，需明确决策记录。
- 无论哪种，孤儿 `/login` 的去留应明确（接入或删除），避免死代码。

### Diagnostic

向用户求证 Hypothesis 1：能否指出记忆中「UX 登录页」的具体出处（Figma / 早期文档 / 截图）。若指的是兽医登录页（FR-29），则那确为独立页且已实现，无需改动。

## Side Findings

- 兽医侧登录是**独立页面**（FR-29 / `vet_login_page.dart` / `/vet/login`），且真实接线（`app_router.dart:50-55` redirect 收口到工作台）。用户侧与兽医侧登录形态不同是 PRD 明确设计（Google 弹窗 vs 账号密码独立页）。
- `login_guide_controller.dart:114-116` 有一条重要修复注释：弹窗登录路径也必须写入 `authController`，否则成功后仍被当游客弹回首页——说明弹窗路径曾踩过登录态未落地的坑。

## Follow-up: 2026-06-03

### New Evidence — 用户提供 `PetGo_V1_mockups.html`（推翻初版结论）

- **F6 (Confirmed)：mockup S01 = 独立全屏登录页，且是第一屏。**
  `_bmad-output/planning-artifacts/PetGo_V1_mockups.html:60-103`。Group A「Authentication & Feed」首屏 `S01 · Login Page`：注释 `No status bar, no nav for pre-login`（66 行）、居中 Logo 块（🐾 PetGo + tagline）、`Masuk dengan Google` 全宽按钮（78-87）、服务条款/隐私小字（88-90）、底部 `Masuk Akun Dokter Hewan` 兽医登录链接（94-99）。**整页无任何「游客浏览/跳过」入口。**
- **F7 (Confirmed)：整份 mockup 没有任何 FR-0B/0C 登录弹窗屏幕。**
  21 个屏幕（S01–S21，四组）全清单中登录只有 S01（用户）+ S19（兽医），均为独立全屏页；**软浮层/强弹窗在 mockup 中不存在**。
- **F8 (Confirmed)：`login_page.dart` 就是 S01 的实现，但被 Story 1.3 主动降级为「自测入口」。**
  `login_page.dart:21-24` 注释原文：「最小登录入口页（Story 1.3 自测入口）……正式触发场景（软浮层/强弹窗）由 Story 1.4 提供，**本页可被其复用或仅作自测**」。页面结构与 S01 一一对应：appTitle/logo(84) + Google 按钮(88-99) + 兽医链接(102-106) + 条款/隐私链接(108-115)。

### Updated Hypotheses

- **Hypothesis 1 → Confirmed（用户前提成立，初版判断 refute 错误）。** 用户记忆的「UX 设计了登录页」属实，出处即 `PetGo_V1_mockups.html` S01。初版仅查 UX_DESIGN.md / UX_EXPERIENCE.md 两份 markdown 而漏读 mockup HTML，导致误判——**取证范围不全是本案最大教训**。

### 真根因（更正）：规划产物之间未解决的冲突，被实现阶段静默选边

| 产物 | 对「登录」的设计 | 性质 |
| --- | --- | --- |
| `PetGo_V1_mockups.html` S01 | 独立全屏登录页，无游客跳过 → **登录优先** | 可视化稿 |
| PRD §4.0 FR-0A/0B/0C | 游客优先、无登录墙、上下文软浮层+强弹窗 → **游客优先** | 功能需求（带 AC） |
| UX_EXPERIENCE.md Screen Map | 首页唯一免登录 Tab，其余 FR-0C 弹窗 → **游客优先** | 体验文档 |
| UX_DESIGN.md | 无登录页组件（中性，未表态） | 设计系统 |

- **冲突点 (Deduced)**：S01 作为「pre-login、无游客跳过」的首屏，与 FR-0A「未登录可直接浏览首页、无强制登录拦截」直接矛盾（mockup 是登录墙；PRD 是游客先用）。两者出自不同心智模型，**从未被显式调和**。
- **dev 为何选 PRD (Deduced)**：① PRD/architecture/UX_DESIGN/epics 是 CLAUDE.md 声明的单一事实源，mockup HTML 与 UX_EXPERIENCE 不在该清单；② FR-0B/0C 是带 AC 的功能需求，story 直接可执行；③ Story 1.3 据此把 S01 实现降级为自测、Story 1.4 建弹窗。结果：S01 对应的 `login_page.dart` 沦为孤儿路由。
- **失误性质 (Deduced)**：不是「AI 即兴做弹窗」，而是**实现阶段遇到两份规划稿冲突时，按声明的事实源选了 PRD，却未把 mockup S01 被推翻一事显式标记/上报**——一次静默的规格取舍。

### Backlog Changes

- 已查（Done）：`epics.md:8` 把 `PetGo_V1_mockups.html` 列为官方输入 → S01 是正式规划稿。`architecture.md:201` 把「登录页前置同意+FR-0D 条款/隐私链接」列为**不可砍**合规地基。三处（epics/architecture/CROSS-STORY-DECISIONS）**均无对「S01 独立页 vs FR-0A 弹窗」的显式裁决** → 确认为未裁决冲突。

### 🔴 Side Finding（高价值·合规）：实际登录路径缺 FR-0D 前置同意

- **F9 (Confirmed→Deduced)**：`login_soft_sheet.dart` / `login_hard_dialog.dart` 全文 grep `terms|privacy|条款|隐私|agreement|协议|同意` **零命中**——实际接线的弹窗登录路径**不含**服务条款/隐私前置同意。FR-0D 同意链接**仅存在于孤儿 `login_page.dart`（`:108-115`）**。
- **影响 (Deduced)**：`architecture.md:201` 明列「登录页前置同意（FR-0D）」为**不可砍**合规项；但用户实际走的是弹窗 Google 登录（`login_guide_controller.dart:80 _attemptLogin → _login()` 直接发起，无同意闸）。**登录页被架空的连带后果是这条不可砍合规要求一并失效**。
- **待证 (Open)**：是否在 onboarding 或别处有全局同意兜底？若无，则正式登录路径缺法务前置同意，需在 correct-course 中作为**必修项**纳入（无论登录最终走页还是弹窗，FR-0D 同意必须挂在实际登录触发点）。

### Updated Conclusion

**Confidence: High。** 弹窗有正当依据链（PRD FR-0B/0C → Story 1.4），但**用户是对的**：UX mockup S01 确实设计了独立全屏登录页，且已被实现（`login_page.dart`）后在 Story 1.3 主动降级为自测、被 Story 1.4 弹窗取代、沦为 `/login` 孤儿路由。根因是**规划产物（mockup vs PRD）对登录形态的冲突在实现期被静默选边**，而非 AI 凭空发挥。处置属产品决策 + 单一事实源修订，应走 `bmad-correct-course`。
