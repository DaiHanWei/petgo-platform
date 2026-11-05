# Story 5.2: @ 面板改造（App，E1–E6）

Status: review

> 版本 v1.4.2 · 主题 social-topic · 分支 `feat/1.4.2-social-topic`
> 口径优先级：`_bmad-output/planning-artifacts/v1.4.2/决策日志-social-topic.md` > PRD `追更话题/1-4-2追更宠物·里程碑·话题prd.md` §3.7 FR-157、§4、§7 > `architecture-v1.4.2-social-topic-delta.md`（§2.1、D-15、D-19、P-9、P-10、P-12，§1.2 第 8 条）> UI 定稿 `追更话题/ui-1-4-2-social-topic-integrated-v1.html` **E1–E6**（及 C2 / C4 的面板改造点，与 @ 同一组件）。
> 范围层：**只 App**（`petgo_app/`）。接口契约由 Story 5.1 提供。
> 🔴 前置（必须已合入本分支）：
> - **Story 5.1**：`GET /api/v1/me/mention-candidates?q=`、每项 `relation`（`following` / `follower` / `interacted`）、带 `q` 最多 20 条 / 不带最多 30 条、带 `q` 超频 429、`q` > 20 字 422。
> - **Story 4.4**：`lib/shared/rich_text/`（`body_tokens.dart` 共用解析器、`topic_mention_text_controller.dart` 即 `TopicMentionTextController`）已接入发布页正文与评论框；发布页 `#` 联想面板已做成「通栏贴键盘上沿、最高 220、按内容收缩、正文 `scrollPadding.bottom = 面板高度 + 20`」的承载形态（架构 §2.1、§5.2 `features/topic/presentation/widgets/topic_suggest_panel.dart`）。本 story **复用该承载位**放 @ 面板，不另造第二套定位逻辑。
> - 若开工时发现 4.4 的承载形态与下文「接口假设」不同，以 4.4 实际代码为准调整本 story 的接入方式，并在 Completion Notes 记一句；**不得**为 @ 另写一份贴键盘定位。
> 🔴 云端只跑 L0（`flutter analyze` / `flutter test`）；L2（模拟器视觉、还原度）待本地验收。

## Story

As a 正在写帖子或评论的用户,
I want 在 @ 后直接打字就能找到人,
so that 不用在过滤框和正文之间来回切。

## Acceptance Criteria

> 层级：L0 = `flutter analyze` / `flutter test`（headless 可跑）；L2 = Android 模拟器视觉 + 连真后端（`api.tailtopia.id` 或 stag）。本 story 无 L1。

**AC1 — 打字即查**〔L0〕（epics 5.2 AC1、PRD FR-157.2 查询时机、UI E1 / E2）
1. 正文 `@` 后**无字**：面板显示快捷列表（E1，调不带 `q` 的接口）。
2. `@` 后**至少 1 个字**：停止输入 **300ms** 后才发请求（`?q=<@后的字>`）；300ms 内继续打字不发请求；新请求发出时**丢弃上一个未返回的结果**（旧响应晚到也不得覆盖新结果）。
3. 「Cari nama…」过滤框**已移除**：`MentionPicker` 内无任何 `TextField`；`_filter` 控制器、`_filterField`、`_matches` 第二个条件全部删除；正文 `@` 后的字是唯一筛选条件。
4. 删回到 `@` 后为空 → 回到 E1 快捷列表（不发带 `q` 的请求）。

**AC2 — 面板位置**〔L0 / L2〕（epics 5.2 AC2、UI E1 改造点 ② ③）
1. **发布页**：@ 面板与 `#` 面板同一形态、同一承载位——通栏白底贴键盘上沿，上沿 1px `AppColors.border` + 向上淡阴影（0 −4 12，6% ink），**最高 220**、按内容收缩、超出在面板内滚动；候选行左右边距 **20**（与发布页内容边距一致）；面板出现期间正文 `scrollPadding.bottom = 面板实际高度 + 20`，光标行始终在面板上沿之上，收起后恢复默认。旧的「正文下方内联浮层」（`publish_compose_page.dart`:828-834）删除。
2. **评论框**：面板仍在评论输入条上方（`comment_composer.dart`:349-353 位置不动），**只去过滤框**；候选行左右边距 **16**（随评论页）。
3. 同一时刻发布页只出现一个面板（@ 与 # 互斥：光标处是 @ 查询就显示 @ 面板，是 # 查询就显示 # 面板）。

