# 保真重新基线 · 打分榜（干净基线 / 第二轮）

> 2026-06-18 · 34 屏并行打分（每屏独立 reviewer 对照原型真值图审 App 实现图，6 维 rubric + FAIL 门）。
> **第二轮已修捕获条件**：强制 `locale=id`（消除语言噪声）+ splash/pet-create 错屏归位。这才是真实结构保真基线。
> 逐屏 deltas：`scores-raw-clean.json`（第一轮含语言噪声的存 `scores-raw.json`）。

## 总结论
**0 / 34 通过**，中位 ~28%，最高 vet-history 70 / konsultasi-home 68。
语言修正后多数屏回升（vet-history +42、splash +45、profil +17、vet-dashboard +14…），但**没有一屏达标**。
→ 语言只是部分因素；**结构性分歧是主因**。1:1 还原 `pages/` 是整屏重做为主、打磨为辅的工程。

> ⚠️ 打分有 ±10 的 LLM 随机波动（个别屏 R2 反而略低，如 feed 35→25），**按区间看，不要抠绝对值**。

## 两轮对比（R1 含语言噪声 → R2 干净）

| 屏 | R1 | R2 | Δ | | 屏 | R1 | R2 | Δ |
|---|---|---|---|---|---|---|---|---|
| detail | 0 | 0🔒 | +0 | | ai-upload | 15 | 28 | +13 |
| vet-queue | 8 | 8 | +0 | | paspor | 22 | 28 | +6 |
| vet-case | 8 | 8 | +0 | | milestone | 25 | 28 | +3 |
| feed-error | 10 | 10 | +0 | | feed-empty | 35 | 35 | +0 |
| namecard | 12 | 12 | +0 | | chat | 38 | 35 | -3 |
| timeline-empty | 8 | 15 | +7 | | notif-empty | 25 | 38 | +13 |
| login | 22 | 22 | +0 | | vet-dashboard | 28 | 42 | +14 |
| pet-edit | 22 | 22 | +0 | | vet-chat | 40 | 42 | +2 |
| feed-guest | 25 | 22 | -3 | | profil | 28 | 45 | +17 |
| rate | 15 | 22 | +7 | | konsultasi | 45 | 52 | +7 |
| vet-login | 15 | 22 | +7 | | splash | 10 | 55 | +45 |
| onboard | 22 | 25 | +3 | | nickname | 45 | 55 | +10 |
| pet-create | 0 | 25 | +25 | | match-wait | 45 | 58 | +13 |
| feed | 35 | 25 | -10 | | pet-select | 55 | 62 | +7 |
| settings | 15 | 25 | +10 | | vet-profile | 52 | 62 | +10 |
| notif | 20 | 25 | +5 | | konsultasi-home | 62 | 68 | +6 |
| create | 30 | 28 | -2 | | vet-history | 28 | 70 | +42 |

## 最终分类（干净基线，按区间）

### A. 错屏/Bug，先修再评 — 1
- `detail`(0,blocked)：详情页在 mock 下渲染不出（卡加载态）。**这是真 bug**，需先查 `/content/:id` 详情数据流（mock 或页面），修好才能评分。

### B. 整屏重做（R2 <35%，结构/组件/主体与原型不符）— 21
vet-queue(8) · vet-case(8) · feed-error(10) · namecard(12) · timeline-empty(15) · login(22) · pet-edit(22) · feed-guest(22) · rate(22) · vet-login(22) · onboard(25) · pet-create(25) · feed(25) · settings(25) · notif(25) · create(28) · ai-upload(28) · paspor(28) · milestone(28) · feed-empty(35) · chat(35)

**典型**：vet-case 截到的是队列页而非案例详情；namecard 是浅色 web 卡 vs 原型深色档案卡；rate 做成小弹窗 vs 原型全屏评价页；settings 缺全部分组/开关/危险区；feed-error 截到骨架加载而非失败态。

