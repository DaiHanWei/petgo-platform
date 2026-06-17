# 原型保真度审计（pages/ 原型 = 唯一标准）

> 2026-06-17 · 6 组并行审计逐屏对比「原型 `pages/*.html`（标准）vs Flutter 实现」。
> 档次：🟢高(布局组件还原,仅色差) / 🟡中(骨架在但组件元素明显出入) / 🟠低(布局结构不同,两套设计) / ⚫缺失。
> **结论：整体保真度约 55%。最系统的问题是「主色全错」（原型紫 #845EC9 / Pop Art 红 #F0425A · 兽医端薄荷 #5BCBBB + 深顶栏 #2B2540 ↔ app 全用薄荷绿 #7FD1AE）。之前 6 条 quick-dev 流只做了 i18n + ① Splash/弹窗，未做任何视觉/布局还原。**

## 一、总览（按组平均保真度）

| 组 | 平均 | 重灾屏 |
|---|---|---|
| 认证引导 | ~60% | 🟠 登录页(缺渐变区/背书)、登录门控弹窗(标准 AlertDialog) |
| 社区内容 | ~57% | 🟠 首页(骨架完全不同)、发布页(Btn3d 立体风格割裂) |
| 分诊+兽医用户侧 | ~60% | 🟠 红态结果(全屏 overlay/步骤/脉冲缺)、等待页(脉冲环缺)、咨询入口(流程卡缺)；⚫ 存档确认缺 |
| 兽医端 | ~44% | 全员缺深色顶栏 #2B2540 + 薄荷 #5BCBBB；⚫ 诊断表单、状态弹窗缺 |
| 档案里程碑 | ~62% | 🟠 里程碑抽屉/徽章图鉴/名片(主色错+装饰缺) |
| 个人中心 | ~50% | 🟠 注销(整页→两层Dialog)、设置(4分组→1卡)、我的发帖(2x2网格→单行) |

## 二、跨屏系统性问题（改一处惠及全局）

1. **主色全错（最高优先）**：app 全局薄荷绿 `#7FD1AE` ↔ 原型用户侧紫 `#845EC9` + Pop Art 红 `#F0425A`、兽医端薄荷 `#5BCBBB` + 深顶栏 `#2B2540`。因 app 严格走 token，**核心是改 `core/theme/colors.dart` token 值**，业务屏不用逐个动——但 cream 底/Pop Art 红/各 tint/兽医端深色主题需重定义。
2. **Pop Art 装饰缺失**：原型大量「红影错位 +3px」(Splash 图标、徽章、头像)、脉冲环、浮动粒子、多层辉光——app 基本没实现。
3. **进度条缺失**：引导流 nickname/pet-select 原型有 1/2、2/2 步进度条，app 无。
4. **沉浸式顶部区缺失**：登录页紫渐变区+背书、AI 结果页三态渐变沉浸顶、兽医端深色顶栏——app 多用标准 AppBar 替代。
5. **标准 Material 组件替代定制**：登录门控/注销用标准 AlertDialog，丢失原型高度定制弹层；分类用下划线 Tab 替代 Chip。

## 三、逐屏未遵守项清单（按流）

### 认证引导流（~60%）
- **Splash 🟡**：背景 #141019→薄荷暗调；图标紫渐变→薄荷；缺 Pop Art 红错位层、6 浮动粒子、4 层辉光；脉冲环紫→薄荷
- **登录门控弹窗 🟠**：原型定制 modal(紫圆角 icon 区+利益文案+3 按钮:Google/注册/继续看)→ app 标准 AlertDialog(仅 Google+兽医链接)
- **登录页 🟠**：缺紫渐变顶部区、装饰光晕、Pop Art 块、Logo 容器、"自动建号"tip 卡、社区数字背书(10K+/50K+/100+)；骨架变纯白列表
- **软登录浮层 🟡**：紫渐变底→白底；横排双 CTA→纵向堆叠
- **昵称页 🟡**：缺 1/2 进度条、Google avatar+email 预显；标准 AppBar；输入框缺紫 focus 边框
- **宠物状态页 🟡**：缺 2/2 进度条；卡片选中态(紫边+checkmark+阴影)不全
- **建档引导 🟢**：仅配色薄荷(布局保留)
- **通知权限 sheet 🟡**：福利卡/icon/按钮 紫→薄荷
- **访客首页 🟢**：仅配色(功能布局一致)

