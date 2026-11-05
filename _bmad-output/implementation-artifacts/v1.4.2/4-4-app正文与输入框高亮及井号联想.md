# Story 4.4: 正文话题高亮、输入框高亮与 # 联想（App，C2–C5）

Status: review

> 版本 v1.4.2 · 主题 social-topic · 分支 `feat/1.4.2-social-topic`（云端单会话串行）
> 口径优先级：`决策日志-social-topic.md` > PRD `追更话题/1-4-2追更宠物·里程碑·话题prd.md` > `architecture-v1.4.2-social-topic-delta.md`（**§2.1 输入框高亮选型**、D-13、D-19、P-1、P-9）> UI 稿 `追更话题/ui-1-4-2-social-topic-integrated-v1.html`（C2–C5、E1–E6 说明）。
> 前置：**4.1**（共用样例 `petgo-backend/src/test/resources/fixtures/topic-parse-cases.json`、发帖响应 `topics`、出参 `topicNames`）、**4.2**（`GET /api/v1/topic-suggestions`）、**4.3**（`TopicPage.routeFor`、`TopicEntrySource`、`TopicOfficialBadge`、`topic*` 文案、`v112_events_test` 已登记 `topic_` 前缀）。
> 范围层：**只 App**。@ 候选范围 / 去过滤框 / @ 面板挪到键盘上沿 / 300ms 查询 → **5.2**（本 story 只把「贴键盘面板」的外壳做成共享件，@ 面板位置不动）。
> 🔴 **云端只跑 L0**（`flutter gen-l10n && flutter analyze && flutter test`）；**L2（模拟器：输入法联想下划线、键盘贴合、还原度 ≥ 95%）待本地验收**。
> ⚠️ 本 story 偏重，云端上下文不足时按文末「待确认」第 1 条拆出 4.5（# 联想），本文件仍是完整版本。

## Story

As a 发帖与看帖的用户,
I want 写 # 有联想、写的时候就看到哪些话题生效，看帖时点话题能跳转,
so that 带话题发帖不出错。

## Acceptance Criteria

**AC1 — 共用标记解析器 `lib/shared/rich_text/body_tokens.dart`（P-1、D-19）** `[L0]`
1. 纯 Dart（不依赖 Flutter / Riverpod），规则与后端 `TopicNameRules` **逐条一致**（4.1 AC3.2）：ASCII `#` 起；`#` 在行首或前一字符非话题字符；话题字符 `RegExp(r'[\p{L}\p{M}\p{Nd}_]', unicode: true)`；取最长串，码点数（`runes.length`）2–30、非全 `\p{Nd}` 才算；前 5 个**不同**标准名有效；标准名 = `toLowerCase()`（Dart 无内置 NFC，见待确认第 5 条）。
2. API：
   - `List<TopicOccurrence> topicsIn(String text)`：全部出现处 `{start, end /*UTF-16，含 #*/, display, norm, effective}`；
   - `List<TopicOccurrence> effectiveDistinct(String text)`；
   - `String normalizeTopic(String raw)`；`bool isValidTopicName(String raw)`（不含 `#`）；
   - `TopicQuery? topicQueryAt(String text, int cursor)`：光标前正在输入的 `#关键词`（`#` 词首、`#` 与光标间全是话题字符、≤ 30 码点；关键词可为空），返回 `{start, end, keyword}`——与 `MentionDraft.queryAt`（`mention_draft.dart:79-92`）同形；
   - `List<MarkRange> mentionRanges(String text, Iterable<({int userId, String nickname})> refs, {required bool dropAmbiguous})`：**从 `MentionText._locate`（`mention_text.dart:160-206`）原样搬过来**（长昵称优先、`MentionToken.indexOf` 词边界、不重叠；`dropAmbiguous=true` 时同名多人一律不高亮——展示侧必须开，输入侧关）。
   - **@ 与 # 重叠时 @ 优先**（昵称里含 `#` 的极端情况，先占 @ 区间，落在其中的话题丢弃）。
3. **测试读与后端同一份样例**：`test/shared/rich_text/body_tokens_fixture_test.dart` 读 `File('../petgo-backend/src/test/resources/fixtures/topic-parse-cases.json')`（`flutter test` 的工作目录是 `petgo_app/`；文件不存在直接 fail，不许静默跳过），逐条断言 `effectiveDistinct` 的 display / norm 与 `effective` 出现处的 `ranges`；`"dartSkip": true` 的条目跳过并在测试名里写明原因。**禁止在别处另写话题正则**。

**AC2 — 正文展示组件 `lib/shared/rich_text/rich_body_text.dart`（C5；D-19 取代 `MentionText`）** `[L0]`
1. `RichBodyText({text, mentions, topicNames, onTapUser, onTapTopic, mentionContext, style, maxLines, overflow})`：
   - @：沿用 `MentionText` 全部行为（只高亮 `MentionView.highlightable`、同名不高亮、点击报 `mention_tapped {context}`、`onTapUser`）；
   - 话题：`topicsIn(text)` 里 **`norm ∈ topicNames`**（服务端下发、仅 ACTIVE / HIDDEN）的出现处高亮可点 → `onTapTopic(norm)`；不在 `topicNames` 的（禁用话题、第 6 个、未关联）按普通文字；
   - 样式：`base.copyWith(color: AppColors.mint600, fontWeight: FontWeight.w600)`，无下划线（`mention_text.dart:148` 同款）；正文按作者原写法显示（`#KucingOren` 照样高亮，C8）。
   - 🔴 recognizer 只在 `initState` / `didUpdateWidget` 建与放（内容 / mentions / topicNames 真变了才重建），**绝不在 `build` 里**——`mention_text.dart:17-24` 记录过的间歇性手势被判负 bug。