**AC3 — 面板内六种状态**〔L0〕（epics 5.2 AC3、UI E1–E6）
| 屏 | 触发 | 表现（全部在面板内） |
|---|---|---|
| E1 快捷列表 | `@` 后无字、快捷列表非空 | 候选行（28 头像 + 昵称 body 15，无等级徽章、不标关系类型），顺序 = 接口顺序 |
| E2 查询中 | `@` 后有字，防抖等待中或请求未返回（快捷列表首次加载同用） | 面板收缩为一行高，居中 18px 紫色转圈（`CircularProgressIndicator(strokeWidth: 2)`、**无底圈**、内边距 12），无文案 |
| E3 查询结果 | 接口返回非空 | 候选行，顺序 = 接口顺序（关注的 → 粉丝 → 互动过的），行样式同 E1 |
| E4 无结果 | 带 `q` 接口返回空 | 一行提示 `mentionNoMatch`「Tidak ada nama yang cocok」，无图标 |
| E5 查询失败 | 带 `q` 或快捷列表请求失败（网络 / 超时 / 5xx / 429 / 422） | 一行提示 `detailNetworkError`「Gagal memuat, silakan coba lagi」+ 紫色文字按钮 `commonRetry`「Coba lagi」（刷新图标 16 + 14 w600，可视高 33、热区 ≥ 44）；点按 → 用当前关键词重查 → 回 E2 |
| E6 一个人都没有 | `@` 后无字、快捷列表返回空 | `people_outline` 30 图标 + 标题 `mentionEmptyTitle` + 提示 `mentionEmptyHint`（**新文案**「Ikuti seseorang dulu, nanti kamu bisa menyebut mereka di sini」/「Follow someone first, then you can mention them here」） |
1. **E5 与 E6 必须区分**：取数失败不再落到 E6 空态（UI E5 说明：改为接口查询后需要区分，否则用户以为范围里真的没人）。
2. 点 E1 / E3 的行：插入「@昵称 」并高亮（高亮由 4.4 的 `TopicMentionTextController` 负责），记下 `userId`（`MentionDraft.insert`，提交仍走 `MentionDraft.userIdsIn`）。

**AC4 — 埋点**〔L0〕（epics 5.2 AC4、PRD §4 FR-157、P-9）
1. `mention_inserted` 在原有 `context` 之外新增 `relation`（`following` / `follower` / `interacted`，取被选候选的 `relation`；接口未带时按 `interacted` 兜底）与 `from_quick_list`（选中时 `@` 后关键词为空 = `true`，否则 `false`）。
2. 只在「真的插进去了」时报（被 5 人上限 / 字数上限拦住的不报，现状不变）；事件名与属性 Map **在调用点写字面量**，`{'context': …}` 仍是第一个键（守卫 `test/mention/mention_legacy_and_analytics_test.dart`:50-63 正则扫源码）。
3. 属性值不含昵称 / 正文等自由文本（`relation` 用枚举的 `wire`，同 `MentionContext` 写法）。

**AC5 — 回归与还原度**〔L0 / L2〕（epics 5.2 AC5）
1. 超过 5 人的提示 `mentionLimitReached`「Maksimal menyebut 5 orang」**位置不变**（仍是默认 `showAppToast`，不改成 `top: true`；话题超限的顶部提示是 4.4 的事）。
2. 字数上限拦截（发布页 `publishTextLimitReached`、评论 `commentLimitReached`）行为不变。
3. 换账号后 @ 候选不串号（`mentionCandidatesProvider` 已登记 `resetUserScopedCaches`，`app.dart`:402；若新增任何用户维度 provider 必须同样登记并更新 `test/app/user_scoped_cache_registry_test.dart`）。
4. L2：Android 模拟器连真后端，E1–E6 与评论框逐屏对照 UI 稿，**还原度 ≥ 95%**（对 UI 稿逐屏打分，记入 Completion Notes）。

## Tasks / Subtasks

- [x] **T0 开工核对**（全部 AC）
  - [ ] 确认 5.1 已合入：本地或 stag 后端 `GET /me/mention-candidates?q=a` 返回带 `relation` 的项
  - [x] 确认 4.4 已合入：找到发布页 `#` 面板的承载位（类名 / 参数），记下接口；核对 Dev Notes「现状」行号未漂移