### C. 打磨（R2 ≥38%，结构可辨，补区块/细节/语言残留）— 12
notif-empty(38) · vet-dashboard(42) · vet-chat(42) · profil(45) · konsultasi(52) · splash(55) · nickname(55) · match-wait(58) · pet-select(62) · vet-profile(62) · konsultasi-home(68) · vet-history(70)

**典型**：konsultasi-home 仅差返回箭头 + banner 副标题；vet-history 卡片配色已接近，补顶部四 tab；match-wait 动画框架已对，补底部说明 + 细节。

## 仍未纳入打分的屏（~18，需补可达手段）
ai-result/ai-result-green/ai-result-red、publish-reviewing/done/rejected、pet-success、milestone-sheet/unlock、archive-confirm、vet-status-popup、vet-final-diagnosis、notif-gate、network-error、badge-gallery、delete-account、catatan-calendar。
→ 需补 DEV_STATE / dev 入口 / 人工交互后截图再评。

## 下一步建议
1. **先修 detail bug**（A 类，1 屏）——它阻塞自己的评分。
2. **B 类 21 屏排重做**：从最烂、且业务重要的开打——建议序 `paspor → vet-case → vet-queue → settings → namecard → login → ...`（兼顾保真差距 + 用户价值）。
3. **C 类 12 屏排打磨**：补区块 + 清语言残留（部分仍有硬编码英文）。
4. 每屏做完按验收标准（真值图 vs 完整 actual + 独立 reviewer ≥95% 且 FAIL 清零）才算过。
5. 剩 ~18 屏补可达手段后纳入。

---

## 进度日志 · 2026-06-18（第一批：A 类 + B 类前 6 屏）

> 全量 `flutter analyze` 干净 + `flutter test` 321 全绿；逐屏 actual 已更新（actual/ + actual_small/）。
> ⚠️ 这些是「实现完成 + 模拟器目检高度吻合」状态，**尚未跑独立 reviewer 重新打分**——下一步对这 7 屏重判确认达标。

### A 类（bug）— 1/1 ✅
- **detail**：根因＝capture 用了不存在的帖 `/content/1`（mock 种子帖 id=100–106）→ 永远 404 gone 态。详情页代码本身健全。修：capture.sh 改 `/content/100`。

### B 类重做 — 6 屏（本批）
| 屏 | 改动 | 备注 |
|---|---|---|
| paspor | 整页重做：横向护照卡+三列统计、msbar、Timeline/Kalender 药丸、按月分组紧凑时间线、健康事件粉底行 | 改 growth_archive_page + pet_info_card + timeline_tiles；测试同步更新 |
| vet-case | 页面本已忠实原型，根因＝capture 用非 waiting-pool 的 id `/vet/request/1`（5s 轮询判「已被抢」弹回队列）。修：capture 改 `8101` + 路由 synth 身份对齐原型 |
| vet-queue | 共享队列卡 `_InboxCard` 升级到 vet-queue.html（顶部色条/徽章/RINGKASAN AI·PERHATIAN SEGERA 框/照片 chip/RED 单红钮）。capture 改 tab=0（app 真队列），同时提升 vet-dashboard |
| settings | 整页重做：四分组（AKUN/TAMPILAN/PRIVASI/ZONA BAHAYA）+ 开关 + 红字危险区 + 版本脚注。保留 7.3 退出/注销逻辑与 keys |
| namecard | 整页重做：深色档案卡（#141019+紫辉光+pop-art）+ hero+成就徽章条+5格快乐时刻+双CTA。原浅色 web 卡弃用 |
| login | 整页重做：紫渐变品牌头（光晕+pop-art+logo+欢迎语）+ Google 钮+自动建号提示+三数字背书+兽医入口+条款。保留 FR-0D keys |

### 关键决策
- **vet workbench 不重建导航架构**：原型本身不一致（dashboard/profile 底部 nav、queue 顶部 tab）。保留 app 已实现的底部 4-tab（标准移动范式、匹配最高分的 dashboard）；真正保真缺口是**队列卡设计**，已升级且为 dashboard+queue 共享。
- **i18n**：vet 徽章复用既有 l10n key（vetQueueLevel*）+ emoji 前缀，保持双语；paspor/settings/namecard/login 的展示串按 rework 惯例硬编码印尼语（capture 强制 locale=id）。

