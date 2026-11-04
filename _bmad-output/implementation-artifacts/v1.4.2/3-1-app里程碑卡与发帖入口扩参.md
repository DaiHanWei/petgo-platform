# Story 3.1: 里程碑卡与发帖入口扩参（App，D4–D7）

Status: ready-for-dev

> 版本 v1.4.2 · 主题 social-topic · 分支 `feat/1.4.2-social-topic`
> 口径优先级：`决策日志-social-topic.md` > PRD §3.6（FR-156「交互」「里程碑卡图」）+ §4（FR-156 埋点）+ §7 文案 > 架构 D-21、G-6 > UI 稿 D4 / D5 / D6 / D7 逐屏说明。
> 前置：**2.3**（账号级编码判定 `isAccountMilestone`、`CelebrationPet` 与 `readAccountCelebrationPets`，多宠卡要用）。后继：**3.2**（庆祝页按钮接线、6 处弹出点、失败埋点）——本 story 只交付「卡 + 截图 + 入口扩参 + 预填」这些**零件**，并用测试证明它们可用；庆祝页按钮的行为改造全部在 3.2。
> 范围层：**只 App**。参考提交：`c496e3c3`（`TsRemoteArtImage.waitPainted`：等远程图首帧真正画上屏再截，修 Tailsonality 白图帖）。

## Story

As a 解锁里程碑的用户,
I want 一张好看的里程碑卡能直接带进发帖页,
so that 分享成就不用自己截图。

## Acceptance Criteria

**AC1 — 1:1 里程碑卡（D6 / D7）** `[L0]` `[L2]`
1. 新组件 `MilestoneShareCard`，按 `CardCanvas.square`（1080×1080，r48）画布坐标排版、放进 `CardFrame` 导出（`shared/card_render/`）。卡面五项：奖章花结（徽章 + 级别色环 + 飘带）+ 级别芯片（S / M / L）+ 短标题（`localizedMilestoneTitle`）+ 宠物头像与名字 · 完成日期（短格式「28 Okt 2026」/「28 Oct 2026」）+ TailTopia 字标。
2. 视觉：底色 violet 155° 渐变 `AppColors.mint500 #9E83DA → mint #845EC9 → mint600 #6C48AE`；左对齐大标题；白色页脚放宠物信息；级别色只出现在花结环、飘带、芯片（L `gold` / M `mint` / S `triageGreen`，同 `milestone_celebration.dart` `_levelColor`）；**M 级飘带用 `mint600 #6C48AE` + 12px 白描边**（圆角接头），色环仍 `mint`。
3. **无二维码、无水印**（不挂 `CardWatermark`、不用 `card_qr.dart`）。
4. 宠物 1~5 只（账号级里程碑 = 全部 ACTIVE，按建档顺序）：只有白色页脚分支变化，上半部（字标 / 花结 / 标题）不随宠物数变。

**AC2 — 屏外挂载 + 等画上屏再截，不出白图** `[L0]`（D-21）
1. 卡片在调用方（3.2 是庆祝层）的 widget 树里**屏外挂载**（`Positioned` 移出可视区 / `Transform.translate`），**不用** `Offstage`、`Visibility(visible:false)`、`Opacity(0)`（这几种不绘制，截出来是空图）。
2. 截图前等徽章（asset）、宠物头像（网络）、字标（SVG）、花结（SVG，若有）**首帧真正画上屏**（沿用 `TsRemoteArtImage.waitPainted` 的思路：每个图片节点挂 `Completer<bool>`，`frameBuilder` 里 `frame != null` 才 complete(true)；遍历 boundary 子树收集后 `Future.wait(...).timeout(8s)`，再 `await WidgetsBinding.instance.endOfFrame`）。
3. 未就绪 / 超时 / 解码失败 → 截图函数返回 `null`（调用方按「出图失败」处理）；**绝不**返回空白或纯白 PNG。头像加载失败但已回落到物种默认图（`PetSpeciesAvatar`）算「已画上屏」（回落图是有内容的）。
4. 有 widget 测试：就绪 → 返回非空 PNG；图片未完成 → 超时返回 null；不在树上（boundary 未挂载）→ null。