2. `MentionText` 保留为**薄包装**（构造参数不变，内部 `RichBodyText(topicNames: const {}, onTapTopic: null, …)`），`comment_section.dart:888` 评论正文继续用它——**评论区不解析话题**（D-19）；`test/mention/*` 原有测试不改照绿。
3. 接入点：
   - Feed 卡 `shared/widgets/masonry_card.dart:320-333`：`MentionText` → `RichBodyText`；新增可选回调 `onTapTopic`；判断从「`onTapMention == null` → 纯 `Text`」改为「两个回调都为 null → 纯 `Text`」。`FeedMasonryView`（`feed_view.dart:36 / :76`）加 `onTapTopic` 并透传到普通卡（`:499`）与内容顶置卡（`:445`）。
   - 调用方：`home_page.dart`（Discover）、`following_tab_view.dart:94` 附近（关注流）、4.3 的 `topic_page.dart` 传 `onTapTopic: (n) => context.push(TopicPage.routeFor(n, from: TopicEntrySource.feedCard))`。
   - 帖子详情 `content_detail_page.dart:282`：`MentionText` → `RichBodyText(topicNames: detail.topicNames, onTapTopic: (n) => context.push(TopicPage.routeFor(n, from: TopicEntrySource.postBody)))`。
   - 模型：`FeedItem`（`features/content/domain/feed_item.dart`，`fromJson` `:274` 附近）与 `ContentDetail`（`content_detail.dart:188` 附近）加 `final List<String> topicNames`（`json['topicNames']` 缺省 → `const []`；构造参数默认 `const []`，copyWith 同步）。
4. Feed 卡：点话题进话题页、点其他区域仍进帖子（同 @ 的手势竞技：`TextSpan` 上的 recognizer 命中更深，同一次点击只有一个赢家）。

**AC3 — 输入框高亮 `lib/shared/rich_text/topic_mention_text_controller.dart`（§2.1、C4）** `[L0]` `[L2]`
1. `class TopicMentionTextController extends TextEditingController`，构造 `{required List<MentionRef> Function() mentionRefs, bool highlightTopics = true, String? text}`；重写 `buildTextSpan({required BuildContext context, TextStyle? style, required bool withComposing})`：
   - 话题（`highlightTopics` 为真）：`topicsIn(text)` 中 `effective == true` 的出现处——即**前 5 个不同话题**（同名重复处也高亮），第 6 个起普通色；
   - @：`mentionRefs()`（发布页 = `PublishController.mentions.refs`，评论框 = `_mentions.refs`）经 `mentionRanges(dropAmbiguous: false)` 定位——**只高亮点选插入过的昵称**、且仍完整在文中的（删掉其中一个字 → `MentionToken` 边界不再匹配 → 高亮消失）；手打的、不在 refs 里的 `@abc` 不变色。手打一个与已点选同名的 `@昵称` 也会高亮——与实际发送（`MentionDraft.userIdsIn` 同样按文本匹配）一致，可接受（§2.1 注意事项）。
   - **只改颜色**：高亮段 `style.merge(TextStyle(color: AppColors.mint600))`，**不改字号 / 字重 / 字距**（防光标与选区错位，§2.1；与 UI C4 写的「mint600 w600」不同，见待确认第 3 条）。
   - 不挂任何 `recognizer`（输入框里不可点）。
2. **composing（输入法联想区间）下划线的做法**（§2.1 硬要求，必须有测试）：
   - 照抄框架默认实现的判定与样式：`final bool composingOn = withComposing && value.isComposingRangeValid;`，`composingStyle = (style ?? const TextStyle()).merge(const TextStyle(decoration: TextDecoration.underline))`。
   - 把「高亮区间端点」与「composing 的 start / end」合在一起排序去重，切成若干相邻小段；每段：在高亮区间内 → 叠颜色；在 `[composing.start, composing.end)` 内 → 再叠 `TextDecoration.underline`（**颜色与下划线可同时存在**：联想词正好落在一个话题里时既紫又有下划线）。
   - 没有任何高亮区间时**直接 `return super.buildTextSpan(...)`**（逐字节等同现状，零回归面）。
   - 返回 `TextSpan(style: style, children: [...])`，所有子段 `text` 依序拼接**必须恰好等于 `value.text`**（任何多 / 少一个字符都会让光标偏移错位）。
3. 字数上限不变：`maxLength` 格式化器与 `buildTextSpan` 无关；`_textController.text.length` 计数照旧。
4. 接入：
   - 发布页 `publish_compose_page.dart:160` `final _textController = TextEditingController();` → `late final TopicMentionTextController _textController = TopicMentionTextController(mentionRefs: () => ref.read(publishControllerProvider).mentions.refs);`（`ref` 在首次访问时已可用；`PublishController.mentions` 见 `publish_controller.dart:72`）。
   - 评论框 `comment_composer.dart:57` `final TextEditingController _controller = TextEditingController();` → `late final TopicMentionTextController _controller = TopicMentionTextController(mentionRefs: () => _mentions.refs, highlightTopics: false);`（`_mentions` 在 `:69`；**评论框只高亮 @，不高亮话题、不弹 # 联想**——话题只在发布页）。
   - 现有 `@` 插入 / 提交逻辑（`MentionDraft.insert` / `userIdsIn`）**不改**：@ 提交方式仍是 `mentionedUserIds`（§2.1）。

**AC4 — `#` 联想面板（C2 / C3）** `[L0]` `[L2]`
1. 触发：发布页正文 `onChanged`（`publish_compose_page.dart:805-809`）里在 `_syncMentionQuery()` 之后调 `_syncTopicQuery()`（`topicQueryAt(text, selection.baseOffset)`）。@ 与 # 查询同时成立时取 `start` 更靠近光标的那个，另一个置 null（两个面板不同时出现）。
2. 数据：`TopicRepository.suggest({String? q})` → `GET /api/v1/topic-suggestions?q=`（4.2）。关键词为空 → 不带 `q`，立即请求（热门，官方在前）；关键词非空 → **停 300ms** 再请求（与 `shop_search_page.dart` / 5.2 同口径）；新请求发出时**丢弃上一个未返回的结果**（自增请求序号比对，不是 `CancelToken` 也可）。游客不会到发布页（`/publish` 受控），无需处理 401。
3. 面板内容（`features/topic/presentation/widgets/topic_suggest_panel.dart`）：
   - 行：28 圆位 `AppColors.mintTint` 底 + `Icons.tag` 16 `mint` → 8 →「#displayName」`AppTypography.body`（15/1.45）→ 官方带 `TopicOfficialBadge`（4.3）；行内边距竖 8、横 20（与发布页内容边距 `:777` `fromLTRB(20, 8, 20, 36)` 对齐）；服务端顺序即官方在前。
   - 无匹配（结果为空）且关键词 `isValidTopicName` → 只剩一行「#关键词」+ 第二行 `topicNewLabel`「Topik baru」（`AppTypography.micro` 11/1.3 `muted`），行首图标 `Icons.add` 16（不是 #）；面板按内容收缩为一行高（C3）。关键词不合法（1 个字、纯数字）且无结果 → 面板不出现。
   - 加载中：面板内居中 18px `CircularProgressIndicator(strokeWidth: 2)`、内边距 12、**无底圈、无文案**（同 `mention_picker.dart:94-100`、E2）。
   - 失败：一行 `detailNetworkError` + 紫色 `TextButton.icon(Icons.refresh 16, commonRetry)`（14 w600），结构照 `mention_picker.dart:158-180` `_emptyState`（无图标、内边距 12），点「Coba lagi」用当前关键词重查（E5 同款）。
