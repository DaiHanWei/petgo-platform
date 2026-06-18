---
title: '社区内容流 i18n 收口（②）'
type: 'chore'
created: '2026-06-16'
baseline_commit: 'f652c8efbede3364a130a377fac78bae56ec2903'
status: 'done'
context:
  - '{project-root}/_bmad-output/core-pages-UX.md'
  - '{project-root}/_bmad-output/pages/create.html'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** ② 社区内容流的屏已对齐薄荷绿（Feed/详情/通知/迷你卡），但 `publish_compose_page.dart`（标题/取消/分享/相机/3 条输入 hint/加图，共 8 处）与 `home_page.dart` 问候（4 时段 + 「Apa kabar, $name?」，共 5 处）仍**硬编码印尼语**，违反「用户可见文案走 arb、不写死」护栏。

**Approach:** 把这些串迁入 `lib/l10n` arb（en 为模板 + id）。能复用既有键的复用（`publishComposeTitle`/`publishButton`/`publishAddImage`），其余新增。纯前端、不改行为/后端/主题。

## Boundaries & Constraints

**Always:** 复用既有 arb 键优先（DRY）；新增键 en+id 成对、每条 ≤1 emoji；保持 mint+cream；印尼语为产品默认显示语言（涉及断言的测试用 `locale: Locale('id')`）。

**Ask First:** 无（纯收口，无设计决策）。

**Never:** 不动后端契约 / DB / 全局主题；**不实现发布审核状态流 P-39/39b/39c**（原型「异步 AI 审核中 5s」与现状「发布时同步 422 拦截」不符，已记入 deferred-work）；不碰 `PETGO_MOCK`、目录名、DB 默认等故意保留标识。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 印尼语环境 | 设备/手动 locale=id | 发布页与首页全部文案显示印尼语，且取自 arb（源码无对应硬编码串） | N/A |
| 英语环境 | locale=en | 同位置显示英文（arb en 值） | N/A |
| 问候时段 | hour <11 / <15 / <18 / else | 早 / 午 / 傍 / 晚 四态各取对应 arb 键（各 1 emoji） | N/A |

</frozen-after-approval>

## Code Map

- `lib/features/content/presentation/publish_compose_page.dart` -- 标题(L237)/取消(L232)/分享(L252)/相机(L305)/3 hint(L331-335)/加图(L505) 文案；`_hint` 改接 l10n
- `lib/features/content/presentation/home_page.dart` -- `_GreetingHeader._greeting`(L189) 四态 + 「Apa kabar」(L223)
- `lib/l10n/app_en.arb` / `app_id.arb` -- 新增 10 键
- `test/content/beranda_home_test.dart` -- 补 `locale: Locale('id')`，使问候断言走 arb id

## Tasks & Acceptance

**Execution:**
- [x] `lib/features/content/presentation/publish_compose_page.dart` -- 文案全部走 `l10n`：'Batal'→新 `publishCancel`；'Buat Postingan'→复用 `publishComposeTitle`；'Bagikan'→复用 `publishButton`；'Kamera'→新 `publishCamera`；`_hint` 改签名 `(c, l10n)` 三态→新 `publishHintDaily/Growth/Knowledge`；'Tambah foto'→复用 `publishAddImage`
- [x] `lib/features/content/presentation/home_page.dart` -- `_greeting` 改接 l10n 四态→新 `greetingMorning/Afternoon/Evening/Night`；'Apa kabar, $name?'→新 `greetingHowAreYou(name)`；如缺则 import `app_localizations`
- [x] `lib/l10n/app_en.arb` + `app_id.arb` -- 增 10 键：`publishCancel`、`publishCamera`、`publishHintDaily/Growth/Knowledge`、`greetingMorning/Afternoon/Evening/Night`、`greetingHowAreYou`（en 模板带 `{name}` placeholder 元数据）；保持 en/id 键完全对齐；`flutter gen-l10n`
- [x] `test/content/beranda_home_test.dart` -- MaterialApp 加 `locale: const Locale('id')`，保持断言 `'Apa kabar, Aurel?'`（迁移后经 arb id 渲染同文案）

**Acceptance Criteria:**
- Given 设备 locale=id, when 打开发布页, then 标题/取消/分享/相机/输入 hint/加图全为印尼语且来自 arb；`grep` 源码无这些硬编码印尼语串
- Given 设备 locale=en, when 打开发布页与首页, then 对应文案显示英文
- Given 任一时段, when 打开首页, then 显示对应时段问候 + 「Apa kabar, {name}?」(id)
- Given 本地 `flutter gen-l10n`/`analyze`/`test`, when 执行, then L0 全绿（含 microcopy J2 键一致性）

## Verification

**Commands:**
- `cd petgo_app && flutter gen-l10n` -- expected: 无错误
- `cd petgo_app && flutter analyze` -- expected: No issues found
- `cd petgo_app && flutter test` -- expected: 相关测试全绿
- `cd petgo_app && grep -nE "'(Batal|Kamera|Tulis satu kalimat|Bagikan tips|Apa yang terjadi|Apa kabar|Selamat (pagi|siang|sore|malam))" lib/features/content/presentation/*.dart` -- expected: 无输出（硬编码已清）

## Suggested Review Order

- 入口：新增 10 键（en 模板，含 `{name}` placeholder）——迁移目标全貌
  [`app_en.arb:505`](../../petgo_app/lib/l10n/app_en.arb#L505)
- 发布页文案全部走 l10n：标题复用 `publishComposeTitle`，`_hint` 改接 l10n 三态
  [`publish_compose_page.dart:237`](../../petgo_app/lib/features/content/presentation/publish_compose_page.dart#L237)
  [`publish_compose_page.dart:328`](../../petgo_app/lib/features/content/presentation/publish_compose_page.dart#L328)
- 首页问候四时段 + `greetingHowAreYou(name)` 走 l10n
  [`home_page.dart:189`](../../petgo_app/lib/features/content/presentation/home_page.dart#L189)
- 测试固定 `locale('id')` 使问候断言走 arb id
  [`beranda_home_test.dart:28`](../../petgo_app/test/content/beranda_home_test.dart#L28)