**AC3 — `BragPostEntry` 扩参（不加 `milestoneCode`）** `[L0]`（D-21、G-6）
1. `BragPostSource` 加 `milestoneCelebration('milestone_celebration')`。
2. `BragPostEntry.open(context, {cardPng, text, ContentType preset = ContentType.daily, List<int>? petIds, BragPostSource? source})`：新参数全部可选、默认值与现在逐字节等价——原 4 个来源（`tailsonality_result` / `tailsonality_match` / `match_preview` / `boarding_pass`）调用点**不改一行**、行为不变（回归测试）。
3. 拆出两个可单独调用的步骤（3.2 需要「先转好 JPEG → 关庆祝页 → 再开发布页」）：`static Future<Uint8List?> prepare(BuildContext context, Uint8List png, {double toastExtraBottom = 0})`（失败 → `showAppToast(shareCardExportError, extraBottom: …)` 并返回 null）与 `static Future<void> openPrepared(BuildContext context, {required Uint8List jpeg, required String text, ContentType preset = ContentType.daily, List<int>? petIds, BragPostSource? source})`；`open` = `prepare` + `openPrepared`。`_busy` 防重入语义保留。
4. **绝不**向 `PublishComposePage.open` 传 `milestoneCode`（那是「去发布」自动打卡用的，会把新帖关联成该里程碑的打卡内容）。

**AC4 — 里程碑来源预填与发布成功来源** `[L0]`（PRD §3.6 交互、§7）
When 以里程碑来源打开发布页，Then：
- `preset = ContentType.growthMoment`（Diary）；用户仍可改 Moment；
- `initialPetIds` = 这只宠物；账号级里程碑 = 全部 ACTIVE 宠物（D5）；
- `initialImages` = 1 张里程碑卡 JPEG（用户可删、可再加）；
- `initialText`：单宠「{宠物名} baru membuka tonggak "{短标题}"! 🎉」/ EN「{name} just unlocked the "{title}" milestone! 🎉」；账号级且 ≥2 只 ACTIVE 宠物「Kami baru membuka tonggak "{短标题}"! 🎉」/ EN「We just unlocked the "{title}" milestone! 🎉」；账号级只 1 只 → 单宠句式；**不带 `#话题`**；`{短标题}` = `localizedMilestoneTitle`（与卡面一致）。
- 发布成功：`PublishComposePage` 收到 `source = milestoneCelebration` 时，在既有 `diary_published` 上追加属性 `source: 'milestone_celebration'`（仅此来源追加；其他来源属性仍「只有 pet_count」）。

**AC5 — `brag_post_tapped` 新来源带 code / level** `[L0]`（PRD §4 FR-156）
`BragPostEntry.reportTap(BragPostSource source, {Map<String, Object> extra = const {}})`：`extra` 合并进属性。里程碑来源由庆祝页（3.2）调用 `reportTap(BragPostSource.milestoneCelebration, extra: {'code': item.code, 'level': 'S'|'M'|'L'})`；原 4 处调用点不传 `extra`，属性仍只有 `source`（`test/tailsonality/brag_entries_test.dart`:144 的断言保持绿）。`BragPostEntry.open` 仍不收 `milestoneCode`。

## Tasks / Subtasks

