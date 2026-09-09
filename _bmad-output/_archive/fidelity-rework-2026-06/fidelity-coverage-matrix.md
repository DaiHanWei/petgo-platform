# 保真覆盖矩阵（Coverage Matrix）

> 2026-06-18。全量 53 个 `pages/*.html`（含 `index.html` 导航页，跳过 → 52 屏）。
> 这是逐屏重新基线的骨架。**保真状态一栏全部从「待重新基线」起步**——不沿用任何历史分。
> 可达方式：`DEV_ROUTE`=带 DEV_USER/DEV_VET + DEV_ROUTE 直达 · `交互`=需走流程/操作到达 · `弹窗/态`=需触发对话框或特定状态 · `待核实`=实现与入口需先查。
> 路由来自 `lib/core/router/app_router.dart`。文件列为最佳已知，标「待核实」者打分前必先确认。

## 启动 / 鉴权 / 引导（9）

| HTML | 屏 | 路由/入口 | Flutter 文件 | 可达方式 | 保真状态 |
|---|---|---|---|---|---|
| splash | 启动屏 | `/splash` | splash_page | DEV_ROUTE | 待重新基线 |
| login | 登录页 | `/login` | login_page | DEV_ROUTE(guest) | 待重新基线 |
| login-gate | 登录门控弹窗 | requireLogin 触发 | shared/widgets/login_hard_dialog | 弹窗(guest 点受控) | 待重新基线 |
| onboard | 建档引导 | `/onboarding/profile` | profile_onboarding_page | DEV_ROUTE | 待重新基线 |
| nickname | 设昵称(进度条) | `/onboarding/nickname` | nickname_page | DEV_ROUTE | 待重新基线 |
| pet-select | 宠物状态选择 | `/onboarding/pet-status` | pet_status_page | DEV_ROUTE | 待重新基线 |
| pet-create | 创建宠物档案 | `/profile/create` | pet_profile_create_page | DEV_ROUTE | 待重新基线 |
| pet-edit | 编辑宠物档案 | `/profile/edit` | pet_profile_edit_page | DEV_ROUTE(种子) | 待重新基线 |
| pet-success | 建档成功庆祝 | `/profile/created` | 待核实 | 交互(建档完成) | 待重新基线 |

## 社区 / Feed / 发布（9）

| HTML | 屏 | 路由/入口 | Flutter 文件 | 可达方式 | 保真状态 |
|---|---|---|---|---|---|
| feed | 登录态首页 | `/home` | content/home_page + feed_view + masonry_card | DEV_ROUTE(种子) | 已改单列·待重判 |
| feed-guest | 游客首页 | `/home` (guest) | home_page(guest 态) | DEV_ROUTE(guest) | 待重新基线 |
| feed-empty | Feed 空态 | `/home` 空数据 | home_page EmptyState | 态(空 mock) | 待重新基线 |
| feed-error | Feed 失败态 | `/home` 错误 | home_page error 分支 | 态(断网/错误) | 待重新基线 |
| detail | 内容详情 | `/content/:id` | content detail_page(待核实) | DEV_ROUTE | 待重新基线 |
| create | 发布页 | `/publish` | content/publish_compose_page | DEV_ROUTE | 已改·待重判 |
| publish-reviewing | 发布审核中 | 发布后态 | 待核实 | 交互(发布) | 待重新基线 |
| publish-done | 发布成功 | 发布后态 | 待核实 | 交互(发布) | 待重新基线 |
| publish-rejected | 发布被拒 | 审核拒态 | 待核实 | 交互(审核拒) | 待重新基线 |

## 分诊 AI（4）

| HTML | 屏 | 路由/入口 | Flutter 文件 | 可达方式 | 保真状态 |
|---|---|---|---|---|---|
| ai-upload | 分诊上传 | `/triage/upload` | triage/triage_upload_page | DEV_ROUTE | 待重新基线 |
| ai-result | 分诊结果(壳) | upload→结果 | triage_result_view | 交互(出结果) | 待重新基线 |
| ai-result-green | 绿态结果 | 结果 GREEN | triage_result_view green | 交互/mock 强制 | 待重新基线 |
| ai-result-red | 红态全屏 | 结果 RED | red_alert_overlay + triage_red_result | 交互(需 RED 结果) | 已改·待重判 |

## 问诊 / 咨询（用户侧，5）