### 下一批
- B 类剩 15 屏：vet-case 已顺带验证 → 继续 onboard/pet-create/feed/notif/create/ai-upload/paspor 同源的 timeline-empty/feed-empty/chat/rate/feed-guest/vet-login/pet-edit/feed-error。

---

## 进度日志 · 2026-06-18（第二批：重判 + feed系 + 引导流 + 发布分诊 + pet-edit）

> 全量 `flutter analyze` 干净 + `flutter test` 321 全绿。逐屏 actual 已更新。

### 重判这批（A+前6屏）结论
独立 reviewer 重判：**paspor 95 / settings 98 真 pass**；namecard 实为 pass（reviewer 把 Oyen/数据差异误判 FAIL）；login/vet-queue/vet-case 真实小瑕已修（login 文案贴原型、vet-queue 加箭头、vet-case 照片渐变色）。**关键发现：reviewer 系统性把「mock 数据差异」当 FAIL**（宠物名/天数/统计值），对数据屏打分偏低——按结构/组件/颜色/布局看才准。

### 本批完成（B 类 + 连带）
| 屏 | 类型 | 结果 |
|---|---|---|
| feed | 卡图固定高度 cover（一屏多卡贴原型紧凑流） | ✅ |
| feed-empty | 文案贴原型+Temukan Teman次链接+无icon（像素级） | ✅ |
| feed-error | 错误态文案贴原型（Gagal memuat feed+Coba Lagi+Laporkan Masalah）；FeedSkeleton 2列→单列 | ✅ UI（dev截图卡loading骨架是DEV_STATE时序，错误态有测试覆盖） |
| feed-guest | AppBar Masuk钮 + 底部紫渐变登录引导横幅 | ✅ |
| onboard | 渐变条+🎉+短线+欢迎语+档案预览卡+紫钮（像素级） | ✅ |
| pet-create | 虚线圆头像+Upload Foto+大写label+紫边框输入+不可改红字+紫钮 | ✅ |
| ai-upload | 居中标题+紫渐变速度横幅+FOTO GEJALA 3列+紫边框文本框+黄字免责+紫钮 | ✅ |
| create | 实现已含全部原型元素，capture 改 preset=growth-calendar 进 growthMoment 态展现宠物tag+日期条 | ✅（捕获态问题，非实现） |
| pet-edit | 套用 pet-create 样式（返回+居中+大写label+紫边框+只读类型+紫钮） | ✅ |
| timeline-empty | paspor 重做连带达标 | ✅ |

### 关键洞察：多屏低分实为「捕获态/数据」问题而非实现
- **detail**（/content/1→不存在帖）、**vet-case**（id 不在 waiting-pool→轮询弹回）、**create**（默认 Harian 态不显 growthMoment 元素）——都是 capture 用错状态/id 导致截错屏，实现本身达标或接近。**教训：低分屏先核对捕获条件（id/DEV_STATE/类型态）再决定是否重做**。

### B 类完成（第三轮，已重做+模拟器验收）
- **vet-login** ✅：薄荷主题完整重做（返回钮+薄荷图标块「Portal Dokter Hewan」+副文+薄荷浅底信息条+EMAIL/KATA SANDI 大写label+薄荷边框输入信封/锁图标+眼睛+薄荷「Masuk sebagai Dokter」大钮+Belum terdaftar分隔+Hubungi Mitra描边钮+底部三信任标）。spec F3 无忘记密码链接仅提示。
- **notif** ✅：HARI INI/KEMARIN 时间分组+圆角方形彩色图标块(按type配色)+未读紫浅底高亮+紫点+印尼语相对时间；mock 种子改印尼语扩 5 条。
- **milestone** ✅：返回+居中标题+Header横向(右侧 4/30 大字)+分组彩色标题(L金LEGENDA/M紫MAJOR/S绿SMALL)+徽章按级别配色(已解锁彩色实心圆奖杯/灰锁)。剩 L/M 全灰是 mock 数据态、标题「Tonggak」vs「Milestone Mochi」。