- [ ] **T1 里程碑卡组件**（AC1；D-21、UI D6 / D7）
  - [ ] 1.1 新建 `lib/features/profile/presentation/widgets/milestone_share_card.dart`：`MilestoneShareCard({required MilestoneItem item, required List<CelebrationPet> pets, required DateTime completedAt})`，`build` 直接按 1080 画布坐标排版（被 `CardFrame` 包住，`CardFrame` 负责 `FittedBox` 缩放；`CardRenderPipeline.capture` 按画布宽反算倍率）。
  - [ ] 1.2 画布坐标（D6，照稿说明，非 App token）：字标白色宽 200 @ (72, 76)（`assets/brand/wordmark_brand.svg`，`ColorFilter.mode(Colors.white, BlendMode.srcIn)`）→ 标头 `milestoneCelebrateUnlocked` 大写 28px、白 72% @ top 222 → 级别芯片 28px @ top 276（`milestoneLevelChipS/M/L`）→ 花结 440×620 右 40 上 28（外圈 24 瓣白 + 级别色环 16 + 白环 + 徽章 276）→ 标题 92 / w800 / 行高 1.04 / 字距 −2、左对齐、最多 3 行、距页脚 52 → 白色页脚高 208。
  - [ ] 1.3 页脚（D7）：1 只——头像 112 + 名字 40 / w700 + 日期 28 muted；2–5 只——头像 88 叠放（每只向左 −22，白 6 描边）+ 名字一句（`names[0..n-2].join(', ')` 再用现有 `l10n.diaryPetsPair(head, last)` 得「Orenji, Kopi & Boba」；**不用 `dkk.`**）2 只 40px、≥3 只 34px、最多 2 行省略；日期 28 muted（`AppColors.muted`）。
  - [ ] 1.4 徽章用 `MilestoneBadge(code, size: 276, level, fallback: 紫渐变奖杯)`（与庆祝页回落一致）；新编码 `first_friend` / `first_save` 素材未到 → 回落，不报错。
  - [ ] 1.5 **花结素材兜底**：正式 SVG（24 瓣外圈 + 级别色环 + 两条飘带，按 L / M / S 三色或单色 + 代码着色）未到前，用「白色外圆 + 级别色环 16 + 白环 + 徽章」的简化同心圆兜底（不画瓣与飘带），素材路径预留 `assets/milestone/rosette.svg`（或三色三份，按设计交付命名），用 `AssetManifest` 判存在后切换（同 `MilestoneBadgeAssets` 做法）。🔴 稿中花结是示意绘制，**不要照稿手写 CustomPainter 定稿**（UI D6 说明）。
  - [ ] 1.6 短日期：新增 `formatMilestoneShortDate(DateTime, Locale)`（id：Jan Feb Mar Apr Mei Jun Jul Agu Sep Okt Nov Des；en：Jan … Dec），手写月份表，照 `milestone_celebration.dart` `_formatLongDate`（:286-299）的做法，不依赖 intl locale 数据。
  - [ ] 1.7 字体：卡面用 App 主题字体（Poppins，UI D6），不另引字体包。
- [ ] **T2 等画上屏与截图**（AC2；D-21、参考 `c496e3c3`）
  - [ ] 2.1 新建 `lib/shared/card_render/card_painted_image.dart`（或放 `features/profile/presentation/widgets/`，二选一，说明理由）：`CardPaintedImage` 包装 `Image.asset` / `Image.network`（`AppImage` 的 provider），`frameBuilder` 首帧 → `_painted.complete(true)`、`errorBuilder` → 回落图并 complete(true)（回落图有内容）；静态 `waitPainted(BuildContext boundary, {Duration timeout = const Duration(seconds: 8)})` 遍历子树收集全部 `CardPaintedImage` 状态（写法照 `ts_remote_art_image.dart`:33-50）。
  - [ ] 2.2 SVG（字标 / 花结）：截图前预加载进 flutter_svg 缓存——`final loader = SvgAssetLoader(path); svg.cache.putIfAbsent(loader.cacheKey(null), () => loader.loadBytes(null));` 并 await；挂载后再等一帧。L2 必须核对导出图里字标在。
  - [ ] 2.3 新建 `Future<Uint8List?> captureMilestoneShareCard(GlobalKey boundaryKey)`：`waitPainted` 为 false → null；否则 `CardRenderPipeline.capture(boundaryKey: …, canvas: CardCanvas.square)`。测试缝 `@visibleForTesting static Future<Uint8List?> Function(GlobalKey)? captureForTest`（照 `TailsonalityResultPage.captureForTest`，`tailsonality_result_page.dart`:206-208）。
  - [ ] 2.4 屏外挂载示例（供 3.2 用）：`Positioned(left: -2000, top: 0, width: 360, child: CardFrame(boundaryKey: k, canvas: CardCanvas.square, child: MilestoneShareCard(...)))`，放在庆祝层 `Stack` 内、`IgnorePointer` 包住。宽 360 逻辑像素即可（导出按画布宽 1080 反算倍率）。