- [x] **T1 数据层**（AC1 / AC4）
  - [x] `lib/features/mention/domain/mention_relation.dart`（新）：`enum MentionRelation { following('following'), follower('follower'), interacted('interacted') }`，`wire` 字段 + `static MentionRelation parse(String?)`（未知 / null → `interacted`）
  - [x] `MentionCandidate` 加 `relation` 字段，`fromJson` 解析
  - [x] `MentionCandidateRepository` 加 `Future<List<MentionCandidate>> search(String keyword, {CancelToken? cancelToken})`：`dio.get(ApiPaths.meMentionCandidates, queryParameters: {'q': keyword}, cancelToken: …)`；改写接口注释（删「不接受任何关键词参数」，写「只在关注 / 粉丝 / 互动三类范围内查，不是全局搜索」）
  - [x] 三个测试假仓储（`test/mention/mention_candidates_lifecycle_test.dart`:9、`mention_entry_test.dart`:29、`mention_picker_test.dart`:14）补 `search` 实现
- [x] **T2 `MentionPicker` 改造**（AC1 / AC3，UI E1–E6）
  - [x] 删 `_filter` / `_filterField` / `_matches` 第二条件 / `mentionFilterHint` 的使用；类注释改为「正文 @ 后的字是唯一筛选条件；接口只在三类范围内查」
  - [x] 关键词为空 → `ref.watch(mentionCandidatesProvider)`（快捷列表，保持 autoDispose、不重试）；关键词非空 → 组件内 300ms 防抖 + 请求序号 / `CancelToken` 丢弃旧结果（见 Dev Notes「查询状态机」）
  - [x] 六态渲染（E1–E6），新增 widget key：`mentionPickerLoading`、`mentionPickerError`、`mentionPickerRetry`；保留 `mentionPicker`、`mentionCandidate_<userId>`、`mentionPickerEmpty`
  - [x] 新增构造参数：`docked`（发布页通栏样式：无圆角无四周描边、上沿 1px border + 向上阴影）与 `rowHorizontalPadding`（发布页 20 / 评论 16）；默认值保持评论框现状外观
- [x] **T3 发布页接入**（AC2.1 / AC2.3 / AC4，D-19）
  - [x] 删 `publish_compose_page.dart`:828-834 内联 `MentionPicker`；把 @ 面板放进 4.4 的贴键盘承载位（与 # 面板互斥）
  - [x] `_onMentionSelected`（:261-293）埋点改为 `Analytics.capture('mention_inserted', {'context': MentionContext.post.wire, 'relation': candidate.relation.wire, 'from_quick_list': query.keyword.isEmpty});`（一行写，见 AC4.2）
- [x] **T4 评论框接入**（AC2.2 / AC4）
  - [x] `comment_composer.dart`:349-353 传 `rowHorizontalPadding: 16`（不传 `docked`）
  - [x] `_onMentionSelected`（:127-166）埋点同 T3（`MentionContext.comment.wire`）
- [x] **T5 文案**（AC3 E6，P-10，NFR5）
  - [x] `app_id.arb` / `app_en.arb` 改 `mentionEmptyHint` 的值为 PRD §7 定稿句（不新增 key）；`flutter gen-l10n`
- [x] **T6 测试**（AC 全部，NFR8）：见「测试」；`flutter analyze` 零警告、`flutter test` 全绿
- [ ] **T7 L2**（本地）：Android 模拟器连真后端走 E1–E6 + 评论框；还原度逐屏打分 ≥ 95%

## Dev Notes

