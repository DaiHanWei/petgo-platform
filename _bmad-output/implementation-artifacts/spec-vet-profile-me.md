---
title: '兽医端第五屏：兽医「我的」页 1:1 还原（vet-profile.html）'
type: 'feature'
created: '2026-06-18'
status: 'done'
baseline_commit: '5890b14'
context:
  - '{project-root}/_bmad-output/pages/vet-profile.html'
  - '{project-root}/_bmad-output/fidelity-audit.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 兽医「我的」Tab `vet_me_page.dart` 当前是标准 AppBar + 名字 + 在线 Switch + 登出钮（审计 🟡，兽医端 44%）：缺原型 `vet-profile.html` 的深色顶栏 #2B2540、个人信息卡（头像+名+3 统计卡）、在线状态分段选择、设置列表、版本号。

**Approach:** 按原型 1:1 重排呈现层：深色顶栏「Profil Saya」+ 个人信息卡（薄荷头像带在线点 + 名 + 3 统计卡）+ 在线状态分段控件 + 设置列表（含登出）+ 版本页脚。统计与状态走真实数据（done=history 长度、rating/total 无源显「—」，同 dashboard「不造假值」原则）；在线态仍是后端 bool（Online/Offline 真切，Sibuk 仅视觉占位）。**不动在线 provider 的 bool 语义 / 心跳 / IM 联动 / 登出逻辑。**

## Boundaries & Constraints

**Always:** 颜色走 `AppColors` token（禁裸 hex）；文案走 arb（en+id 双语）；保留 `vetDisplayName`、`vetLogoutButton` 两个 ValueKey 与 `_logout`/心跳/`_toggle`(in-flight 锁)/`vetOnlineStatusProvider` 联动原样；无数据字段不臆造（评分/总数无源→「—」；诊所名/SIP 无字段→不显）。

**Ask First:** 改在线 provider 的 bool 语义（加真 BUSY 态）、心跳间隔、`setOnline`/`logout` 调用或 `/home` 跳转。

**Never:** 不引后端契约新字段（VetMe 仅 id/displayName/status）；不引新依赖；不实现无数据来源的功能（Sibuk 忙碌态 / Edit Profil & SIP / Notifikasi 开关 → 仅 UI + 「V1 未提供」提示，不臆造后端）；不显伪造诊所名/认证机构。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 加载完成 | me().displayName + history().length | 顶栏「Profil Saya」+ 信息卡显名/头像首字母/在线点 + 3 卡(done 实/rating「—」/total「—」) | me/history 失败 → 名留空、统计「—」，不崩 |
| 切 Online/Offline | 点对应分段 | 走 `vetOnlineStatusProvider.toggle(bool)`（乐观+回滚+IM 联动）；选中段高亮薄荷 | 失败 → SnackBar `vetStatusUpdateFailed` + 回滚 |
| 点 Sibuk / Edit Profil / Notifikasi | 无后端 | SnackBar `vetChatToolUnavailable`(V1 未提供)，不导航、不改态 | N/A |
| 点 Keluar | — | 走原 `_logout`（停心跳→IM 登出→token 清→`/home`） | N/A |

</frozen-after-approval>

## Code Map

- `petgo_app/lib/features/vet/presentation/vet_me_page.dart` -- 目标页：重写 `build()`（深顶栏 + 信息卡 + 3 统计卡 + 分段状态 + 设置列表 + 版本）；心跳/生命周期/`_logout`/`_toggle` 逻辑不动
- `petgo_app/lib/features/vet/presentation/vet_inbox_page.dart` -- dashboard `_StatCard` 样式（薄荷浅底 + display 数字）的还原参照（本页内联同款）
- `petgo_app/lib/features/vet/data/vet_repository.dart` -- `me()`(displayName) / `history()`(done 计数) 数据源
- `petgo_app/lib/core/theme/colors.dart` -- token：vetTopBar、vetPrimary、vetSurface、goldTint、coral、muted
- `petgo_app/lib/l10n/app_en.arb` / `app_id.arb` -- 新增标签键（复用 `vetChatToolUnavailable`/`vetDashboardStatDone`/`vetDashboardStatRating`/`vetLogout`）
- `petgo_app/test/vet/vet_workbench_test.dart` -- AC2 由点 Switch 改为点「Online」分段，断言 `repo.online`

## Tasks & Acceptance