- [ ] **T3 `BragPostEntry` 扩参**（AC3、AC5；D-21、G-6）
  - [ ] 3.1 `lib/features/content/presentation/brag_post_entry.dart`：加枚举值；`reportTap` 加 `extra`；拆 `prepare` / `openPrepared`；`open` 加三个可选参数并委托。`openPrepared` 内 `PublishComposePage.open(context, preset: preset, initialText: text, initialImages: [jpeg], initialPetIds: petIds, entrySource: source)`。
  - [ ] 3.2 `prepare` 失败 toast 带 `extraBottom` 参数（3.2 的 D3 要传 96，见 `app_toast.dart`:36-45）；默认 0 = 原位置不变。
  - [ ] 3.3 类注释更新：新增第 5 个来源「里程碑庆祝页」，仍「不自建发布流程」。
- [ ] **T4 发布页来源透传**（AC4；G-6）
  - [ ] 4.1 `lib/features/content/presentation/publish_compose_page.dart`：构造与 `open`（:115-157）加 `BragPostSource? entrySource`；`_publish` 成功分支（:481-485）`diary_published` 属性：`{'pet_count': n, if (widget.entrySource == BragPostSource.milestoneCelebration) 'source': 'milestone_celebration'}`。其余逻辑不动（`initialPetIds` 已支持预选并过滤非 ACTIVE，:330-332）。
  - [ ] 4.2 ⚠️ 本文件会被 4.4 / 5.2 继续改不同区域：本 story 只改构造参数与 `_publish` 成功分支两处，不重排、不格式化。
- [ ] **T5 预填文案与宠物**（AC4；PRD §7、UI D4 / D5）
  - [ ] 5.1 ARB（`app_id.arb` / `app_en.arb`，前缀 `milestoneShare*`，P-10）：
    - `milestoneShareComposeSingle`：ID `{name} baru membuka tonggak "{title}"! 🎉` / EN `{name} just unlocked the "{title}" milestone! 🎉`（placeholders: name, title）
    - `milestoneShareComposeMulti`：ID `Kami baru membuka tonggak "{title}"! 🎉` / EN `We just unlocked the "{title}" milestone! 🎉`（placeholder: title）
    改完 `flutter gen-l10n`；不改生成的 `app_localizations_*.dart`。
  - [ ] 5.2 新建 `lib/features/profile/domain/milestone_compose_preset.dart`：`({String text, List<int> petIds}) milestoneComposePreset({required MilestoneItem item, required int petId, required String petName, required List<CelebrationPet> accountPets, required AppLocalizations l10n, required Locale locale})`——非账号级：`[petId]` + Single；账号级：`accountPets` 的 id（≥2 → Multi，=1 → Single 用那只的名字）。`CelebrationPet` 需带 `id`（2.3 定义时若无 id，本 story 补上）。