### 现状（改动前必读，2026-11-04 核 `dev_1.4.2` @ c496e3c3；4.4 合入后发布页 / 评论框行号会漂移，以实际为准）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo_app/lib/features/mention/presentation/mention_picker.dart` | `ConsumerStatefulWidget`；`keyword` 来自 `MentionDraft.queryAt`（:35-39）；内含过滤框 `_filter`（:51）、`_filterField`（:122-154）、`_matches` 取「正文词 ∩ 过滤框词」（:60-67）；取数失败与空列表**共用 E6 空态**（:73-76、:102-103）；加载转圈 18px `strokeWidth: 2`、内边距 `AppSpacing.md`=12（:94-101）；容器圆角 `AppRounded.lgRadius` + 四周 1px border（:79-87）；行 `InitialAvatar radius 14` + body，左右 `AppSpacing.md`（:182-198）；`maxHeight` 默认 220（:32） | 去过滤框、打字即查、六态、`docked` / `rowHorizontalPadding` | 行样式（28 头像 + body 15）、空态结构 `_emptyState(title, hint, withIcon:)`、widget key |
| `…/mention/data/mention_candidate_repository.dart` | `MentionCandidate(userId, nickname, avatarUrl)`（:12-24）；`getCandidates()` 无参（:30、:39-45）；`mentionCandidatesProvider` autoDispose + `retry: null`（:62-65） | 加 `relation`、加 `search` | provider 的 autoDispose 与不重试 |
| `…/mention/domain/mention_draft.dart` | `queryAt(text, cursor)`：`@` 在词首、与光标间无空白无第二个 `@`、关键词 ≤ 20（:61、:79 起）；`insert` / `userIdsIn` / `maxMentions = 5` | 不改 | 全部 |
| `…/mention/domain/mention_context.dart` | 枚举 `post` / `comment`，`wire`；类注释说明「事件名与属性 Map 写字面量」 | 不改；`MentionRelation` 照此写法 | — |
| `…/content/presentation/publish_compose_page.dart` | 整个 sheet 包在 `KeyboardInset`（:671，`AnimatedPadding(bottom: viewInsets.bottom)`）；正文 `TextField`（:800 起，`onChanged` 调 `_syncMentionQuery`）；@ 浮层内联在正文下方（:828-834）；`_syncMentionQuery`（:253-258）；`_onMentionSelected`（:261-293，字数拦截 → 5 人拦截 → 插入 → `mention_inserted`）；内容区 `ListView` padding `fromLTRB(20, 8, 20, 36)`（:777） | 删内联浮层，接入 4.4 承载位；埋点加属性 | 字数 / 5 人拦截顺序与提示；`controller.setText` 同步 |
| `…/content/presentation/comment_composer.dart` | `_mentions = MentionDraft()`（:69）；`_syncMentionQuery`（:115）；`_onMentionSelected`（:127-166，埋点 :162-164 多行 Map）；@ 浮层在输入条上方（:349-353）；提交 `_mentions.userIdsIn(text)`（:173） | 传 `rowHorizontalPadding: 16`；埋点加属性 | 浮层位置、提交逻辑 |
| `petgo_app/lib/l10n/app_id.arb`:269-273 / `app_en.arb`:415-432 | `mentionFilterHint`、`mentionEmptyTitle`、`mentionEmptyHint`（旧句「先互动才能 @」）、`mentionNoMatch`、`mentionLimitReached`；复用键 `detailNetworkError`（id :263 / en :400）、`commonRetry`（id :2122 / en :3214） | 只改 `mentionEmptyHint` 的值 | 其余键 |
| `petgo_app/lib/app.dart`:402 | `ref.invalidate(mentionCandidatesProvider)`（登录跃迁清用户维度缓存） | 不改 | — |
| `petgo_app/lib/features/shop/presentation/shop_search_page.dart`:43-66 | 300ms 防抖先例（`Timer? _debounce`、`_debounceWindow`） | 照写 | — |

### 接口假设（4.4 交付，开工时核对）

- 发布页存在一个「贴键盘面板承载位」：一个只在有查询时出现的子组件，位于 `KeyboardInset` 内容的底部（`Stack` + `Positioned(left: 0, right: 0, bottom: 0)` 或等价），自带上沿描边 / 阴影 / 最高 220 / 按内容收缩，并把实际高度回报给正文用于 `scrollPadding`。本 story 只把它的「内容」从 `TopicSuggestPanel` 切成 `MentionPicker(docked: true, rowHorizontalPadding: 20, …)`。
- 若 4.4 把面板外观（描边、阴影）做在承载位上，则 `MentionPicker.docked` 只负责去掉自身圆角与四周 border，不重复画阴影。
- `TopicMentionTextController` 的 @ 高亮读的是 `PublishController.mentions` / 评论框 `_mentions` 中「点选进来、且仍完整在正文里」的记录（UI C4 说明）；本 story 不改高亮规则。
- ⚠️ 踩坑：UI 稿写的是 `Positioned(bottom: viewInsets.bottom)`，但发布页整个 sheet 已包在 `KeyboardInset`（内容底边已经是键盘上沿）。在 `KeyboardInset` 内再加 `viewInsets.bottom` 会**双倍上移**，面板悬在半空。以代码为准：承载位在 `KeyboardInset` 内取 `bottom: 0`。