4. 面板外壳 = **共享件 `lib/shared/widgets/keyboard_suggest_panel.dart`**（5.2 的 @ 面板复用）：通栏白底 `AppColors.surface`、上沿 1px `AppColors.border`、向上淡阴影 `BoxShadow(offset: Offset(0, -4), blurRadius: 12, color: AppColors.ink.withValues(alpha: 0.06))`、`maxHeight 220`、按内容收缩、超出在面板内滚动（`Flexible + ListView(shrinkWrap)`，照 `mention_picker.dart:108-116`）。
5. 位置（贴键盘上沿）：把发布页 `Expanded(child: ListView(...))`（`publish_compose_page.dart:775-777` 起）改成 `Expanded(child: Stack(children: [ListView(...), if (面板可见) Positioned(left: 0, right: 0, bottom: 0, child: TopicSuggestPanel(...))]))`。⚠️ 整页外层已是 `KeyboardInset`（`:671`，已经垫了 `viewInsets.bottom`），所以 **`bottom: 0` 就是键盘上沿**——UI 稿写的 `Positioned(bottom: viewInsets.bottom)` 是按无 `KeyboardInset` 的结构写的，照抄会顶出两倍键盘高度（见待确认第 4 条）。
6. 光标行保持可见（C2「正文很长时不会被面板盖住」）：面板可见期间正文 `TextField.scrollPadding = EdgeInsets.only(left: 20, top: 20, right: 20, bottom: 面板实际高度 + 20)`（Flutter 默认 `EdgeInsets.all(20)`），面板收起恢复默认；面板高度用 `GlobalKey` 在 post-frame 读 `size.height`（变了才 `setState`，首帧按 220 估）。若面板刚出现时光标未自动滚入（scrollPadding 只在光标变化时生效），在 post-frame 里对正文 `TextField` 的 context 调一次 `Scrollable.ensureVisible(..., alignmentPolicy: ScrollPositionAlignmentPolicy.keepVisibleAtEnd)` 兜底；L2 验收以「光标行紧贴面板上沿」为准。
7. 确定话题：
   - 点候选行 → 把 `[query.start, query.end)` 替换为 `#<displayName> `（带尾随空格），光标落在空格后，`controller.setText` 同步；记入本地 `Set<String> _suggestedTopicNorms`（供 AC6 `method`）；面板收起。
   - 点「Topik baru」行 → 替换为 `#<关键词> `（用户自己打的写法）；**不**记入 `_suggestedTopicNorms`（见待确认第 6 条）。
   - 继续打空格 / 标点 → `topicQueryAt` 返回 null，面板自然收起（话题按手打处理）。
   - 插入前先用 `characters.length` 与 `kMaxPostTextLength`（`publish_controller.dart:38`）比，超了 `showAppToast(context, l10n.publishTextLimitReached)` 并收起——同 `_onMentionSelected`（`:261-293`）那道「插入绕过 maxLength 格式化器」的拦截。
8. 无「# Topik」按钮、无话题选择页（PRD）；评论框不弹 # 面板。

**AC5 — 第 6 个话题（C4）** `[L0]`
- 正文已有 5 个不同有效话题（计数时**排除当前正在输入的那段 `#关键词`**）再输入 `#` → **不弹面板**，`showAppToast(context, l10n.topicLimitReached, top: true)`（屏幕顶部 = `app_toast.dart:236` 安全区 + 16）；同一个 `#`（同一 `query.start`）只提示一次，删掉重输再提示。第 6 个起在输入框里显示普通色（AC3）。现有 @ 超 5 人的提示位置不变（仍底部）。

**AC6 — 埋点（P-9、R-2、PRD §4）** `[L0]`
1. `topic_inserted`：**发布成功后**按服务端响应的 `topics` 逐个上报（审核挂起也算服务端已接住；被拦截 / 失败不报；私密帖服务端不返回 topics 即不报）：
   ```dart
   Analytics.capture('topic_inserted', {
     'topic_id': t.name,          // 标准名（R-2）
     'is_official': t.official,
     'is_new': t.created,
     'method': _suggestedTopicNorms.contains(t.name) ? 'suggestion' : 'typed',
   });
   ```
   放在 `_publish` 的 `if (id != null) {`（`publish_compose_page.dart:480`）分支里、`diary_published` 旁边；**字面量写在调用点**。
2. `topic_page_viewed.source`：Feed 卡（Discover / Following / 话题页列表 / 顶置卡）进话题页 = `feed_card`，帖子详情正文 = `post_body`——通过 `TopicPage.routeFor(name, from: …)` 传入，由 4.3 的话题页统一上报（本 story 不另报）。
3. 发布响应解析：`ContentRepository.publish`（`content_repository.dart:12 / :48`）返回类型 `Future<int>` → `Future<PublishedPost>`（`{int id, List<PublishedTopic> topics}`，`topics` 解析 `[{name, official, created}]`，缺省空表）；**`PublishController.publish`（`publish_controller.dart:230`）对外仍返回 `int?`**，另存 `List<PublishedTopic> lastPublishedTopics`（发布成功后赋值）——避免改动 30 处 `controller.publish` 测试调用；13 个 `ContentRepository` 测试 fake 的返回值机械改为 `PublishedPost(id: …)`。

**AC7 — 话题与 @ 混排（PRD FR-69 AC13）** `[L0]`
- 发布页输入「6 个话题 + 1 个从列表点选的 @ + 手打 `@abc`」：前 5 个话题与点选的 @ 紫色；第 6 个话题普通色；`@abc` 不变色；`MentionDraft.userIdsIn` 只含点选那位（回归）。