### 社区内容流（~57%）
- **登录态首页 🟠**：原型=AppBar(TailTopia🐾)+铃铛+分类Chips+Feed；app=Momo问候头+4快捷卡(Konsultasi Kilat/Gabung Gath/Paspor/Catat momen)+每日提示+Untukmu区头+Tab+Feed——**两套设计**；Chip→下划线Tab
- **发布页 🟠**：发布按钮 紫扁平→Btn3d 立体(风格割裂)；类型 Chip→下划线；文字区 fake-ta 非真 input
- **内容详情 🟡**：返回/···改标准 Material；头像 38→16px；多图计数角标/点赞红心样式偏差；评论区头大写丢失
- **Feed 错误态 🟡**：原型自定义红警告圆 → app `Icons.cloud_off`(图形完全不同)
- **Feed 空态 🟡**：图标 🐾→add_circle；按钮 padding/圆角偏差
- **通知中心 🟡 / 通知空态 🟢**：最接近(标准 ListTile)
- **发布成功/审核中/被拒**：⚫ 多缺(②已记 deferred，与同步拦截模型冲突)

### AI分诊+兽医用户侧（~60%）
- **问诊 hub 🟡**：AI卡紫渐变/兽医卡白底边框→通用 _EntryCard tone 区分;在线兽医条无动态数据;缺右箭头
- **AI 上传 🟡**：3宫格→横向 ListView;缺紫渐变"15秒"信息条;输入框缺紫左边框+glow
- **AI 结果黄 🟡**：缺顶部黄渐变沉浸区+🟡居中;症状框配色;建议 bullet 黄点
- **AI 结果绿 🟢**：需验证按 level 切换绿色逻辑
- **AI 结果红 🟠**：原型全屏红呼吸 overlay+3步骤(圆数字badge)+breathGlow脉冲 → app 缺步骤列表/全屏沉浸/脉冲动画
- **咨询入口 🟠**：缺绿色在线提示(3医生+pulse)、3步流程卡(编号圆)
- **匹配等待 🟠**：缺 3 层脉冲环(matchPulse)+中心呼吸医生
- **问诊对话 🟡**：需验证医生头像#5BCBBB/气泡色;顶部症状摘要折叠条可能缺
- **评分 🟡**：基本对(细节边框)
- **存档确认 ⚫**：缺(④已记 deferred)