### 查询状态机（`MentionPicker` 内，纯组件状态，不新增全局 provider）

```
keyword 为空        → watch(mentionCandidatesProvider)：loading → E2 转圈；error → E5；[] → E6；有 → E1
keyword 非空（变化） → 立刻进入 E2（不展示上一批结果），重置 Timer(300ms)
Timer 到点          → seq++；记下 mySeq；cancelToken?.cancel(); 发 search(keyword)
响应回来            → mySeq != seq（或组件已 unmount）→ 丢弃；否则 [] → E4、有 → E3
请求失败            → mySeq == seq 才落 E5（被 cancel 的 DioException.cancel 静默忽略）
点 Coba lagi        → 关键词为空：ref.invalidate(mentionCandidatesProvider)；非空：立即重查（不等 300ms）
didUpdateWidget     → 只在 keyword 真变化时重置；dispose 时 cancel Timer 与 CancelToken
```

- 不加全局 provider 的理由：查询结果按「这一次打字」存活，随面板收起即丢；放全局就要登记 `resetUserScopedCaches`，且容易在换账号时串号。若实现中确需 provider（如 `FutureProvider.autoDispose.family<String>`），**必须**登记 `app.dart` 的 `resetUserScopedCaches` 并更新 `test/app/user_scoped_cache_registry_test.dart`。
- 每次 build 不得发请求；防抖计时只由 `keyword` 变化触发。
- 快捷列表与查询结果都**原样按接口顺序**渲染，不在本地二次排序 / 过滤（排序与范围是 5.1 的服务端规则）。

### 视觉数值（UI E1–E6 说明逐条取值，数值以代码出处为准，不量截图）

- 行：28 头像（`InitialAvatar radius 14`，首字 11.2 = radius × 0.8，`initial_avatar.dart`:73）+ 昵称 `AppTypography.body`（15/1.45），竖向内边距 8（`AppSpacing.sm`），左右发布页 20 / 评论 16，**无等级徽章**、行上不标关系类型。
- 发布页面板：通栏白底，上沿 1px `AppColors.border` + 阴影 `0 -4 12` 6% ink；最高 220；快捷列表约露 4 行多，其余面板内滚动。
- E2：`CircularProgressIndicator(strokeWidth: 2)`，18×18，**不设 `backgroundColor`**（第四步更正：无浅紫底圈），内边距 12。
- E4：`mentionNoMatch` 一行，无图标，`text-wrap: balance` 对应 Flutter 居中。
- E5：结构照 `_emptyState`（无图标、内边距 12）；按钮 = 紫色 `TextButton.icon`（`Icons.refresh` 16 + 14 w600，同 `EmptyState` 次级按钮写法），可视高 33，用 `minimumSize` / `tapTargetSize` 保证热区 ≥ 44。
- E6：`Icons.people_outline_rounded` 30（`AppColors.textTertiary`）→ 8 → 标题（body，`ink2`）→ 4 → 提示（`AppTypography.micro` 11/1.3 muted）。
- 评论框：位置与现有容器样式不变，只去过滤框、行左右 16。
- 原有模块（发布页把手 / 关闭钮 / 标题 / Bagikan / 类型 chips / 公开提示 / 图片格 / 正文 / 字数 / 底部可见性提示）**照线上，不改样式**。

### 禁止事项与踩坑

- **不硬编码文案**：所有可见字串走 ARB；E5 复用 `detailNetworkError` + `commonRetry`，E4 复用 `mentionNoMatch`，不新建同义 key。
- **改 ARB 后必须 `flutter gen-l10n`**，不要手改 `app_localizations*.dart`（生成产物）。
- **不整文件 `dart format`**：存量代码未格式化，整文件格式化会带出几百行无关 diff；手工对齐缩进，只跑 `flutter analyze`。
- 埋点：事件名 / 属性 Map 写字面量；`'context'` 必须是 Map 第一个键且与 `Analytics.capture('mention_inserted', {` 写在同一行（至少发布页那处），否则 `mention_legacy_and_analytics_test.dart`:62 红。新属性名 `relation` / `from_quick_list` 不命中 `Analytics._piiKeys` / `_piiKeySuffixes`（`lib/core/analytics/analytics.dart`:42、:356），已核对。
- 不改 `MentionDraft` 的触发规则（词首 `@`、关键词无空白、≤ 20 字）——含空格的昵称（如「Rara Putri」）只能打到空格前，属既有行为。
- 不改 `mentionLimitReached` 的提示位置（PRD：现有 @ 超过 5 人的提示位置不变）。
- `mentionFilterHint` 键不再被引用：**ARB 键保留不删**（避免与同分支其他 story 的 ARB 改动冲突），Completion Notes 记为待清理。
- 不在 `MentionPicker` 里做全局搜索的视觉暗示（不放放大镜、不写「搜索用户」，现有守卫用例保留）。