**Execution:**
- [x] `app_en.arb`/`app_id.arb` -- 新增：`vetProfileTitle`(Profil Saya/My profile)、`vetProfileAvailability`(STATUS KETERSEDIAAN/AVAILABILITY)、`vetStatusOnline`(Online)+`vetStatusOnlineSub`(Terima antrian)、`vetStatusBusy`(Sibuk)+`vetStatusBusySub`(Selesaikan dulu)、`vetStatusOffline`(Offline)+`vetStatusOfflineSub`(Istirahat)、`vetProfileStatTotal`(Total kasus/Total cases)、`vetProfileEditProfile`(Edit Profil & SIP)、`vetProfileNotif`(Notifikasi Antrian)、`vetProfileVersion`(TailTopia Vet Portal v1.0.0) -- 原型文案
- [x] `vet_me_page.dart` -- 重写 `build()`：深顶栏「Profil Saya」；信息卡（薄荷 60 头像+首字母+右下在线点 + 名 + 3 统计卡 done/rating「—」/total「—」）；在线分段控件（3 段，Online/Offline 走 `_toggle`、选中薄荷高亮，Sibuk→`vetChatToolUnavailable` 提示，段加 `vetStatusOnline`/`vetStatusOffline` key）；设置列表卡（Edit Profil & SIP / Notifikasi → 未提供提示；Keluar 红字走 `_logout` 保留 `vetLogoutButton`）；版本页脚；保留 `vetDisplayName` -- 1:1 还原
- [x] `vet_workbench_test.dart` -- AC2 由 `find.byKey(vetOnlineSwitch)` 改为点 `vetStatusOnline` 分段，断言 `repo.online` 为真 + displayName 仍渲染 -- design 同步

**Acceptance Criteria:**
- Given 加载完成，when 进入「我的」，then 顶栏深色「Profil Saya」、信息卡显名 + 3 统计卡(done 实数/rating「—」/total「—」)、在线分段控件 3 段。
- Given 离线态点「Online」分段，when 切换，then `vetOnlineStatusProvider` 转在线（provider 行为不变）、该段高亮薄荷。
- Given 点 Sibuk / Edit Profil / Notifikasi，then 出「V1 未提供」SnackBar、不导航。
- Given 点 Keluar，then 走原 `_logout` → `/home`。
- Given `flutter analyze` + `flutter test test/vet/`，then 全绿。

## Spec Change Log

- **CHECKPOINT 决议 A**：Sibuk/Edit Profil/Notifikasi 做诚实 stub（点击→`vetChatToolUnavailable`）；诊所名省略；**PDHI 认证徽章保留**为静态信任标（盾形 icon + `vetProfileVerified` 薄荷文案，对任意登录兽医成立，V1 均经后台审核）。新增键 `vetProfileVerified`。

## Design Notes

**无数据元素处置（CHECKPOINT 决议点）：** 原型 me 页含多个 V1 无后端支撑的元素——① 在线 Sibuk/忙碌态（provider 是 bool，加真 BUSY 属状态机改动，违 V1 轻量）② Edit Profil & SIP / Notifikasi 开关（无端点）③ 诊所名「Klinik … Jakarta Selatan」+ PDHI 认证徽章（VetMe 无字段）。**推荐**：①② 保留原型 UI 形 + 点击 `vetChatToolUnavailable` 提示（同会话页工具 stub 模式，诚实）；③ 纯展示假数据 → **省略**诊所行（不臆造）；PDHI 徽章作为静态信任标（V1 兽医账号均经后台审核，"已认证"对任意登录兽医成立）可保留或省略，Dai 定。统计：done=`history().length`（真）、rating/total 无源「—」。

**配色：** 统计卡复用 dashboard `_StatCard` 薄荷浅底款；分段选中态薄荷 `vetPrimary`、未选 `muted` 底；Keluar 红字 `coral`；头像 `vetPrimary` 圈 + 右下在线点。

## Verification

**Commands:**
- `cd petgo_app && flutter analyze` -- expected: No issues
- `cd petgo_app && flutter test test/vet/` -- expected: All tests pass
- `cd petgo_app && flutter gen-l10n` -- expected: 新键被 codegen 识别

**Manual checks:**
- 模拟器走工作台「我的」Tab（或 dev 深链 `/vet/workbench` 切 tab），对照 `vet-profile.html`：深顶栏、信息卡 3 卡、在线分段、设置列表、版本。