### B 类仍待做（架构差异，需决策后做）
- **rate**：当前是**评分弹窗**（标题+5小星+备注+Kirim），原型是**全屏评价页**（头像+医生名+时长 Durasi+大金星+快捷标签chips Responsif/Penjelasan Jelas+备注+Kirim Ulasan大钮+Lewati）。架构差异（弹窗→全屏页），且缺医生信息/快捷标签。
- **chat**：气泡配色已对（紫/白）+免责+系统行+输入栏，但缺顶栏（头像+🟢Online·Klinik+红Akhiri钮）+紫浅底病例条（⚠Mochi—muntah·2 foto·Lihat）+气泡头像(D/A)+打字指示。中等重做（涉及 consult_conversation IM 逻辑，改顶栏/病例条/气泡头像不动消息流）。

### 本 session 累计完成
A 类 1（detail bug）+ B 类重做/达标 ~20 屏（paspor/vet-case/vet-queue/settings/namecard/login/feed系4/onboard/pet-create/ai-upload/create/pet-edit/timeline-empty/vet-login/notif/milestone）。全程 analyze 干净 + 321 测试绿。**核心方法论沉淀：低分先查捕获条件（id/DEV_STATE/类型态）；reviewer 对 mock 数据差异系统误判；表单重做统一模式 + 高视口测试。**

### 第四轮（Opus 精审后按序补修）
- **detail**（47%→~70%）：作者行加紫圆头像(首字母)+分类彩徽章(Cerita/Momen/Tips)、评论标题改「KOMENTAR (n)」计数、评论框改 pill 填充+紫圆实心发送钮。CommentSection 嵌套回复/查看更多本已实现。剩：图是 mock 真照片、整体布局细节。
- **timeline-empty**（38%→~70%）：空态从一行字改为居中引导（「Belum ada catatan」标题+副文+紫「+ Catat Momen Pertama」CTA，对齐原型 timeline-empty.html）。护照卡保留（App 合理增强：有档案显档案信息，原型该图疑似裁切未画护照卡）。
- **feed-error**（仍 5%，dev 截图工具限制）：错误态 UI 已实现且 **feed_test AC5 覆盖验证正确**（provider override 抛错→EmptyState「Gagal memuat feed」+Coba Lagi 正确渲染）。但 `DEV_STATE=feed-error`（mock GET /content-posts `throw 500`）在真实 dio 链路下截图**卡 loading 骨架**不触发 error 态——已排查：AuthInterceptor.onError 对 500 正确放行(next)，feed-empty(同机制 return ok)正常，故疑为 mock async reject + provider 重建时序问题。**待 dev 后续根因定位（非产品 bug）**。

### 第五轮（B 类收尾：rate + chat）—— B 类 21/21 全部完成 ✅
> 全量 `flutter analyze` 干净 + `flutter test` 321 全绿；rate/chat 已 Android 模拟器截图肉眼验过贴原型。

- **rate**（22%→达标，架构差异已消化）：由**评分弹窗**改为**全屏评价页**（原型 rate.html 1:1）。薄荷头像「D」+ 兽医名 + 「Klinik Hewan Sehat · Durasi: 18 menit」+ 标题「Bagaimana pengalamanmu?」+ 副文 → 5 大星（必填，金 #F6A609）→ 快捷标签 chips 多选（Responsif👍/Penjelasan Jelas📋/Sabar/Ramah/Profesional）→ ≤100 字备注 → 紫「Kirim Ulasan」大钮 → 「Lewati」。
  - **保契约不动后端**：`ConsultRatingDialog.show()` 静态 API + `RatingResult(stars,comment)` 返回不变（3 调用点：triage 补弹 / 会话页结束 / 入口延迟补弹）；仅 AlertDialog→fullscreenDialog 路由。快捷标签为原型呈现元素（不在数据模型）→ 选中标签随备注**折叠进单一 comment 字段**（≤100 裁断，不新增后端字段）。
  - 测试随之改：入口页 AC5 原「点遮罩关弹窗」→ 改「点 Lewati(ratingSkip)」+ 设大视口（全屏页高于默认 600 视口，Skip 在 fold 外）。