### 必须改的既有测试

- `test/mention/mention_picker_test.dart`：`AC2：浮层内过滤框只在这批人之内筛`（:95）、`AC2 🔴 过滤只在候选内做：筛不到就是空，绝不发第二次请求去"搜"`（:103）、`AC3：一个候选都没有 → 空态，且**不显示过滤输入框**`（:141）、`取数失败与"没有候选"走同一个空态`（:157）——语义已被 PRD 推翻，改写为本 story 的 E1–E6 用例；`AC2：正文里 @ 后面打的字就是过滤条件`（:84）/ `过滤不分大小写`（:90）改为「关键词透传给 `search`」；`文案不许承诺"搜索用户"，也不许出现放大镜`（:115）保留。
- `test/mention/mention_entry_test.dart`:255-268、:399-412：`mention_inserted` 断言 `{'context': 'comment'}` / `{'context': 'post'}` 改为含 `relation` 与 `from_quick_list` 的完整 Map。
- 三个假仓储补 `search`（见 T1）。

### 测试

**L0（云端可跑）**
- `test/mention/mention_picker_test.dart`（改写，覆盖稿中每种状态）：
  - E1：关键词空 → 渲染快捷列表、顺序 = 仓储返回顺序；面板内 `find.byType(TextField)` 为空（无过滤框）；
  - E2：输入关键词后 `pump(299ms)` 仓储 `search` 调用 0 次、显示 `mentionPickerLoading`；`pump(1ms)` 后调用 1 次且参数为当前关键词；转圈 `backgroundColor == null`；
  - E3：返回 3 条 → 3 行、顺序不变；
  - E4：返回空 → `mentionNoMatch`，无图标；
  - E5：`search` 抛 `DioException`（含 429）→ `mentionPickerError` + `mentionPickerRetry`；点重试 → 立刻再调一次 → 成功后 E3；快捷列表失败同样是 E5、**不是** E6；
  - E6：快捷列表返回空 → 图标 + `mentionEmptyTitle` + 新 `mentionEmptyHint`（id / en 两种 locale 各断言一次）；
  - 丢弃旧结果：关键词 `ra` 的请求挂起（`Completer`），改为 `rar` 后其结果先回；再让 `ra` 的结果回来 → 界面仍是 `rar` 的结果；
  - 关键词删空 → 回 E1 且不再发 `search`；dispose 时无悬挂 Timer（`tester.binding` 无 pending timers）。
- `test/mention/mention_entry_test.dart`（改）：发布页 / 评论框各一条「选中快捷列表的人 → `mention_inserted` = `{context, relation: following, from_quick_list: true}`」、「打字查到粉丝后选中 → `relation: follower, from_quick_list: false`」；被 5 人上限拦截的那次不报（现有用例保留）。
- `test/mention/mention_relation_test.dart`（新）：`parse` 三值与未知 / null → `interacted`；`wire` 取值。
- `test/content/publish_mention_panel_dock_test.dart`（新）：`MediaQuery(viewInsets.bottom: 300)` 下面板底边 = `KeyboardInset` 内容底边（不双倍上移）、面板高度 ≤ 220、同一时刻只有一个面板（@ 与 # 互斥）、正文 `scrollPadding.bottom` = 面板高度 + 20 且收起后恢复 20；行左右内边距 20。
- `test/content/comment_composer_mention_test.dart`（新或并入既有）：面板在输入条上方、无过滤框、行左右内边距 16。
- 既有守卫全绿：`test/mention/mention_legacy_and_analytics_test.dart`、`test/analytics/v112_events_test.dart`、`test/analytics/v140_events_inventory_test.dart`、`test/app/user_scoped_cache_registry_test.dart`、`test/shared/keyboard_safe_area_test.dart`、`test/l10n/microcopy_rules_test.dart`。
- 命令：`flutter gen-l10n && flutter analyze && flutter test`。

