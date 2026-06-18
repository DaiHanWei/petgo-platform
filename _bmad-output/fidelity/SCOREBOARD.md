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