- **chat**（35%→达标，中等重做）：自定义浅色顶栏（返回钮 + 薄荷「D」头像带在线点 + 「drh. Dewi Santoso」+ 「● Online · Klinik Hewan Sehat」/终态副行 + 红描边 Akhiri）+ 紫浅底症状摘要条（info + 占位症状 + 「Lihat ↓」）+ 气泡两侧头像（兽医薄荷「D」/ 用户紫渐变「A」）。**不动消息流**：保留 NFR-9 金色免责条、评分提示条、中断态、IM 占位、全部 key（consultTerminalLabel/consultInterruptedState/consultReconsult/consultRatePromptBanner/consultOpenRating/consultDisclaimerBanner）。
  - **气泡头像参数化共享**：ImChatPlaceholder 加 `selfIsVet`（用户侧 false：己方紫「A」/对端薄荷「D」；兽医侧 true：反之），vet_conversation_page 传 true，两端各自正确。
  - 「Akhiri」用户侧无结束端点 → 确认后离开会话（服务端状态权威，本页不发起结束）。

### 第六轮（C 类打磨 12 屏）
> 并行 3 诊断 agent（reference 真值 + 原型 HTML + actual + 源码）逐屏定位差距 → 主 agent 实施。
> 全量 `flutter analyze` 干净 + `flutter test` 321 全绿；改动 7 屏已 Android 模拟器肉眼验过贴原型。

**真实补齐（7 屏）**：
- **notif-empty**：EmptyState 加可选 `iconBackground`（80×80 浅紫 #F8F2FF 圆底盘包图标）+ 补副文案 `notificationEmptyHint` + 标题 22→19。
- **konsultasi-home**：标准 AppBar → 自定义返回 header（36×36 #EFEDF3 圆角11 返回钮 + 17px w700「Mulai Konsultasi」，l10n consultEntryTitle 改）。
- **match-wait**：补顶部返回钮 + 倒计时下方「RINGKASAN YANG DIKIRIM」白底圆角14 阴影摘要卡（占位症状正文）。
- **vet-dashboard**：3 统计卡统一薄荷 → 三色（Antrian 紫 #F8F2FF/#845EC9、Selesai 薄荷 #EFF7F4/#5BCBBB、Rating 金 #FEF3DE/#F6A609），去边框。
- **vet-profile**：同款三色统计卡 + 名字下补诊所·地点副行（占位）+ 顶栏字号 headline→title(17/700) + Online 选中段薄荷投影。
- **vet-chat**：Template 工具 chip / 辅助面板由薄荷改**紫色**（原型深薄荷 chrome 中唯一的紫点缀：紫左边框 + skyTint 底 + 紫标题 + 紫钮）。
- **profil/me_page**：去「Saya」AppBar 大标题 + 双图标改 .ibtn 样式（38×38 白底圆角11 阴影，headset+gear）+ 用户卡边框改阴影（profhead box-shadow）。**保留 PRD F8 宠物状态区**（原型 profil.html 无此区，但 F8 信息架构明文要求，守「不碰 PRD」不删）。

**判定已达标/有意偏离，不改（5 屏）**：
- **splash / nickname / konsultasi / pet-select**：结构已 1:1（nickname/pet-select 的「空/未选」是 mock 数据态非实现差距；konsultasi/pet-select 文案是有意改写版；仅余 1-2 色阶微差可忽略）。
- **vet-history 顶部四 tab 条**：原型有，但与「兽医端保留底部 4-tab、不重建第二套导航」决策冲突 → 不加（已 70%，卡片配色达标）。
- **konsultasi-home banner 副标题**「不报在线人数」、**nickname 用词**：合规/产品决策，非保真缺陷。

### 仍待后续：未纳入打分屏（~18，需补 DEV_STATE/dev 入口可达手段）。