| HTML | 屏 | 路由/入口 | Flutter 文件 | 可达方式 | 保真状态 |
|---|---|---|---|---|---|
| konsultasi | 问诊 hub tab | `/triage` | triage/triage_page | DEV_ROUTE(种子) | 已改·待重判 |
| konsultasi-home | 咨询入口 | `/consult` | consult/consult_entry_page | DEV_ROUTE(种子,在线态) | 已改·待重判 |
| match-wait | 匹配等待 | `/consult/waiting/:id` | consult/consult_waiting_page | 态(需 WAITING 会话) | 已改·待重判 |
| chat | 用户-兽医会话 | `/consult/conversation/:id` | consult 会话页(待核实) | 态(需 IN_PROGRESS) | 待重新基线 |
| rate | 评分弹窗 | 会话结束补弹 | consult/consult_rating_dialog | 弹窗(pendingRating) | 待重新基线 |

## 成长档案 / 里程碑（9）

| HTML | 屏 | 路由/入口 | Flutter 文件 | 可达方式 | 保真状态 |
|---|---|---|---|---|---|
| paspor | 成长档案 tab | `/profile` | profile/growth_archive_page | DEV_ROUTE(种子) | **已确认 ~45%·须整屏重做** |
| catatan-calendar | 档案日历视图 | `/profile` 日历切换 | growth_archive ArchiveCalendar | 交互(切日历) | 待重新基线 |
| timeline-empty | 时间线空态 | `/profile` 空 | growth_archive 空分支 | 态(空 mock) | 待重新基线 |
| milestone | 里程碑列表 | `/profile/milestones` | profile/milestone_list_page | DEV_ROUTE(种子) | 待重新基线 |
| milestone-sheet | 里程碑详情抽屉 | 列表点条目 | 待核实(sheet) | 弹窗(点里程碑) | 待重新基线 |
| milestone-unlock | 里程碑解锁庆祝 | 解锁触发 | widgets/milestone_celebration | 弹窗(解锁) | 待重新基线 |
| badge-gallery | 徽章图鉴 | 待核实 | 待核实(可能未实现) | 待核实 | 待重新基线 |
| namecard | 名片 H5/预览 | `/card/preview` | profile/pet_card_page | DEV_ROUTE(种子) | 待重新基线 |
| archive-confirm | 存入档案确认 | 红/黄态存档 | triageRedArchiveHandler(待核实 UI) | 弹窗(点存档) | 待重新基线 |

## 个人中心 / 设置（3）

| HTML | 屏 | 路由/入口 | Flutter 文件 | 可达方式 | 保真状态 |
|---|---|---|---|---|---|
| profil | 我的 tab | `/me` | me/me_page | DEV_ROUTE(种子) | 已改·待重判 |
| settings | 设置页 | `/me/settings` | me/settings_page(待核实名) | DEV_ROUTE(种子) | 待重新基线 |
| delete-account | 注销整页 | settings 入口 | 待核实(arb 有串,页待核实) | 交互(设置→注销) | 待重新基线 |

## 通知（3）

| HTML | 屏 | 路由/入口 | Flutter 文件 | 可达方式 | 保真状态 |
|---|---|---|---|---|---|
| notif | 通知中心 | `/notifications` | notify/notification_center_page | DEV_ROUTE(种子) | 待重新基线 |
| notif-empty | 通知空态 | `/notifications` 空 | 同上 空分支 | 态(空 mock) | 待重新基线 |
| notif-gate | 通知权限引导 | 触发态 | 待核实 | 弹窗/态 | 待重新基线 |

## 错误态（1）

| HTML | 屏 | 路由/入口 | Flutter 文件 | 可达方式 | 保真状态 |
|---|---|---|---|---|---|
| network-error | 网络错误 | 全局错误态 | 待核实(通用组件?) | 态(断网) | 待重新基线 |

## 兽医端（9）