- [ ] **T6 测试**（全部 AC；P-12）
  - [ ] 6.1 `test/profile/milestone_share_card_test.dart`：S / M / L 三级各渲染一次（M 级飘带色 `mint600` + 白描边存在，用 key 找节点）；1 / 2 / 3 / 5 只宠物页脚分支（头像尺寸 112 / 88、名字字号 40 / 34、名字句式「A & B」「A, B & C」、无 `dkk.`）；无二维码 / 无水印（找不到 `CardQr` / `CardWatermark` 类型）；花结素材缺失走兜底；徽章素材缺失走回落。
  - [ ] 6.2 `test/profile/milestone_share_card_capture_test.dart`（参考 `test/tailsonality/ts_remote_art_painted_test.dart`）：全部就绪 → 非空；网络头像一直不回 → 超时 null（用 fake clock / 短 timeout）；boundary 未挂载 → null；头像 404 → 回落物种图且返回非空。
  - [ ] 6.3 `test/content/brag_post_test.dart` 扩：`open` 默认参数仍 `preset: daily`、不带 petIds（回归，4 来源）；`openPrepared` 带 `growthMoment` + petIds + `entrySource`；`prepare` 失败 → toast、返回 null、不开发布页；`reportTap` 带 extra 时属性合并、不带时只有 source。
  - [ ] 6.4 `test/content/publish_diary_published_analytics_test.dart` 扩：`entrySource = milestoneCelebration` 发 Diary 成功 → `{pet_count, source: 'milestone_celebration'}`；无来源仍只有 `pet_count`（原用例不变）。
  - [ ] 6.5 `milestoneComposePreset` 单测：单宠 / 账号级 3 宠（Multi）/ 账号级 1 宠（Single）、id / en、文本不含 `#`。
  - [ ] 6.6 `test/analytics/v112_events_test.dart`：`brag_` 前缀已登记，无新事件名；确认绿。
  - [ ] 6.7 现有 `flutter test` 全绿。
- [ ] **T7 L2**：在模拟器用 debug 钩子（或 3.2 完成后的真实入口）导出 S / M / L、1 / 3 / 5 只宠物共 ≥ 6 张卡，对 D6 / D7 打还原度 ≥ 95%；确认 Feed 瀑布流约 170px 宽时标题仍可读、无白图。

## Dev Notes

### 现状（核 `dev_1.4.2` @ c496e3c3）

- `BragPostEntry`（`content/presentation/brag_post_entry.dart`:27-66）：`reportTap(source)` 只带 `source`；`open(context, {cardPng, text})` → `prepareBragPostImage`（isolate 转 JPEG）失败 toast `shareCardExportError` 不开发布页 → `PublishComposePage.open(preset: ContentType.daily, initialText, initialImages)`，**写死 Moment、不透传宠物**（架构 §1.2 第 10 条）。`_busy` 静态防重入；测试缝 `prepareForTest`。
- `PublishComposePage.open`（`publish_compose_page.dart`:115-157）已有 `preset` / `initialText` / `initialImages` / `initialPetIds` / `milestoneCode`；`initialPetIds` 预选时过滤掉非 ACTIVE（:330-332）；Diary 必须 ≥1 只宠物（:436-437）。发布成功只有 `diary_published {pet_count}`（:481-485），**App 没有通用的「发帖成功」事件**。
- 卡片管线：`CardCanvas.square`（1080×1080 r48）、`CardFrame`（RepaintBoundary + FittedBox，水印挂 boundary 外）、`CardRenderPipeline.capture`（按画布宽反算倍率 + 白底合成防黑角）——都**不认识「分享卡」**，直接复用，不往里加里程碑逻辑。
- 白图教训（`c496e3c3`）：`Image.memory` / `Image.asset` / 网络图都是异步解码，解码前一帧不画；`toImage` 截到空 → `_composeOnWhite` 垫白 = 纯白 PNG。`TsRemoteArtImage.waitPainted`（`tailsonality/presentation/widgets/ts_remote_art_image.dart`:33-50）用 `frameBuilder` 首帧 + 子树遍历 + 超时解决；本 story 做成通用件给里程碑卡用（不改 Tailsonality 那份）。
- 字标 `assets/brand/wordmark_brand.svg` 已在三处卡面用（`result_share_card.dart`:163 等）；`flutter_svg ^2.0.10`。
- 色值：`AppColors.mint` #845EC9 / `mint500` #9E83DA / `mint600` #6C48AE（`colors.dart`:19-21，常量名 `mint*` 是遗留别名，实为紫）；`gold` #F6A609、`triageGreen` #1F9E6A。

