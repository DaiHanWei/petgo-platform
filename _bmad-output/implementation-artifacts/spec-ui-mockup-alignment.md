---
title: 'UI 对齐设计稿——Home/Consult/Me 三屏差距修复(批次2)'
type: 'bugfix'
created: '2026-06-03'
status: 'done'
baseline_commit: '718a567'
context:
  - '{project-root}/_bmad-output/planning-artifacts/UX_DESIGN.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 实机与设计稿(TailTopia_V1_mockups S03/S11/S17)在三屏有真实视觉差距:① Home feed 卡无封面(纯文字白卡)② Consult 首页入口卡上下堆叠且历史项是纯文字「AI·GREEN」无胶囊/星评/时间 ③ Me 屏头像是灰图标、宠物状态露原始码「B」、邮箱不显示、编辑按钮是裸图标,且「我的发布」因前端字段名错位(读 text/imageUrls 而后端给 body/firstImageUrl)永远显示「#id」无标题无图。

**Approach:** 以前端 UI 修复为主对齐设计稿;唯一后端改动是 `/me` 的 `UserProfileResponse` 补 email(实体已有,DTO 漏带)。修复后继续逐屏审计剩余 17 屏。

## Boundaries & Constraints

**Always:** 复用既有 i18n key(petStatusA/B/C、meEditPetProfile、consultProbabilisticOnline 等)与主题色(`core/theme/colors.dart`);改 arb 后跑 `flutter gen-l10n`;email 为 PII——仅 `/me` 本人态返回,**绝不**进 Feed/作者视图/日志(架构护栏)。

**Ask First:** 若 Consult 历史项需兽医真实头像图(后端 DTO 无 avatarUrl)而非首字母占位——需新增后端字段,先问;若要改 en arb 的 Moments/Tips(设计基准语言是印尼语,默认只改 id)——先问。

**Never:** 不改铃铛角标(已实现,实机无角标是 unreadCount=0/登录态,非缺陷);不改作者名回落逻辑(昵称空露 displayName 属 onboarding/数据问题);不改「编辑宠物档案」行的 hasPetProfile 门控(有意设计,S17 是有宠物用户的稿);不改页面"左上大标题"全局风格(blast 大);不引入新依赖/中间件;不动 Growth 门控逻辑。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Feed 卡有真实首图 | `firstImageUrl != null` | 顶部渲染 `Image.network` 封面(现状保留) | 加载失败回落占位彩块 |
| Feed 卡无图 | `firstImageUrl == null` | 顶部渲染「按 type/id 取色的柔彩块 + 该类 emoji」占位封面 | N/A |
| Me 宠物状态 | petStatus ∈ {A,B,C} | 显示对应 l10n 标签胶囊(A=有宠物/B=计划养/C=爱好者) | null/未知 → '-' |
| Me 我的发布 | 后端 item 含 body+firstImageUrl | 横向缩略图卡:左图(无图占位)+ body 首行标题 | body 空 → 显示占位文案,不再露 #id |
| Me 头像 | avatarUrl 空 | 彩色圆 + nickname 首字母大写 | nickname 空 → person 图标兜底 |
| Me 邮箱 | /me 返回 email | nickname 下方显示 email | email 空 → 仅显示 nickname |
| Consult 历史(AI) | dangerLevel∈{GREEN,YELLOW,RED} | 严重度色胶囊 + 本地化文案 + 相对时间 | N/A |
| Consult 历史(VET) | userStars 1..5 / null | 星形控件(null=未评分不显星) + 相对时间 | N/A |

</frozen-after-approval>

## Code Map