| HTML | 屏 | 路由/入口 | Flutter 文件 | 可达方式 | 保真状态 |
|---|---|---|---|---|---|
| vet-login | 兽医登录 | `/vet/login` | vet/vet_login_page | DEV_ROUTE | 待重新基线 |
| vet-dashboard | 兽医工作台 | `/vet/workbench`(tab) | vet/vet_workbench_shell | DEV_VET+DEV_ROUTE | 待重新基线 |
| vet-queue | 待接单队列 | workbench tab | vet 队列页(待核实) | DEV_VET(切 tab) | 待重新基线 |
| vet-case | 接诊详情 | `/vet/request/:id` | vet/vet_request_detail_page | DEV_VET+DEV_ROUTE | 待重新基线 |
| vet-chat | 兽医会话 | `/vet/conversation/:id` | vet/vet_conversation_page | DEV_VET+DEV_ROUTE | 待重新基线 |
| vet-final-diagnosis | 最终诊断 | 会话内提交 | 待核实(sheet/页) | 交互(发诊断) | 待重新基线 |
| vet-history | 接诊历史 | workbench tab | vet/vet_history_page | DEV_VET(切 tab) | 待重新基线 |
| vet-profile | 兽医资料 | workbench tab | vet/vet_me_page | DEV_VET(切 tab) | 待重新基线 |
| vet-status-popup | 兽医状态弹窗 | 工作台触发 | 待核实(弹窗) | 弹窗 | 待重新基线 |

---

## 汇总与缺口

- **52 屏待重新基线**（index 跳过）。
- **DEV_ROUTE 直达可截**（最易）：约 20 屏（splash/login/onboard/nickname/pet-select/pet-create/pet-edit/feed/feed-guest/create/ai-upload/konsultasi/konsultasi-home/profile/milestone/namecard/profil/settings/notif/vet-login/vet-case/vet-chat 等）。
- **需种子态/切 tab**：兽医端 tab 屏（dashboard/queue/history/profile）需 DEV_VET + workbench 内切换——`DEV_ROUTE` 落到 shell 后还需点 tab（同样受 GUI 点击限制，需想办法：给 shell 加 dev initialTab 参数）。
- **弹窗/状态屏（最难，约 20 屏）**：login-gate/rate/milestone-sheet/milestone-unlock/archive-confirm/vet-status-popup（弹窗）、feed-empty/feed-error/timeline-empty/notif-empty（空/错态，需控 mock）、publish-reviewing/done/rejected（发布后态）、ai-result*/match-wait/chat（需流程态）、pet-success（建档完成）。这些要靠 **mock 状态注入 / dev 入口扩展** 才能稳定截到。
- **疑似未实现/待核实**：badge-gallery、delete-account（整页?）、notif-gate、network-error、final-diagnosis、若干 sheet —— 打分前逐个 `grep` 确认；未实现的直接进「待实现」而非「待重做」。

## Dev 可达性手段（已就绪）

直达任意屏的 dart-define 组合（debug + mock 下生效，release/test 恒空）：

| 手段 | 用法 | 解锁 |
|---|---|---|
| `DEV_ROUTE=/x` | 启动落该路由 | 所有有独立路由的屏 |
| `DEV_USER=true` | 种普通登录态(HAS_PET) | 登录态用户屏 |
| `DEV_VET=true` | 种兽医登录态 | 兽医端 |
| `DEV_VET_TAB=0..3` | 兽医工作台落指定 tab（**原已存在**） | vet-dashboard/queue/history/profile |
| `DEV_STATE=<s>` | mock 强制态（**本次新增**） | 见下 |

`DEV_STATE` 已支持：`feed-empty` `feed-error` `timeline-empty` `notif-empty` `consult-waiting`(match-wait 停 WAITING) `rate`(补弹评分) `triage-red/yellow/green`(强制分诊等级)。
样例（match-wait，已验证可截）：
```
flutter run -d <dev> --dart-define=DEV_USER=true \
  --dart-define=DEV_ROUTE=/consult/waiting/1 --dart-define=DEV_STATE=consult-waiting
```

仍需交互/暂未覆盖（后续按需补 DEV_STATE 或 dev 入口）：ai-result-red/green（需走 upload 提交，DEV_STATE 只定等级）、publish-reviewing/done/rejected、pet-success、各 sheet/popup（milestone-sheet/unlock、vet-status-popup、final-diagnosis、archive-confirm）、notif-gate、badge-gallery/delete-account（疑未实现，待 grep 确认）。

## 下一步（阶段 B/C）
1. 批量产出 `reference/*.png` ✅（52 张已出，full-content）。
2. 逐屏截 App actual（用上表手段，完整内容）→ rubric + 独立 reviewer 打分。串行 or 并行待定。
3. 仍不可达的屏：补对应 DEV_STATE / dev 入口，或人工交互截图。