**AC8 — 文案（PRD §7）** `[L0]`
- 新增：`topicNewLabel`（Topik baru / New topic）、`topicLimitReached`（Maksimal 5 topik per postingan / Up to 5 topics per post）；复用 4.3 的 `topicOfficialBadge`、既有 `detailNetworkError` / `commonRetry` / `publishTextLimitReached`。两份 ARB 同步写 → `flutter gen-l10n`；源码不硬编码可见字符串。

**AC9 — 还原度** `[L2]`
- 本地 Android 模拟器逐屏对 C2、C3、C4、C5 截图打分，每屏 ≥ 95%；中文 / 印尼语输入法下实测 composing 下划线不丢、光标不偏、话题高亮随打字即时变化；面板贴键盘上沿、光标行在面板之上。实现完在模拟器打开发布页给用户看。

## Tasks / Subtasks

- [x] **T0 开工核对**：4.1 ~ 4.3 已合入；fixtures 文件存在；Dev Notes 行号未漂移；联想路径已定为 `/topic-suggestions`（2026-11-04 主会话拍板）。
- [x] **T1 解析器** `body_tokens.dart` + fixture 测试 + 单测（AC1；P-1、D-19）
- [x] **T2 展示组件** `rich_body_text.dart`；`MentionText` 改薄包装；`FeedItem` / `ContentDetail` 加 `topicNames`；`MasonryCard` / `FeedMasonryView` / 详情页 / 三个列表调用方接 `onTapTopic`（AC2）
- [x] **T3 输入控制器** `topic_mention_text_controller.dart` + 发布页 / 评论框替换（AC3；§2.1）
- [x] **T4 联想**：`keyboard_suggest_panel.dart`、`topic_suggest_panel.dart`、`TopicRepository.suggest` + `ApiPaths.topicSuggestions`、发布页 `_syncTopicQuery` / 选中 / 限 5 / scrollPadding（AC4、AC5）
- [x] **T5 发布响应与埋点**：`PublishedPost`、`PublishController.lastPublishedTopics`、13 个 fake、`topic_inserted`（AC6）
- [x] **T6 文案**（AC8）→ `flutter gen-l10n`
- [x] **T7 测试**（见「测试」）→ `flutter analyze` 零告警 + `flutter test` 全绿
- [ ] **T8 本地 L2**（AC9）；Completion Notes 记分数与输入法实测结果

## Dev Notes

### 现状（改动前必读，2026-11-04 核 `dev_1.4.2` @ c496e3c3）

| 位置 | 现状 | 本 story 改什么 / 保留什么 |
|---|---|---|
| `lib/features/mention/domain/mention_token.dart` | `MentionToken.indexOf(text, nickname, from)`：`@昵称` 两头都不是 `[\p{L}\p{N}_]` 才算；写入侧（`userIdsIn`）与渲染侧共用 | **不改**；`mentionRanges` 与输入控制器都调它（边界判定只此一处） |
| `lib/features/mention/domain/mention_draft.dart` | `MentionRef{userId, nickname}`、`MentionQuery`、`queryAt`（`@` 前须空白）、`insert`（插 `@昵称 `、上限 5、同人不占名额）、`textAfterInsert`、`userIdsIn`；纯 Dart | **不改**；控制器只读 `refs`；`topicQueryAt` 仿 `queryAt` 写在 `body_tokens.dart` |
| `lib/features/mention/presentation/mention_text.dart` | 只认 @ 的 `StatefulWidget`；`_locate`（:160-206）同名不高亮、长昵称优先；`_spans` 私有；recognizer 生命周期在 State | `_locate` 逻辑搬进 `BodyTokens.mentionRanges`；本类改成 `RichBodyText` 薄包装（API 不变） |
| `lib/features/mention/presentation/mention_picker.dart` | 内联 `Container`（圆角描边）+ 过滤框 `_filterField` + 加载 / 空 / 无匹配 / 列表；`maxHeight 220` | 本 story **不改**（5.2 去过滤框并换成 `KeyboardSuggestPanel` 外壳）；# 面板复用其状态结构与行尺寸 |
| `lib/shared/widgets/masonry_card.dart:320-333` | `onTapMention == null` → `Text(maxLines 2)`，否则 `MentionText` | 换 `RichBodyText`，加 `onTapTopic` |
| `lib/features/content/presentation/content_detail_page.dart:282` | 正文 `MentionText`（@ 点击 `openUserProfile(entry: mention, onBlocked: …)`） | 换 `RichBodyText`；@ 部分参数原样搬 |
| `lib/features/content/presentation/comment_section.dart:888` | 评论正文 `MentionText` | **不改**（不解析话题） |
| `lib/features/content/presentation/comment_composer.dart:57 / :69 / :114-170 / :350` | 普通 `TextEditingController`；`MentionDraft _mentions`；`_syncMentionQuery` / `_onMentionSelected`；`MentionPicker` 长在输入条上沿 | 只换控制器类型（@ 高亮），其余不动 |
| `lib/features/content/presentation/publish_compose_page.dart` | `:160` 普通控制器；`:250-259` `_mentionQuery` / `_syncMentionQuery`；`:261-293` `_onMentionSelected`；`:427` `_publish`、`:461` `controller.publish`、`:480` 成功分支；`:671` `KeyboardInset` 包整页；`:775` `Expanded(ListView)`；`:799-826` 正文 `TextField`（`minLines 3 / maxLines 6 / maxLength 1000`，`onChanged` → `setText` + `_syncMentionQuery`）；`:828-834` 内联 `MentionPicker` | 换控制器、加 `_topicQuery` 与面板 Stack、scrollPadding、`topic_inserted`；@ 浮层位置不动 |
| `lib/features/content/domain/publish_controller.dart:72 / :230` | `final MentionDraft mentions`；`publish()` → `Future<int?>` | 加 `lastPublishedTopics`；返回类型不变 |
| `lib/features/content/data/content_repository.dart:12 / :48` | `Future<int> publish(...)`，Dio 实现解析 `id` | 改 `Future<PublishedPost>` |
| `lib/shared/widgets/app_toast.dart:42-53 / :236` | `showAppToast(context, msg, {bool top = false, …})`；`top` → 安全区 + 16 | 第 6 个话题提示传 `top: true` |
| `test/content/content_detail_checkin_strip_test.dart:93` | **源码断言** `src.indexOf('MentionText(')` 在场所条之前 | 改成 `'RichBodyText('`（否则 indexOf = -1 直接红） |
| `test/mention/*`（`mention_text_test`、`mention_tap_priority_test`、`mention_legacy_and_analytics_test` 等） | 钉住 @ 渲染、手势优先级、埋点 | 不改，必须照绿（薄包装保证） |

