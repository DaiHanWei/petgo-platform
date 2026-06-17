# 用户侧重灾区保真还原 · 工作计划

> 2026-06-18 · 6 个 Explore 代理逐屏调研（原型 `pages/*.html` = 标准 × 当前 Flutter 实现 × 数据现实）。
> 范围：审计标 🟠 的用户侧重灾屏 6 个 + 决策 A 并入的 3 个 🟡 底栏 tab（成长档案/问诊 hub/我的）= **共 9 屏**。兽医端 6 屏已全部 1:1 还原完成（~92%）。
> 底栏 4 tab 全覆盖目标：① 首页(🟠 #6) ② 成长档案(🟡 #7) ③ 问诊 hub(🟡 #8) ④ 我的(🟡 #9) + ＋发布(🟠 #1)。
> 全局纪律：色走 `AppColors` token / 文案走 arb 双语 / **不引新依赖**（脉冲动画用 `AnimationController`+`CustomPainter`，不加 flutter_animate）/ 无数据不臆造 / 红态安全语义只升不降。

## 执行顺序与清单

| # | 屏 | 原型 | 当前文件 | 档次 | 工作量 | 决策 |
|---|---|---|---|---|---|---|
| 1 | 发布页 | create.html | `content/presentation/publish_compose_page.dart` | 🟠 | 中 | 默认（按原型扁平化） |
| 2 | 登录门控弹窗 | login-gate.html | `shared/widgets/login_hard_dialog.dart` | 🟠 | 中 | **需定**：第3按钮 |
| 3 | 咨询入口 | konsultasi-home.html | `consult/presentation/consult_entry_page.dart` | 🟠 | 中 | **需定**：在线人数 |
| 4 | 匹配等待 | match-wait.html | `consult/presentation/consult_waiting_page.dart` | 🟠 | 中大 | 默认（脉冲环自绘） |
| 5 | AI 结果红态 | ai-result-red.html | `triage/presentation/triage_red_result.dart` + `shared/widgets/red_alert_overlay.dart` | 🟠 | 中大 | **需定**：全屏沉浸 vs 现 modal |
| 6 | 登录态首页 (tab①) | feed.html | `content/presentation/home_page.dart` + `feed_view.dart` | 🟠 | 大 | ✅ 已定：推倒重做为原型 |
| 7 | 成长档案 (tab②) | paspor.html | `profile/presentation/growth_archive_page.dart` | 🟡 | 小 | 默认（FAB 渐变 + 健康事件红体系） |
| 8 | 问诊 hub (tab③) | konsultasi.html | `triage/presentation/triage_page.dart` | 🟡 | 中 | ✅ 已定：availability 聚合提示 |
| 9 | 我的 (tab④) | profil.html | `me/presentation/me_page.dart` | 🟡 | 中 | ✅ 已定：编辑 BottomSheet |

---

## 逐屏方案

### 1. 发布页（create.html）
**原型**：顶栏 Close 圆钮 + 居中标题 + 「Bagikan」扁平紫钮；类型 Chips（紫底圆角）；宠物胶囊；照片 3×3 网格（首格虚线 Tambah）；文本区 + 0/1000 计数；日期行（紫浅底 #F8F6FF）。
**当前**：发布按钮用 **Btn3d 立体**（审计「割裂」）；类型用 **_TabButton + boxShadow** 仿 3D（非 Chip）；图片**横向 ListView**；日期行白底。文本区已是真 TextField（正确）。
**数据**：类型枚举 `ContentType{daily,growthMoment,knowledge}`；宠物 `profileRepository.getMyProfile()`；发布 `POST /posts` + Idempotency-Key（不动）。
**改动**：① 发布钮 Btn3d→扁平紫 FilledButton；② 类型标签去 boxShadow 改 Chip（紫底圆角 9999）；③ 图片改 3 列网格（首格虚线 Tambah）；④ 日期行紫浅底。**纯呈现层，提交逻辑不动。**
**决策**：无（审计已定调，按原型扁平化）。

### 2. 登录门控弹窗（login-gate.html）
**原型**：居中定制 modal（深遮罩+blur）；紫浅底圆角 icon 区（lock+paw）；标题「Fitur ini perlu akun」；3 行利益文案；纵向 3 按钮：Google / 「Daftar Gratis」实心紫 / 「Lanjut lihat dulu」文字链。
**当前**：标准 `AlertDialog`（`login_hard_dialog.dart`）：1 行 message + Google FilledButton + 可选兽医链接 + 关闭。无 icon 区、无利益文案、无注册钮。触发：401 / auth_guard / 软浮层。
**数据/流程**：Google 登录 `authRepository.loginWithGoogle()`（V1「自动建号」=Google 即注册）；兽医链接跳 `/vet/login`；继续看保留 pendingAction。
**改动**：自定义 Dialog 重建（紫浅底 icon 区 + 标题 + 利益文案 + 纵向按钮 + 深遮罩）；保留 Google 登录 / 继续看 / 兽医链接逻辑与失败内联态。
**决策（需定）**：原型有独立「Daftar Gratis」钮，但 V1 无独立注册流程（Google 即建号）。→ **A. Daftar Gratis 也走 Google 登录（两钮同源，视觉对原型）** / B. 去掉 Daftar 钮（只 Google + 继续看）/ C. 接真实 /register（V1 无此页，成本高，不建议）。

### 3. 咨询入口（konsultasi-home.html）
**原型**：在线提示（绿脉冲点 + 「Dokter Hewan Tersedia」+「3 dokter online · 响应 45 detik」）；3 步流程卡（编号圆 1/2/3 紫渐变 + 副标题）；责任声明；CTA「Mulai Konsultasi →」紫底。
**当前**：`consult_entry_page.dart` 三态（进行中/在线/离线）走 `consultAvailabilityProvider`。在线态仅概率文本 + 开始钮，**缺**在线脉冲提示、**缺** 3 步流程卡、**缺**责任声明。
**数据**：`ConsultAvailability.vetOnline`(bool)，**无在线人数字段**。「3 dokter / 45 detik」原型示意值，无后端来源。
**改动**：在线态加绿脉冲提示 + 3 步流程卡（静态文案）+ 责任声明 + CTA 紫底；离线/进行中态保留。
**决策（需定）**：在线人数无数据 → **A. 省略具体数字，仅「Dokter tersedia」+ 绿脉冲（不造假）** / B. 后端补 `onlineCount` 字段（超出保真范围，需后端 story）。建议 A。

### 4. 匹配等待（match-wait.html）
**原型**：3 层脉冲环（matchPulse，scale 1→1.9 交错延迟）+ 中心呼吸医生头像（breathGlow）+ 倒计时 00:47 + 症状摘要折叠卡 + 取消钮（二次确认）。
**当前**：`consult_waiting_page.dart` 用系统 `CircularProgressIndicator` 代脉冲环；轮询 3s（WAITING→继续，IN_PROGRESS→跳会话，超时→BottomSheet 继续等/转 AI）；取消二次确认 ✓。**缺**脉冲环/呼吸/倒计时/症状卡。
**数据**：`ConsultSession.waitingElapsedSeconds` 有；轮询/超时/取消逻辑完善（不动）。
**改动**：自绘 3 层脉冲环（`AnimationController`+`CustomPainter`，**不加三方库**）+ 中心呼吸医生 emoji 头像 + 倒计时（本地 Timer 逐秒，基于 elapsed）+ 症状摘要卡（若有数据）+ 取消钮。**轮询/状态转移逻辑零改。**
**决策**：无（脉冲自绘、emoji 头像、不引依赖——按既定纪律默认）。

### 5. AI 结果红态（ai-result-red.html）
**原型**：全屏红 #F0425A 沉浸 + breathGlow 呼吸；居中 🚨 88px 脉冲；三行主文案；下半屏白底浮起；症状摘要（红浅底）；「LANGKAH SEKARANG」3 步骤（圆数字 badge 1/2/3）；CTA「Saya Mengerti (5)」5s 倒计时锁。
**当前**：`TriageResultView`→`TriageRedResult`→`RedAlertOverlay`（自底滑起 modal，不可拖拽/关闭）；有 5s 倒计时锁 + 单出口「我已知晓」+ 底部摘要。**缺**全屏红/呼吸/居中脉冲符号/3 步骤 badge。
**数据**：`dangerLevel:"RED"`；`TriageResult.advice`（经 `TriageWordingGuard.sanitize()` 护栏）；宠物名。
**改动**：把红态 overlay 改为**全屏红沉浸**（breathGlow 呼吸 + 居中 🚨 脉冲 + 三行主文案 + 下半屏白卡浮起 + 症状红浅底 + 3 步骤圆 badge + 5s 倒计时锁 CTA）。**安全语义只升不降**：保留倒计时锁、不可轻易关闭、文案走 sanitize 护栏。
**决策（需定）**：**A. 改全屏沉浸（按原型，保留安全锁）** / B. 保留现 modal 仅补 3 步骤 + 呼吸。建议 A（原型即安全意图——强冲击）。

### 6. 登录态首页（feed.html）——⚠️ 关键决策
**原型**：标准社区设计——AppBar（「TailTopia 🐾」+ 铃）+ 分类 Chips（Semua/🐱Kucing/🐶Anjing/🐰Lainnya）+ 2 列瀑布流 Feed 卡（头像+作者+时戳+彩徽章+正文+图）。
**当前**：另一套设计——固定 Momo 吉祥物问候头 + 4 快捷卡（Konsultasi Kilat/Gabung Gath/Paspor/每日记录提示）+「Untukmu」区头 + 分类 Tab + 瀑布流。`home_page.dart`/`feed_view.dart`/`feed_tab_row.dart`。
**数据**：Feed `feedRepository`（`/feed`）；分类 `feedCategoryProvider`（ALL/DAILY/GROWTH_MOMENT/KNOWLEDGE）；宠物状态变更自动刷新（FR-21）。
**决策（关键，需定）**：两套设计冲突，不能擅自推倒。
- **A. 保留 Momo 方案**（承认是优化后的产品设计，品牌感+导航更强），仅卡片/Chip 细节对齐原型紫主题。
- **B. 推倒重做**为原型 AppBar+Chips+Feed（工期大、丢快捷导航/每日提示）。
- **C. 混合**：保留 Momo 问候头 + 改用原型分类 Chips 样式，拆每日提示/部分快捷卡。

---

### 7. 成长档案 tab②（paspor.html）
**原型**：宠物名片 petcard（头像+名+品种·年龄+bio）+ 统计行（快乐时刻/问诊双柱，紫数字）+ 里程碑进度条 msbar（紫填充）+ 时间线 tentry（左日期+emoji 圆标+卡）+ 健康事件 hentry（**红浅底 #FDE7EB + 红文字 #C4263C + 红等级徽章**）+ 分享 FAB（**135° 紫渐变 #9E83DA→#845EC9→#6C48AE**）。
**当前**：`growth_archive_page.dart` 保真度已 ~90%（PetInfoCard/_StatsBar/_MilestoneBar/HappyMomentTile/HealthEventTile/ShareFab 齐全，进度条/时间线分色/编辑钮/首条🌟标 均对）。仅 2 处差：① FAB 单色 mint 无渐变；② 健康事件用紫浅底 skyTint 而非原型红体系。
**数据**：宠物 `petProfileProvider`、统计 `archiveStatsProvider`、时间线 `timelineFirstPageProvider`、里程碑 `milestoneListProvider`（真后端，不动）。
**改动**：① ShareFab 单色→3 段紫渐变；② HealthEventTile 背景→红浅底 + 标题/文字红 + 红等级徽章（如缺 token 则加 `healthEventTint`/沿用 coralTint+triageRed）。**纯呈现，数据/分享/里程碑逻辑零改。**
**决策**：无（小改，按原型补渐变 + 红体系）。

### 8. 问诊 hub tab③（konsultasi.html）
**原型**：Momo 头部（"Bantuan Cepat…🐾" + 免费副标）+ **AI 卡（135° 紫渐变 #845EC9→#9E83DA + ⚡ + 白字）** + **兽医卡（白底 + #E6E6E6 边框 + 🩺 + 绿点 + 营业时段）** + 在线兽医条（3 医生行：头像/绿点/名/专长/Online·Sibuk）+ 历史列表（黄 badge + 相对时间）。
**当前**：`triage_page.dart` 用通用 `_EntryCard` tone 区分（AI 卡单色 mintTint 无渐变、emoji 🤖 而非 ⚡；兽医卡无边框）；在线兽医条**写死 3 名医生**（drh. Sari/Bayu/Indah，无 API）、缺右箭头；活跃会话卡/历史列表数据完整（`repo.active()`/`repo.history()`）。
**数据**：AI 入口=本地化+路由 `/triage/upload`；兽医可用性 `ConsultRepository.availability()`（在线 bool）；**无真实医生库 API**——在线兽医条 3 医生纯 mock 示意。
**改动**：① AI 卡补 135° 紫渐变 + ⚡ + 白字；② 兽医卡补 #E6E6E6 边框 + 🩺 + 绿点 + 营业时段；③ 卡片补右箭头 CTA；④ 在线兽医条按决策处理（见下）。
**决策（需定）**：在线兽医条无真实医生 API。→ **A. 去掉具名 mock 医生，改为 availability bool 驱动的「Dokter tersedia · 绿脉冲」聚合提示（不造假医生名，同咨询入口决策一致）** / B. 保留 3 名 mock 示意医生（视觉最贴原型，但是假数据）。建议 A。

### 9. 我的 tab④（profil.html）
**原型**：用户头区 profhead（头像 62px + 昵称/邮箱 + Edit 胶囊 + Help/Settings 图标）+ 宠物 mini 卡 petmini（头像 + 名 + **「Kucing · 3 tahun · 12 momen」元数据** + Lihat →）+ 「Postinganku」分组 + **发帖 2×2 网格**（缩略图 + 类型 badge：Momen/Tips/Cerita）。
**当前**：`me_page.dart` 用户卡（头像+昵称+邮箱+Edit）✓、AppBar Help/Settings ✓；宠物卡**仅名字 + 最近快乐首图，缺元数据**；我的发帖**横向单行 scroll**（非 2×2 网格）、**无类型 badge**；编辑资料用 **AlertDialog 仅昵称**（非原型 BottomSheet）。
**数据**：用户 `authControllerProvider`（nickname/email/avatarUrl）；宠物 `petProfileProvider`（有 name/avatar，species/birthday 未渲染）；发帖 `myPostsProvider`（id/type/text/firstImageUrl，`GET /me/posts`）；momen 数可复用 `archiveStatsProvider` 快乐计数。
**改动**：① 宠物 mini 卡补元数据（种类 + 年龄(由 birthday 算) + momen 数）；② 我的发帖改 2×2 网格（GridView，含类型 badge：GROWTH_MOMENT→绿/KNOWLEDGE→黄/DAILY→紫）；③ 编辑资料按决策处理（见下）。设置页/注销不在本 tab 范围（另算）。
**决策（需定）**：编辑资料 → **A. 升级为原型 BottomSheet（头像上传 + 昵称 + 邮箱只读）** / B. 保留现昵称 AlertDialog（轻量，头像上传走媒体流成本较高）。建议先做 A 的视觉 sheet（昵称+邮箱只读），头像上传若触媒体流复杂则降级标注。

## 不在本批次（单列后续）
- **里程碑抽屉 / 徽章图鉴 / 名片 H5**（P-33b/34/36，🟠 待验证）：需先逐屏核验实际实现，独立批次。
- **注销整页**（P-43，🟠）：与 story 7.3「立即删除」的 30 天冷静期冲突（⑥ deferred），仅可补部分视觉，需先解冲突。
- **登录页**（P-05）：已 ②还原完成，不在范围。

## 决策锁定（2026-06-18 Dai）
1. **登录态首页 → 推倒重做为原型**（AppBar「TailTopia 🐾」+ 铃 + 分类 Chips + 2 列瀑布流 Feed；移除 Momo 问候头/4 快捷卡/每日提示）。
2. **AI 红态 → 全屏沉浸**（保留 5s 倒计时锁与不可轻关安全语义）。
3. **咨询入口在线人数 → 省略数字**，仅绿脉冲 +「Dokter tersedia」。
4. **门控弹窗第 3 钮 → Daftar Gratis 也走 Google**（与 Google 同源，视觉对原型）。

### 扩充的 🟡 tab 批次（已锁定 2026-06-18 Dai）
5. **问诊 hub 在线兽医条**（#8）→ **A：去具名 mock 医生，改 `ConsultRepository.availability()` 的在线 bool 驱动「Dokter tersedia · 绿脉冲」聚合提示**（不造假医生名，与咨询入口 #3 决策一致）。
6. **我的·编辑资料**（#9）→ **A：升级为原型 BottomSheet（昵称可编辑 + 邮箱只读）**；头像上传若触媒体流复杂则本期降级（保留现有头像展示，sheet 内标注待接入），不阻塞 sheet 还原。
> #7 成长档案为小改（FAB 渐变 + 健康事件红体系），无需决策。

## 全局执行约定
- 每屏：spec(quick-dev) → 实现 → `flutter analyze`+相关测试绿 → 模拟器截图验收 → 提交。
- 复用兽医端已验证模式：dev 深链直达验收、token 驱动、双语 arb、状态机/提交逻辑零破坏。
- 决策锁定后批量执行。建议顺序：先小改快赢（#7 成长档案）→ 中等（#1 发布、#9 我的、#8 问诊 hub、#2 门控、#3 咨询入口）→ 大/动画（#4 匹配等待、#5 AI红态）→ 最后 #6 首页推倒重做（决策与工作量最重，独立验收）。