### 兽医端（~44% 最低）
- **全员**：缺深色顶栏 #2B2540；主色应薄荷 #5BCBBB 却用紫；Tab active 无 #5BCBBB
- **登录 🟠**：缺医生身份绿横幅、联系popup、安全徽章
- **工作台首页 🟠**：缺问候+在线切换顶栏、3统计卡网格、AI摘要队列卡(空态占位)
- **待接单 🟡**：缺三优先级色bar(黄/绿/红)、AI摘要框、Lewati/详情双按钮
- **案例详情 🟡**：缺AI黄框、宠物tags、图片缩略;倒计时逻辑✓
- **兽医对话 🟡**：缺工具栏(#1A2B28)+FR-5快捷(模板/药物/历史/紧急)、辅助提示区
- **历史 🟢**：最高;缺筛选 Chip
- **兽医我的 🟡**：缺3统计卡、在线3态选择
- **诊断表单 ⚫**：无实现(无后端契约,④已记 deferred)
- **状态弹窗 ⚫**：简化为 toggle

### 档案里程碑流（~62%）
- **成长档案 🟡**：里程碑进度条 紫→薄荷;FAB渐变→单色;时间线缩略背景未按类型分色;健康事件红背景缺;编辑按钮缺
- **日历 🟡**：日期3状态区分/当天双层圈/过期灰化/图例 缺
- **时间线空态 🟢 / 庆祝页 🟢**：最接近(庆祝页深底+Pop Art块在;纸屑动画待验)
- **建档创建 🟡**：缺头像dashed边框/必填红*/BIO字数计数/焦点边框
- **编辑档案 🟡**：缺头像Pop Art红影偏移/编辑按钮/锁定字段红标/删除按钮/顶部保存
- **里程碑列表 🟡**：L/M/S级标题色彩层级(金#F6A609等)缺;网格对齐
- **里程碑抽屉 🟠 / 徽章图鉴 🟠 / 名片H5 🟠**：代码未充分验证;主色错+Pop Art装饰缺
- **解锁动效 🟡**：阴影偏移/纸屑/CTA待逐项验

### 个人中心（~50%）
- **我的页 🟡**：宠物mini卡缺元数据(种类/年龄/momen);我的发帖 2x2网格→单行scroll;缺badge标签;缺编辑资料BottomSheet(仅昵称AlertDialog)
- **设置页 🟡**：原型4分组(AKUN/TAMPILAN/PRIVASI/ZONA BAHAYA)→app 1卡;缺深色模式开关、档案公开开关、Notifikasi/Bahasa项、版本号;多出兽医登录
- **注销 🟠**：原型整页(警告icon+5条删除清单+30天冷静期卡+邮箱输入红边框)→app 两层 AlertDialog(短语确认);信息密度差距大。⚠️注:30天冷静期与 story 7.3「立即删除」冲突(⑥已记 deferred),其余视觉项可补

## 四、改进建议（优先级）

- **P0 全局换肤**：改 `core/theme/colors.dart` 把 mint 体系→原型紫 #845EC9 + Pop Art 红 + cream/白底重定义；兽医端单独深色顶栏 #2B2540 + 薄荷 #5BCBBB 主题。一处改、全屏受益。
- **P1 高频屏布局还原**：登录态首页(回到 AppBar+Chips+Feed 或确认保留 Momo 方案)、登录页沉浸顶+背书、兽医端深色顶栏+统计卡/队列卡。
- **P2 装饰系统**：Pop Art 红影错位组件、脉冲环组件(splash/match-wait/red-alert 复用)、进度条(引导流)。
- **P3 缺页/缺态**：AI 红态步骤列表、注销整页(去掉冲突的冷静期、保留清单+邮箱)、设置分组、徽章图鉴/名片逐项核验。

> 注：审计中部分屏(里程碑抽屉/图鉴/名片/兽医对话气泡)子代理未充分读到代码细节，标注「待验证」，改进前需再逐屏确认实际实现。

## 五、路由 × 原型 × 审计 全量对照（覆盖盲区核验）

> 2026-06-17 追加。以 `app_router` 真实路由表 × `pages/` 原型 53 屏（权威编号见 `core-pages-reference-解读.md`）× 本审计逐条交叉比对，确认逐屏计划无盲区。
> 计划状态：✅在逐屏还原计划 / ⚫缺页且已 deferred(暂不做) / ⚠️无原型标准或审计未覆盖(需先定) / 🛠️调试页(故意排除)。

### 认证 & 引导
| 原型 | html | app 路由/触发 | 档次 | 状态 |
|---|---|---|---|---|
| P-01 | splash.html | `/splash` | 🟡 | ✅(①已做) |
| P-02 | feed-guest.html | `/home`(游客) | 🟢 | ✅ |
| P-03 | (P-02 内浮层) | 软登录浮层 | 🟡 | ✅ |
| P-04 | login-gate.html | 登录门控弹窗(全局) | 🟠 | ✅ |
| P-05 | login.html | `/login` | 🟠 | ✅(②已做) |
| P-06 | nickname.html | `/onboarding/nickname` | 🟡 | ✅(③已做) |
| P-07 | pet-select.html | `/onboarding/pet-status` | 🟡 | ✅(④已做) |
| P-08 | onboard.html | `/onboarding/profile` | 🟢 | ✅ |
| P-09 | notif-gate.html | 通知权限 sheet | 🟡 | ✅ |

### 核心 Tab（底部 4 tab + ＋发布）
| 原型 | html | app 路由 | 档次 | 状态 |
|---|---|---|---|---|
| P-10 | feed.html | `/home`(登录态) **tab1** | 🟠 | ✅(两套设计,需重做) |
| P-27 | paspor.html | `/profile` 成长档案 **tab2** | 🟡 | ✅ |
| P-17 | konsultasi.html | `/triage` 问诊 **tab3** | 🟡 | ✅ |
| P-40 | profil.html | `/me` 我的 **tab4** | 🟡 | ✅ |
| P-38 | create.html | `/publish` ＋发布 | 🟠 | ✅ |

### AI 分诊
| 原型 | html | app 路由 | 档次 | 状态 |
|---|---|---|---|---|
| P-18 | ai-upload.html | `/triage/upload` | 🟡 | ✅ |
| P-19 | ai-result.html | 结果黄态 | 🟡 | ✅ |
| P-19b | ai-result-green.html | 结果绿态 | 🟢 | ✅(顺验 level 切换) |
| P-19c | ai-result-red.html | 结果红态 | 🟠 | ✅ |

### 兽医问诊（用户视角）
| 原型 | html | app 路由 | 档次 | 状态 |
|---|---|---|---|---|
| P-20 | konsultasi-home.html | `/consult` 咨询入口 | 🟠 | ✅ |
| P-21 | match-wait.html | `/consult/waiting/:id` | 🟠 | ✅ |
| P-22 | chat.html | `/consult/conversation/:id` | 🟡 | ✅ |
| P-23 | rate.html | 评分(consult 流内) | 🟡 | ✅ |
| P-25 | archive-confirm.html | 存档确认 | ⚫ | ⚫deferred(④,与同步模型冲突) |

### 内容 / 通知
| 原型 | html | app 路由 | 档次 | 状态 |
|---|---|---|---|---|
| P-12 | detail.html | `/content/:id` | 🟡 | ✅ |
| P-13 | notif.html | `/notifications` | 🟡 | ✅ |
| P-39 | publish-done.html | 发布成功 | ⚫ | ⚫deferred(②) |
| P-39b | publish-reviewing.html | 审核中 | ⚫ | ⚫deferred(②) |
| P-39c | publish-rejected.html | 被拒 | ⚫ | ⚫deferred(②) |

### 成长档案 / 里程碑
| 原型 | html | app 路由 | 档次 | 状态 |
|---|---|---|---|---|
| P-28 | catatan-calendar.html | `/profile/day` 日历 | 🟡 | ✅ |
| P-30 | pet-create.html | `/profile/create` | 🟡 | ✅ |
| P-31 | pet-success.html | `/profile/created` 庆祝 | 🟢 | ✅(顺验纸屑动画) |
| P-32 | pet-edit.html | `/profile/edit` | 🟡 | ✅ |
| P-33 | milestone.html | `/profile/milestones` | 🟡 | ✅ |
| P-33b | milestone-sheet.html | 里程碑抽屉(页内,无独立路由) | 🟠 | ⚠️实现待核验 |
| P-34 | badge-gallery.html | 徽章图鉴(无独立路由) | 🟠 | ⚠️实现待核验 |
| P-35 | milestone-unlock.html | 解锁动效 | 🟡 | ✅待逐项验 |
| P-36 | namecard.html | `/card/preview` 名片H5 | 🟠 | ✅待验 |

### 设置 / 账户
| 原型 | html | app 路由 | 档次 | 状态 |
|---|---|---|---|---|
| P-41 | settings.html | `/me/settings` | 🟡 | ✅ |
| P-43 | delete-account.html | 注销(settings 内) | 🟠 | ✅(去掉冲突冷静期,⑥) |

### 空态 / 错误态
| 原型 | html | app 路由 | 档次 | 状态 |
|---|---|---|---|---|
| P-10b | feed-empty.html | Feed 空态 | 🟡 | ✅ |
| P-10c | feed-error.html | Feed 错误态 | 🟡 | ✅ |
| P-13b | notif-empty.html | 通知空态 | 🟢 | ✅ |
| P-28b | timeline-empty.html | 时间线空态 | 🟢 | ✅ |
| (通用) | network-error.html | 系统级无网络态 | — | ⚠️审计未单列,需补核 |

### 兽医端（V- 系列）
| 原型 | html | app 路由 | 档次 | 状态 |
|---|---|---|---|---|
| V-01b | vet-login.html | `/vet/login` | 🟠 | ✅ |
| V-01 | vet-dashboard.html | `/vet/workbench` 首页 tab | 🟠 | ✅ |
| V-02 | vet-queue.html | 待接单(workbench tab) | 🟡 | ✅ |
| V-03 | vet-case.html | `/vet/request/:id` | 🟡 | ✅ |
| V-05 | vet-chat.html | `/vet/conversation/:id` | 🟡 | ✅ |
| V-06 | vet-history.html | 历史(workbench tab) | 🟢 | ✅ |
| V-07 | vet-profile.html | 兽医我的(workbench tab) | 🟡 | ✅ |
| V-08 | vet-final-diagnosis.html | 诊断表单 | ⚫ | ⚫deferred(④,无后端契约) |
| V-ST | vet-status-popup.html | 状态弹窗 | ⚫ | ⚫简化为 toggle |

### ⚠️ app 有路由但无原型标准（计划外，需先定）
| app 路由 | 页 | 情况 |
|---|---|---|
| `/gath` | 聚会页 GathPage | 原型 `pages/` **无对应 html**；仅首页快捷卡有 "Gabung Gath" 入口文案。无 1:1 标准，需产品定视觉规范 |
| `/me/language` | 独立语言设置页 | 原型把语言放在 settings 内，**无独立 language.html**。需确认是并入设置还是单独定规范 |
| `/dev/login-guide`、`/dev/triage` | 调试入口 | 🛠️非生产屏，故意排除，不还原 |

### 盲区结论
- **53 屏原型 + 4 tab 全部已对账**：✅在计划 ~40 屏 / ⚫deferred 6 屏(publish 三态·存档确认·诊断表单·状态弹窗) / ⚠️待核或无标准 5 屏(里程碑抽屉·徽章图鉴·network-error·gath·me/language)。
- **唯一两个「无原型标准」**：`/gath`、`/me/language` —— 需产品先给规范，否则游离计划外。
- **里程碑抽屉 / 徽章图鉴**：原型有(P-33b/P-34)但 app 无独立路由，实现是否到位待核；改进前必须逐屏确认。