### `@` 现有三件的复用与替换方案（对照代码）

| 现有 | 处置 | 理由 |
|---|---|---|
| `MentionDraft`（数据层：refs / queryAt / insert / userIdsIn） | **原样保留**，输入高亮只读 `refs` | @ 提交方式不变（§2.1）；它是纯 Dart、已被 L0 钉住 |
| `MentionToken`（词边界） | **原样保留**，展示 / 输入 / 提交三处共用 | 2026-09-15 分叉事故后定下的「判定只留一处」 |
| `MentionText`（只认 @ 的展示组件） | 逻辑迁入 `RichBodyText` + `BodyTokens.mentionRanges`；类本身变薄包装，评论区继续用 | D-19 要求「取代私有且只认 @ 的 `_spans`」；保留类名让评论区与既有测试零改动 |
| `MentionPicker`（@ 浮层） | 本 story 不改；5.2 换外壳 | 范围归 FR-157 / 5.2 |

### 实现提示

- **目录（P-4）**：`lib/shared/rich_text/{body_tokens.dart, rich_body_text.dart, topic_mention_text_controller.dart}`（content / mention / topic 共用）；`lib/shared/widgets/keyboard_suggest_panel.dart`；`lib/features/topic/presentation/widgets/topic_suggest_panel.dart`；联想结果模型复用 4.3 的 `TopicSummary`（dev 修订：不另建 `TopicSuggestion`）。
- **`buildTextSpan` 骨架**（示意，非最终代码）：
  ```dart
  @override
  TextSpan buildTextSpan({required BuildContext context, TextStyle? style, required bool withComposing}) {
    final String t = value.text;
    final List<MarkRange> marks = _marks(t); // 前 5 话题（highlightTopics 时）+ 点选 @，已排序不重叠
    if (marks.isEmpty) {
      return super.buildTextSpan(context: context, style: style, withComposing: withComposing);
    }
    final TextRange c = (withComposing && value.isComposingRangeValid) ? value.composing : TextRange.empty;
    final cuts = <int>{0, t.length, for (final m in marks) ...[m.start, m.end], if (c.isValid) ...[c.start, c.end]}
        .toList()..sort();
    final children = <TextSpan>[];
    for (int i = 0; i + 1 < cuts.length; i++) {
      final int a = cuts[i], b = cuts[i + 1];
      if (a >= b) continue;
      TextStyle seg = const TextStyle();
      if (marks.any((m) => m.start <= a && b <= m.end)) seg = seg.copyWith(color: AppColors.mint600);
      if (c.isValid && c.start <= a && b <= c.end) seg = seg.merge(const TextStyle(decoration: TextDecoration.underline));
      children.add(TextSpan(text: t.substring(a, b), style: seg));
    }
    return TextSpan(style: style, children: children);
  }
  ```
  子段 style 只带「差量」（颜色 / 下划线），其余继承父 `style`——字号字重与无高亮时完全一致。
- **`MarkRange` 计算放 `body_tokens.dart`**（纯函数、可单测），控制器只做切段与样式。
- **请求去重**：`_suggestSeq++` 记下发请求时的序号，回包时序号不等即丢；300ms 用 `Timer`，`dispose` 里取消。
- **两个查询的取舍**：`final mq = MentionDraft.queryAt(...); final tq = topicQueryAt(...);` 两者都非 null 时比较 `start`，大者胜。
- **字数**：插入预演用 `.characters.length`（`package:characters`，同 `_onMentionSelected`）。
- **不整文件 `dart format`**；手工对齐，只跑 `flutter analyze`。改 ARB 后必须 `flutter gen-l10n`。

### UI 数值出处速查（C2–C5）

| 元素 | 数值 | 出处 |
|---|---|---|
| 正文 | 14/1.6、最小 3 行、字数 11 | `publish_compose_page.dart:799-841`（UI 写 L811-841） |
| 面板 | 通栏白底、上沿 1px border、阴影 0 −4 12 6% ink、最高 220 | UI C2 改造点② |
| 行 | 28 圆位 + body 15/1.45、内边距竖 8、横 20 | UI C2 改造点② / ④ |
| 话题行首 | `Icons.tag` 16、mintTint 底 | UI C2 |
| 新话题行 | `Icons.add`、第二行 micro 11/1.3 muted | UI C3 |
| Resmi | 同 4.3 `TopicOfficialBadge` | UI C1 / C2 / C7 |
| 输入高亮 | **只改颜色** mint600（不加 w600） | 架构 §2.1（优先于 UI C4，待确认第 3 条） |
| 展示高亮 | mint600 + w600、无下划线 | `mention_text.dart:148`、UI C5 |
| 第 6 个提示 | `showAppToast(top: true)`，字号 14 | `app_toast.dart:236 / :162` |

### 测试