**L2（本地，不在云端尝试）**
- Android 模拟器（`adb` + `flutter run`，连真后端），准备一个「关注 2 人 + 被 1 人关注 + 互动若干」的账号与一个「取关官方号后无任何关系」的账号：逐屏截 E1–E6、评论框面板，与 UI 稿对照打分（≥ 95%）；顺带验 300ms 防抖手感、长正文时光标行不被面板遮挡、@ 插入后高亮。

### Project Structure Notes

- 新文件：`lib/features/mention/domain/mention_relation.dart`；其余均为改动。不进 `shared/rich_text/`（那是 4.4 的解析器与输入控制器），@ 候选仍归 `features/mention`（架构 §5.2）。
- 无路由、无 `ApiPaths` 新常量（复用 `meMentionCandidates`，`lib/core/network/api_paths.dart`:531），无新依赖。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-social-topic.md#Story 5.2、Story 4.4]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-social-topic-delta.md#§2.1 输入框高亮选型、D-15、D-19、§1.2 第 8 条、P-9、P-10、P-12、§5.2]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/1-4-2追更宠物·里程碑·话题prd.md#3.7 FR-157（@ 联想形态、查询时机、空状态、AC1–AC7）、§4 FR-157、§7 FR-157 文案]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/ui-1-4-2-social-topic-integrated-v1.html#E1–E6、C2 改造点、C4]
- [Source: petgo_app/CLAUDE.md#输入 / 键盘避让（强制标准）、i18n、测试]

### 待确认 / 发现的规划冲突（不改规划文档）

1. **UI 稿 `Positioned(bottom: viewInsets.bottom)` 与代码不符**：发布页 sheet 已整体包在 `KeyboardInset`（`publish_compose_page.dart`:671），照稿会双倍上移；本 story 以代码为准取 `bottom: 0`（见「接口假设」），4.4 若已照稿实现需一并修正。
2. **快捷列表取数失败的表现**：PRD 只写「加载中 / 无结果 / 失败 / 空状态都在面板内」，UI E5 只画了「查询失败」；本 story 推荐快捷列表失败也落 E5（不落 E6），理由同 UI E5 说明。
3. **`relation` 缺失兜底为 `interacted`**：仅为兼容「App 先于 5.1 后端上线」的窗口；按「前后端同批部署」惯例正常不会出现。
4. UI E1 说明引用的发布页内容边距行号「L677 fromLTRB(20…)」在当前代码是 :677 的页头 `fromLTRB(20, 10, 20, 8)` 与 :777 的内容区 `fromLTRB(20, 8, 20, 36)`，两者左右都是 20，不影响取值。

## Dev Agent Record

### Agent Model Used

Claude

### Debug Log References

- `flutter gen-l10n`：OK
- `flutter analyze`：No issues found
- `flutter test test/mention test/content/publish_mention_panel_dock_test.dart`：全绿
- `flutter test`（全量）：All tests passed（+4485，~3 skipped 为存量）
- 调试记录：widget test 中 `pump(duration)` 先推进时钟再跑帧 → 关键词变更后需先 `pump()` 让防抖计时器建立再 `pump(300ms)`；同一 testWidgets 内二次 `pumpWidget` 换掉 ProviderContainer 会留 Riverpod dispose 计时器 → 422 / 500 拆成两条用例。

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created
- **名字核对（T0）**：4.4 实际交付的贴键盘外壳是 `lib/shared/widgets/keyboard_suggest_panel.dart` 的 `KeyboardSuggestPanel`（描边 / 阴影 / 最高 220 在外壳上），承载位是发布页 `Stack` 里 `Positioned(left: 0, right: 0, bottom: 0)` + `KeyedSubtree(_topicPanelKey)`，高度量测 `_afterTopicPanelFrame` / `_topicPanelHeight`。本 story 照此接入：新增 `_dockPanelVisible`（@ 或 # 任一可见），承载位内容按 `_mentionQuery != null` 在 `MentionPicker(docked: true, rowHorizontalPadding: 20)` 与 `TopicSuggestPanel` 间二选一；@ 与 # 互斥沿用 4.4 `_syncTopicQuery` 的「start 更靠近光标者胜」。`MentionPicker.docked` 直接用 `KeyboardSuggestPanel` 作外壳（不重复画阴影）。Dev Notes 行号（publish_compose_page :828-834 等）因 4.4 合入已漂移，以实际代码为准，名字无出入、未改 story 其它文字。
- **5.1 契约核对**：后端 `MentionCandidatesResponse(items)`、每项 `relation` 小写，与 App 解析一致（T0 中「stag 实测带 relation」属 L2，未勾）。
- @ 面板内部状态（转圈 → 列表）变化不重建发布页，故用 `SizeChangedLayoutNotifier` + `NotificationListener` 通知发布页重量面板高度，保证 `scrollPadding.bottom = 面板实际高度 + 20`。
- 快捷列表在面板存活期间一直 `watch`（autoDispose、不重试不变），删回 `@` 后为空直接回 E1、不另发请求；带 `q` 的查询为组件内状态（Timer 300ms + 请求序号 + `CancelToken`），未新增全局 provider，`resetUserScopedCaches` / `user_scoped_cache_registry_test` 无需改。
- **复审修复（CONFIRMED）**：E2 转圈外层 `Center` 未设 `heightFactor`，被 `Flexible` 包住后撑满 220（docked / 非 docked / 评论框都有）→ 改 `Center(heightFactor: 1, …)`；补用例「请求挂起时 `mentionPicker` 高约 42、远小于 220」docked 与非 docked 各一条（`test/mention/mention_picker_test.dart`）。
- **顺带修复 4.4 同类问题**：`topic_suggest_panel.dart` 加载态同样写法，实测面板高 220（新用例修复前红）→ 同样改 `heightFactor: 1`；补 `test/topic/topic_suggest_panel_loading_test.dart`（加载中面板约 43 高）。
- 「评论框面板在输入条上方、无过滤框、行左右 16」用例并入既有 `test/mention/mention_entry_test.dart`（未另建 `test/content/comment_composer_mention_test.dart`）。
- E5 重试按钮：`minimumSize: Size(0, 33)` + `tapTargetSize: padded`（热区 48，测试断言 ≥ 44）。
- 埋点：两处 `mention_inserted` 均一行字面量 `{'context': …, 'relation': candidate.relation.wire, 'from_quick_list': query.keyword.isEmpty}`；`relation` / `from_quick_list` 不命中 PII 黑名单。
- 文案：仅改 `mentionEmptyHint` 的 EN / ID 值为 PRD §7 定稿句；`mentionFilterHint` 键已不再被代码引用，**ARB 键保留待清理**（其 `@description` 仍描述旧过滤框，一并待清理）。
- 🔴 **L1/L2 待本地验收**（本 story 无 L1）：
  - L2：Android 模拟器连真后端（stag / `api.tailtopia.id`）逐屏对照 UI 稿 E1–E6 与评论框面板，还原度 ≥ 95% 打分待本地补记；
  - L2：300ms 防抖手感、长正文时光标行不被面板遮挡、@ 插入后高亮；
  - L2：T0 第一项（stag `GET /me/mention-candidates?q=a` 返回带 `relation`）。
- 待确认：
  1. 快捷列表失败也落 E5（story 待确认第 2 条的推荐做法，已照做）。
  2. `relation` 缺失兜底 `interacted`（story 待确认第 3 条，已照做）。

### File List

- petgo_app/lib/features/mention/domain/mention_relation.dart（新）
- petgo_app/lib/features/mention/data/mention_candidate_repository.dart
- petgo_app/lib/features/mention/presentation/mention_picker.dart
- petgo_app/lib/features/content/presentation/publish_compose_page.dart
- petgo_app/lib/features/content/presentation/comment_composer.dart
- petgo_app/lib/l10n/app_en.arb
- petgo_app/lib/l10n/app_id.arb
- petgo_app/test/mention/mention_picker_test.dart
- petgo_app/test/mention/mention_entry_test.dart
- petgo_app/test/mention/mention_candidates_lifecycle_test.dart
- petgo_app/test/mention/mention_relation_test.dart（新）
- petgo_app/test/content/publish_mention_panel_dock_test.dart（新）
- petgo_app/lib/features/topic/presentation/widgets/topic_suggest_panel.dart（复审顺带修复 4.4 加载态高度）
- petgo_app/test/topic/topic_suggest_panel_loading_test.dart（新）

### Change Log

- 2026-11-05：实现 Story 5.2（@ 面板去过滤框、打字即查 300ms 防抖、六态、发布页贴键盘承载位、评论框行距 16、埋点加 relation / from_quick_list、空态提示改文案）；Status → review。
- 2026-11-05：复审修复——E2 加载态 `Center(heightFactor: 1)`（@ 面板与 4.4 # 面板），补高度用例。