### 拍板点（按推荐实现，Completion Notes 复述）

1. **发帖成功来源落在 `diary_published`**：PRD §4 要「发帖成功事件中能区分来自里程碑庆祝页」，App 只有 `diary_published` 这一个成功事件；用户若手动改成 Moment 则不上报来源（Moment 无成功事件）。如需覆盖 Moment，另开事件（如 `publish_post_succeeded`）需产品确认。
2. **拆 `prepare` / `openPrepared`**：D2 要求「生成卡图 → 关庆祝页 → 打开发布页」且失败时庆祝页不关，转 JPEG 必须在关页前完成；`open` 原样保留给 4 个老来源。
3. **花结兜底**：简化同心圆，正式 SVG 到货替换；不写 CustomPainter 定稿。

### 禁止事项与踩坑

- 不传 `milestoneCode`；不加二维码 / 水印；不用 `Offstage` / `Opacity(0)` / `Visibility` 挂卡。
- 新文案只进 ARB（两份同步）+ `flutter gen-l10n`；不硬编码；卡面不出现后端中文。
- 宠物名是用户数据：不进日志、不进埋点（`pet_names.dart` 头注释 §5）。
- 不整文件 `dart format`；`publish_compose_page.dart` 只做最小 diff。
- 头像用 `AppImage` 的缩略图 URL（OSS 实时缩放），导出 112px 头像用 `thumbWidth: 240` 左右即可，别拉原图。

### 测试

- 云端只跑 **L0**：`flutter analyze`、`flutter test`。**L2（卡面视觉、导出图、还原度）待本地验收**，Completion Notes 写明「L1/L2 待本地验收」，不得标 done。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/1-4-2追更宠物·里程碑·话题prd.md#3.6 交互、里程碑卡图、#4 FR-156、#7]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-social-topic-delta.md#D-21、G-6、P-9、P-10]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/ui-1-4-2-social-topic-integrated-v1.html#D4、D5、D6、D7]
- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-social-topic.md#Story 3.1]
- 代码：`content/presentation/brag_post_entry.dart`、`content/presentation/publish_compose_page.dart`、`shared/card_render/*`、`tailsonality/presentation/widgets/ts_remote_art_image.dart`、`tailsonality/presentation/tailsonality_result_page.dart`:200-240、`profile/presentation/widgets/milestone_badge.dart`、`profile/presentation/widgets/pet_names.dart`。

## Dev Agent Record

### Agent Model Used

（实现时填写）

### Debug Log References

### Completion Notes List

- create-story：上下文分析完成，已生成完整开发指引（2026-11-04）。
- 实现完成时必须写明：「云端只跑 L0；L1/L2 待本地验收」。

### File List

## 待确认 / 发现的规划冲突

1. **「发布成功事件带 source」无现成事件承载**：App 没有通用发帖成功事件，本 story 落在 `diary_published`（见拍板点 1），Moment 不覆盖——需产品确认是否够用。
2. **epics FR 覆盖表**写「FR-156.3 / .4 → 3.1、FR-156.1 / .2 → 3.2」，但 Story 3.1 的 AC 实际是 .1（入口与预填）/ .2（卡图），3.2 的 AC 是 .3（次入口）/ .4（缺陷修复）——覆盖表与 story 正文对调，以正文为准。
3. **花结 / 新徽章素材**未交付，按兜底实现；素材命名（单份代码着色 vs 三色三份）需设计确认后定路径。
4. UI D4 示例截图里「Untuk:」三只宠物都在、只选中 Orenji；本 story 只负责预选，不改 chips 展示（原有模块照线上）。