- **L0（云端跑）**：
  - `test/shared/rich_text/body_tokens_fixture_test.dart`：读共用样例逐条断言（AC1.3）。
  - `test/shared/rich_text/body_tokens_test.dart`：`topicQueryAt`（`#` 刚打 → 空关键词；`#ku|` → `ku`；`abc#|` → null；`#kucing |` → null；31 字 → null；光标在 0 / 越界 → null）；`mentionRanges` 搬迁后与原 `MentionText` 行为一致（An / Ana、同名两人 `dropAmbiguous` 开关两种结果）；@ / # 重叠时 @ 优先。
  - `test/shared/rich_text/rich_body_text_test.dart`：`topicNames` 内的话题高亮（颜色 mint600、w600）且点击回调收到标准名；禁用话题（不在 `topicNames`）普通文字、无 recognizer；@ 行为回归；`#KucingOren` 原样显示且高亮；recognizer 在无关重建（祖先 `setState`）后不被重建（同 `mention_text_test` 写法）。
  - `test/shared/rich_text/topic_mention_text_controller_test.dart`（`testWidgets` 里拿 `BuildContext` 调 `buildTextSpan`）：
    1. 子段文本拼接 == `value.text`（覆盖：无高亮、只话题、只 @、话题 + @、6 个话题、composing 横跨高亮边界）；
    2. **composing**：`value = TextEditingValue(text: '#kucing lucu', composing: TextRange(start: 3, end: 10))` → `[3,7)` 段同时有 `mint600` 与 `TextDecoration.underline`、`[7,10)` 只有下划线、`[0,3)` 只有颜色；`withComposing: false` → 全文无下划线；`composing` 越界 / 非法 → 无下划线且不抛；
    3. 无任何高亮时与 `TextEditingController().buildTextSpan(...)` 结果结构相等（含 composing 情况）；
    4. 高亮段 `fontWeight` / `fontSize` 与基础 style 相同（只改颜色）；
    5. 第 6 个话题无颜色；点选 @（refs 里）有颜色、删掉其中一个字后无颜色、手打 `@abc` 无颜色；`highlightTopics: false` 时话题无颜色。
  - `test/content/publish_compose_test.dart`（补，用 fake `TopicRepository`）：输入 `#` → 面板出现并请求无 `q`；输入 `#ku` → 300ms 内不请求、之后带 `q=ku` 请求；快速 `#k`→`#ku` 只采用后一次结果；无结果 → 「Topik baru」行；关键词 `#a` 无结果 → 无面板；加载中转圈；失败 → `Coba lagi` 重查；点候选 → 正文变 `…#KucingSehat `、光标在尾、面板收起；已有 5 个话题再输 `#` → 无面板 + 顶部 toast（`top: true`）且同一个 `#` 只一次；发布成功 → 按响应 `topics` 报 `topic_inserted`（suggestion / typed 区分、`is_new` / `is_official` 透传）；发布被拦截 → 不报；面板可见时 `TextField.scrollPadding.bottom` = 面板高 + 20、收起后恢复 20；AC7 混排断言。
  - `test/content/comment_composer_mention_highlight_test.dart`：评论框点选 @ 后高亮、`#kucing` 不高亮、无 # 面板。
  - `test/content/masonry_card_topic_tap_test.dart`：点正文话题 → `onTapTopic` 触发、`onTap`（进详情）不触发；点正文其余位置 → 进详情（同 `mention_tap_priority_test` 写法）。
  - `test/content/content_detail_checkin_strip_test.dart:93`：源码断言改为 `'RichBodyText('`。
  - 13 个 `ContentRepository` fake：返回 `PublishedPost(id: …)` 后全部测试照绿。
- **L1**：无。
- **L2（本地 Android 模拟器 + 真后端）**：Gboard（印尼语 / 英语联想）与中文输入法下打 `#kuc` → 联想词下划线显示正常、选词后高亮正确；长正文时面板不盖光标行；Feed 卡 / 详情点话题进话题页、点空白进详情；禁用话题（后台禁用后刷新）正文不可点；评论框 @ 高亮；C2–C5 还原度 ≥ 95%。
- 云端只跑 L0：`flutter gen-l10n && flutter analyze && flutter test`；Completion Notes 写「L1/L2 待本地验收」。

### Project Structure Notes