- `petgo_app/lib/shared/widgets/masonry_card.dart` -- 单卡渲染;`item.hasImage` else 分支补占位彩块封面(差距:Home 封面)
- `petgo_app/lib/features/content/domain/feed_item.dart` -- FeedItem 模型;`type`(DAILY/GROWTH_MOMENT/KNOWLEDGE)用于占位色/emoji 选择
- `petgo_app/lib/features/triage/presentation/triage_page.dart` -- Consult Tab 实际页(S11);入口卡布局 :76-107、`_EntryCard` :225-284、`_HistoryTile` :172-223、`_FreeBadge` :286-302 复用
- `petgo_app/lib/features/me/presentation/me_page.dart` -- Me 屏;头像 :255-260、宠物状态 :45、编辑按钮 :264-268、我的发布 :325-331、邮箱渲染 :263
- `petgo_app/lib/features/me/data/my_posts_repository.dart` -- MyPost.fromJson :16-21 字段映射 Bug(text→body, imageUrls→firstImageUrl)
- `petgo_app/lib/features/auth/domain/login_response.dart` -- UserProfile 模型 :33-62,补 email 字段
- `petgo_app/lib/l10n/app_id.arb` / `app_en.arb` -- tab 文案 + 新增问诊严重度/营业时间 label key
- `petgo-backend/src/main/java/com/tailtopia/auth/dto/UserProfileResponse.java` -- /me 聚合视图 :12-30,补 email(实体已有 getEmail())

## Tasks & Acceptance

**Execution:**
- [x] `petgo_app/lib/shared/widgets/post_cover.dart`(新增)+ `masonry_card.dart` -- 无图时渲染占位封面(柔彩块按 `item.type` 取色 + emoji 🐾/🌱/📖),高度按 id 错落;图加载失败回落同款占位。色 token 入 `core/theme/colors.dart`
- [x] `petgo_app/lib/features/me/data/my_posts_repository.dart` -- 修字段映射:`text` 读 `json['body']`、`firstImageUrl` 读 `json['firstImageUrl']`
- [x] `petgo_app/lib/features/me/presentation/me_page.dart` -- ① 头像 fallback 首字母彩圆(`_InitialAvatar`)② 宠物状态改 `_PetStatusChip`+`petStatusLabel(A/B/C)` ③ 编辑按钮改描边「Edit」胶囊 ④ 我的发布改横向缩略图卡(`_MyPostCard`)⑤ nickname 下显示 email
- [x] `petgo_app/lib/features/auth/domain/login_response.dart` -- UserProfile 加 `email` 字段 + fromJson 解析
- [x] `petgo-backend/.../auth/dto/UserProfileResponse.java` -- record 加 `String email`,`from()` 填 `u.getEmail()`(仅 /me 本人态)
- [x] `petgo_app/lib/features/triage/presentation/triage_page.dart` -- ① 入口卡改 `IntrinsicHeight`+`Row(Expanded×2)` 并排 ② `_EntryCard` 重写竖向大卡 + 分色底(AI 浅蓝/Vet 浅粉)+ emoji + Gratis 胶囊 ③ 卡下补营业时间 Text ④ `_HistoryTile` 重写:严重度胶囊 `_SeverityChip` + 相对时间 + 星形 `_Stars` + 头像 `_HistoryAvatar`
- [x] `petgo_app/lib/l10n/app_id.arb` + `app_en.arb` -- id `feedTabGrowth`→Tumbuh、`feedTabKnowledge`→Edukasi;新增 `triageLevelGreen/Yellow/Red`、`meEditButton`、`meNoPostCaption`;营业时间复用现有 `consultProbabilisticOnline`;已跑 `gen-l10n`
- [x] `petgo_app/test/widget_test.dart` -- 修 AC3 id 断言:'Tumbuh' 现合理出现在底栏+feed tab 两处 → `findsWidgets`

**Acceptance Criteria:**
- Given 一条无图 feed 帖,when 进 Home,then 卡顶显示按类取色的柔彩占位封面而非纯文字白卡
- Given 测试账号(petStatus=B),when 进 Me,then 状态区显示「计划养」标签胶囊而非「B」
- Given 我有 N 条发布,when 进 Me,then「我的发布」每项显示缩略图(或占位)+ 正文首行,不再出现「#id」
- Given /me 返回含 email,when 进 Me,then 昵称下显示邮箱
- Given 有 AI+兽医问诊历史,when 进 Consult,then 入口卡左右并排、历史项显示严重度胶囊/相对时间/星评
- Given `flutter analyze`,then 无 error;`flutter test` 既有用例不回归

## Design Notes

- 占位封面取色:用 `item.id % palette.length` 或 `item.type` 映射一组柔彩(参考设计稿:DAILY 暖米/GROWTH_MOMENT 浅绿/KNOWLEDGE 浅蓝/triage 浅粉),emoji 居中。避免引第三方,纯 Container+Text。
- 宠物状态/严重度标签建议抽共享 helper(状态映射参考 `pet_status_selector.dart:26-30`),勿与选择器重复硬编码。
- 相对时间用项目已依赖的 `package:intl`;兽医头像无后端 URL → 用 `vetDisplayName` 首字母占位(不新增后端字段,除非 Ask First 触发)。

## Verification

**Commands:**
- `cd petgo_app && flutter gen-l10n` -- expected: 成功生成,无报错
- `cd petgo_app && flutter analyze` -- expected: No issues found(或仅既有 info 级)
- `cd petgo_app && flutter test` -- expected: 既有用例全绿
- `cd petgo-backend && ./mvnw -q -B compile` -- expected: BUILD SUCCESS(email 字段)

**Manual checks:**
- 重新构建装模拟器(`--dart-define=PETGO_API_BASE_URL=http://10.0.2.2:8080`),逐屏 screencap 对比 `/tmp/petgo_audit/mock/std/s03|s11|s17.png` ✅ 已验:Home 彩块封面+错落、Consult 并排卡+历史胶囊、Me 首字母头像+邮箱+状态胶囊+缩略图发布

## Suggested Review Order

**封面占位体系（Home Feed + 我的发布的共同根因：缺图→彩块）**

- 入口：新增的占位封面组件，按内容类型取柔彩底+emoji
  [`post_cover.dart:9`](../../petgo_app/lib/shared/widgets/post_cover.dart#L9)
- feed 卡无图分支接入占位（替代纯文字白卡），高度按 id 错落
  [`masonry_card.dart:68`](../../petgo_app/lib/shared/widgets/masonry_card.dart#L68)
- 占位柔彩 token（守护栏：业务文件不硬编码 hex）
  [`colors.dart:40`](../../petgo_app/lib/core/theme/colors.dart#L40)

**Me 屏对齐 S17**

- 确凿 Bug 修复：我的发布字段映射 text→body / firstImageUrl
  [`my_posts_repository.dart:19`](../../petgo_app/lib/features/me/data/my_posts_repository.dart#L19)
- 宠物状态码 A/B/C → 友好标签胶囊
  [`me_page.dart:412`](../../petgo_app/lib/features/me/presentation/me_page.dart#L412)
- 头像首字母彩圆 fallback
  [`me_page.dart:302`](../../petgo_app/lib/features/me/presentation/me_page.dart#L302)
- 我的发布横向缩略图卡
  [`me_page.dart:365`](../../petgo_app/lib/features/me/presentation/me_page.dart#L365)

**Consult 屏对齐 S11**

- 入口卡左右并排（IntrinsicHeight + Row）
  [`triage_page.dart:80`](../../petgo_app/lib/features/triage/presentation/triage_page.dart#L80)
- 历史项重写：严重度胶囊 + 星评 + 相对时间 + 头像
  [`triage_page.dart:194`](../../petgo_app/lib/features/triage/presentation/triage_page.dart#L194)
- 严重度三色胶囊映射
  [`triage_page.dart:303`](../../petgo_app/lib/features/triage/presentation/triage_page.dart#L303)

**邮箱（PII，仅 /me 本人态）**

- 后端聚合视图补 email（实体已有 getEmail）
  [`UserProfileResponse.java:16`](../../petgo-backend/src/main/java/com/tailtopia/auth/dto/UserProfileResponse.java#L16)
- 前端模型解析 email
  [`login_response.dart:61`](../../petgo_app/lib/features/auth/domain/login_response.dart#L61)

**文案与测试（peripherals）**

- id tab 文案对齐设计稿（Tumbuh/Edukasi）
  [`app_id.arb:93`](../../petgo_app/lib/l10n/app_id.arb#L93)
- AC3 断言修正（'Tumbuh' 现底栏+feed tab 两处）
  [`widget_test.dart:79`](../../petgo_app/test/widget_test.dart#L79)