- 新增：`lib/shared/rich_text/*`（3 个）、`lib/shared/widgets/keyboard_suggest_panel.dart`、`lib/features/topic/presentation/widgets/topic_suggest_panel.dart`、`lib/features/topic/domain/topic_suggestion.dart`、`lib/features/content/domain/published_post.dart`（`PublishedPost` / `PublishedTopic`）、`test/shared/rich_text/*`、上列新测试。
- 改动：`mention_text.dart`（薄包装）、`masonry_card.dart`、`feed_view.dart`、`home_page.dart`、`following_tab_view.dart`、`topic_page.dart`（4.3 产物）、`content_detail_page.dart`、`feed_item.dart`、`content_detail.dart`、`comment_composer.dart`、`publish_compose_page.dart`、`publish_controller.dart`、`content_repository.dart`、`topic_repository.dart`、`api_paths.dart`、两份 ARB、13 个测试 fake、`content_detail_checkin_strip_test.dart`。
- 与架构 §5.2 的差异：`KeyboardSuggestPanel` 外壳放 `shared/widgets`（@ 与 # 共用）；架构写 `topic_suggest_panel.dart` 在 `features/topic/presentation/widgets`，一致。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-social-topic.md#Story 4.4、校验结论（4.4 可拆 4.5）]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-social-topic-delta.md#2.1 输入框高亮选型、D-13、D-19、P-1、P-4、P-9、P-10、P-12]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/1-4-2追更宠物·里程碑·话题prd.md#3.3 话题名规则 / 发布 / 展示与跳转 / AC3、AC4、AC12、AC13；§4 FR-69 埋点；§7]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/ui-1-4-2-social-topic-integrated-v1.html#C2–C5、E1–E5]
- [Source: _bmad-output/planning-artifacts/v1.4.2/决策日志-social-topic.md#R-2]
- [Source: _bmad-output/implementation-artifacts/v1.4.2/4-1-话题建模发帖写关联与按天浏览.md#共用样例初稿]

## 待确认 / 发现的规划冲突（不改规划文档）

1. **建议拆出 4.5（# 联想）**：本 story 同时改 `publish_compose_page.dart`（1790 行）、`comment_composer.dart`、`masonry_card.dart`、`feed_view.dart`、详情页，新增 6 个文件、改 13 个测试 fake，云端单会话上下文吃紧（epics「校验结论」也预留了拆分）。拆分方案：
   - **4.4（保留）**：AC1 解析器 + 样例、AC2 展示组件与跳转、AC3 输入控制器（发布页 + 评论框）、AC6 发布响应与 `topic_inserted`（此时 `method` 恒 `typed`）、AC7 混排、AC8 文案中 `topicLimitReached` 以外部分、C5 还原度。
   - **4.5（新）**：AC4 联想面板 + `KeyboardSuggestPanel` 外壳 + 贴键盘 / scrollPadding、AC5 第 6 个提示、`method=suggestion` 的本地记录、`topicNewLabel` / `topicLimitReached` 文案、C2–C4 还原度。依赖 4.4 的 `body_tokens.topicQueryAt` 与控制器。
   - 拆分需主会话在 sprint-status 增加 `4-5-…` 行（本 story 不改 sprint-status）。
2. **@ 面板位置在 4.4 与 5.2 之间不一致**：UI 规定 # 与 @ 「同一个组件、同一个位置（贴键盘上沿）」，但 @ 面板挪位与去过滤框归 5.2（FR-157.2）。4.4 完成到 5.2 完成之间，发布页 # 面板贴键盘、@ 面板仍在正文下方带过滤框——同分支串行、不单独发版，可接受；4.4 先把外壳做成共享件，5.2 只需换用。
3. **输入高亮字重**：UI C4 写「mint600 w600」，架构 §2.1 与 epics 4.4 AC 写「只改颜色、不改字号字重（避免光标 / 选区错位）」。按优先级（架构 > UI）只改颜色；展示侧（Feed / 详情）仍是 mint600 + w600。
4. **贴键盘的写法与代码结构不符**：UI 写 `Stack + Positioned(bottom: viewInsets.bottom)`，但发布页整页已包 `KeyboardInset`（已垫键盘高度），按代码应 `bottom: 0`。以代码为准。
5. **Dart 无 NFC**（dev 复审后改写，2026-11-05）：`normalizeTopic` 已补两条与 Java `Locale.ROOT` 对齐的映射（`İ` → `i̇`、Final_Sigma `Σ` → `ς`），对应样例 `final_sigma` / `dotted_I_short` / `lowercase_grows_over_30` 前后端都过、不 dartSkip。剩余差异只有 NFC：分解形式正文（`nfc_decomposed` / `nfd_short` 两条 dartSkip）App norm 是分解形式——**展示侧**由 `matchServerTopicName` 的 NFD 容错对上服务端 NFC 名（高亮可点、跳转用服务端名，`rich_body_text_test` 钉住）；**输入框高亮 / 限 5 计数**仍按 App norm 算，NFD 正文时名额与服务端可能差一格，极长 NFD 话题 App 可能判超长。
6. **「Topik baru」行点选算 `suggestion` 还是 `typed`**：PRD 定义 suggestion =「从联想选的」、typed =「手打完整话题」。点「Topik baru」行是在面板里确认了自己打的新名字，本 story 记为 `typed`（名字来自用户输入）；若产品要算 `suggestion`，把该分支也加入 `_suggestedTopicNorms` 即可。
7. **联想接口路径**跟随 4.2 的决定（4.2「待确认」第 1 条：`/topic-suggestions` vs 架构原写 `/topics/suggest`）；本 story 只通过 `ApiPaths.topicSuggestions` 引用，改回只需改一处常量。

## Dev Agent Record

### Agent Model Used

Claude

### Debug Log References

- `flutter test test/shared/rich_text/body_tokens_fixture_test.dart`：初版 35 条通过、2 条 dartSkip；首跑 `library;` 指令位置错误修正。复审修复后 40 条样例：38 条通过、2 条 dartSkip（`nfc_decomposed`、`nfd_short`）。
- 复审核对：用 `java T.java`（Normalizer NFC + `toLowerCase(Locale.ROOT)`）实测 `ΟΔΟΣ→οδος`、`ΑΣ_→ας_`、`ΑΣΑ→ασα`、`猫Σ→猫σ`、`ΑΣ+U+0301→ας+U+0301`、`İ→i+U+0307`；Dart 原生 `toLowerCase` 得 `οδοσ`、`i`（丢点）。后端 `TopicNameRulesFixtureTest` 42 个用例全过。
- `publish_topic_suggest_test` 首跑：GoRouter 以 `/compose` 为初始页时发布成功 `pop` 掉最后一页 → 改为 `/home` 起 push `/compose`（同既有 harness）。
- 控制器测试：越界 composing 在 `TextEditingController.value=` 处即被框架断言拒绝，进不到 `buildTextSpan` → 用例改测能进入的两种无效形态（`TextRange.empty` / 折叠区间）。

### Completion Notes List

- **L1/L2 待本地验收**：
  - L1：本 story 无 L1 项（无后端运行时改动）。
  - L2（本地 Android 模拟器 + 真后端）：AC9 C2–C5 逐屏还原度 ≥ 95%；Gboard（印尼语 / 英语联想）与中文输入法下打 `#kuc` 联想下划线正常、选词后高亮正确、光标不偏；面板贴键盘上沿、长正文时光标行在面板之上（`scrollPadding` + `showOnScreen` 兜底）；Feed 卡 / 详情点话题进话题页（source = feed_card / post_body）、点空白进详情；后台禁用话题后刷新正文不可点；评论框 @ 高亮。T8 未勾。
- 实现：`body_tokens.dart`（`topicsIn` / `effectiveDistinct` / `normalizeTopic` / `isValidTopicName` / `topicQueryAt` / `mentionRanges` / `mergeMarks`，按码点处理代理对）与后端读同一份样例；`RichBodyText` 取代 `MentionText._spans`，`MentionText` 成薄包装（评论区不解析话题）；`TopicMentionTextController`（只改颜色、composing 下划线合并切段、无高亮直接 `super`）；发布页 # 联想（空关键词立即请求、非空 300ms 防抖、请求序号丢弃旧回包、限 5 顶部 toast 同一 `#` 只一次、贴键盘 Stack、scrollPadding）；`PublishedPost` / `lastPublishedTopics` / `topic_inserted`（发布成功后按服务端 `topics` 上报，R-2）；`FeedItem` / `ContentDetail.topicNames` 接入，后端 `FeedResponseContractTest` / `ContentDetailAndCommentContractTest` 契约字段集并入 `topicNames`。
- **对 story 文件的名字修订**：
  1. 不新建 `topic_suggestion.dart` / `TopicSuggestion`：复用 4.3 已声明供 # 联想共用的 `TopicSummary`。
  2. 「13 个 `ContentRepository` fake」实为 9 个文件 11 处 `publish` 覆写（其余 fake 走 `noSuchMethod`，无需改）。
  3. `mention_picker.dart:158-180 _emptyState` 无重试按钮；失败态按 E5 结构自写（一行 `detailNetworkError` + `TextButton.icon(refresh, commonRetry)`）。
  4. `body_tokens.dart` 另加 `mergeMarks`（@ 优先合并，展示与输入两侧共用）；`MarkRange` 带 `userId` / `topicNorm`。
  5. 「光标未自动滚入」兜底改用 `RenderObject.showOnScreen(rect: 字段 + 面板高 + 20)`：`Scrollable.ensureVisible(keepVisibleAtEnd)` 会把正文对齐到视口底边——正好压在面板下面。
  6. 测试落点：发布页 # 联想 / 限 5 / 埋点 / scrollPadding 放新文件 `test/content/publish_topic_suggest_test.dart`（`publish_compose_test.dart` 已 800+ 行，harness 同口径）；评论框高亮用例加进 `test/mention/mention_entry_test.dart`（复用其私有 fake），不另建 `comment_composer_mention_highlight_test.dart`；AC7 混排断言放 `topic_mention_text_controller_test.dart`。
  7. `test/mention/mention_legacy_and_analytics_test.dart` 的「埋点字面量」源码扫描清单追加 `lib/shared/rich_text/rich_body_text.dart`（`mention_tapped` 上报点随切片逻辑搬入，D-19 按设计打破；只加站点、不放宽断言）。`v112_events_test` 属性清单加 `is_official` / `is_new`。
  8. 交接口径里写的 `TopicRef(name, displayName)` 与代码不符：后端实际为 `TopicRef(name, official, created)`，与本 story AC6 一致，按代码实现。
- **复审修复（2026-11-05，编排者复审）**：
  1. `normalizeTopic` 补 `İ` → `i̇` 与 Final_Sigma（前有 cased 字母、后无 cased 字母，跳过组合附标；cased 用「大小写映射至少一边不是自身」近似——Java 实测 `猫Σ` → `σ`，所以不是「前面是字母」）。
  2. 共用样例新增 `final_sigma`、`dotted_I_short`（不 dartSkip，前后端都过）、`nfd_short`（dartSkip，写明原因）；`lowercase_grows_over_30` 去掉 dartSkip。
  3. NFD 容错 `matchServerTopicName`（只在 `body_tokens.dart`）：先精确比，不中再按「基字符 + 组合附标」逐簇对齐——App 簇带附标、服务端是单个非 ASCII 码点（预组合字符）即算对上，且须**唯一**命中。⚠️ 没有照字面做「去掉 \p{M} 再比」：服务端 NFC 的 `é`（U+00E9）本身不是附标、删不掉，裸删永远对不上 `e`+U+0301；无分解表时这是能做到的最近似。`RichBodyText` 命中后点击回调给服务端名。
  4. `_suggestedTopicNorms` 随每次输入收缩为当前正文有效话题；发布时再取「联想记录 ∩ 正文 `effectiveDistinct`」判 `method`；补两条测试（点选→删除→手打同名 = typed；程序化改值后的交集）。
- **待确认**：
  1. 见上方「待确认 / 发现的规划冲突」第 5 条（已按复审改写）：剩余差异仅 NFC，展示侧有 NFD 容错兜底；输入框高亮 / 限 5 计数在 NFD 正文时可能与服务端差一格。NFD 容错在同帖两个话题只差一个重音（如 `pé` / `pè`）时按「多个都像 → 不高亮」处理。
  2. 关键词非空的 300ms 等待期间：若已有上一关键词的结果则继续显示旧结果，否则显示转圈（避免每打一个字闪一下）；L2 看感受，如要求每次都转圈改 `_requestSuggestions` 一处。
  3. 话题页列表里点**当前话题**仍会 push 一层同名话题页（story 未区分）；如需拦截在 `topic_page.dart` 的 `onTapTopic` 判同名即可。
  4. 「Topik baru」行按原待确认第 6 条记 `typed`。

### File List

- 新增：`petgo_app/lib/shared/rich_text/body_tokens.dart`、`petgo_app/lib/shared/rich_text/rich_body_text.dart`、`petgo_app/lib/shared/rich_text/topic_mention_text_controller.dart`、`petgo_app/lib/shared/widgets/keyboard_suggest_panel.dart`、`petgo_app/lib/features/topic/presentation/widgets/topic_suggest_panel.dart`、`petgo_app/lib/features/content/domain/published_post.dart`、`petgo_app/test/shared/rich_text/{body_tokens_fixture_test,body_tokens_test,rich_body_text_test,topic_mention_text_controller_test}.dart`、`petgo_app/test/content/publish_topic_suggest_test.dart`、`petgo_app/test/content/masonry_card_topic_tap_test.dart`
- 改动（App lib）：`mention_text.dart`、`masonry_card.dart`、`feed_view.dart`、`home_page.dart`、`following_tab_view.dart`、`topic_page.dart`、`content_detail_page.dart`、`feed_item.dart`、`content_detail.dart`、`comment_composer.dart`、`publish_compose_page.dart`、`publish_controller.dart`、`content_repository.dart`、`topic_repository.dart`、`app_en.arb`、`app_id.arb`
- 改动（App test）：`fake_topic_repository.dart`、`v112_events_test.dart`、`content_detail_checkin_strip_test.dart`、`mention_legacy_and_analytics_test.dart`、`mention_entry_test.dart`、9 个 `ContentRepository` fake 所在文件（`publish_controller_test`、`publish_diary_published_analytics_test`、`publish_sync_switch_test`、`publish_milestone_celebration_compose_test`、`publish_preview_lightbox_test`、`publish_compose_test`、`publish_checkin_link_test`、`publish_reorder_test`、`mention_entry_test`）
- 改动（后端 test）：`FeedResponseContractTest.java`、`ContentDetailAndCommentContractTest.java`、`src/test/resources/fixtures/topic-parse-cases.json`（复审：+`final_sigma` / `dotted_I_short` / `nfd_short`，`lowercase_grows_over_30` 去 dartSkip）

### Change Log

- 2026-11-05：实现 Story 4.4（解析器 + 共用样例、RichBodyText、TopicMentionTextController、# 联想面板与限 5、topic_inserted、topicNames 接入与后端契约字段集同步）；Status → review。
- 2026-11-05：复审修复——`normalizeTopic` 对齐 Java Locale.ROOT（İ / Final_Sigma）、共用样例 +3 条、`matchServerTopicName` NFD 容错、`_suggestedTopicNorms` 收缩与发布时交集。
