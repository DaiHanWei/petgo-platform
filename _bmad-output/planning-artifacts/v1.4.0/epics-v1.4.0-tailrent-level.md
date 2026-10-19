---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.0/Tailrent等级/1-4-0Tailrent等级prd.md
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-tailrent-level-delta.md
  - _bmad-output/planning-artifacts/v1.4.0/decision-log.md                         # ① 节
  - _bmad-output/planning-artifacts/v1.4.0/Tailrent等级/ui-1-4-0-tailrent-level-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.0/Tailrent等级/ui-admin-1-4-0-tailrent-level-integrated-v1.html
  - _bmad-output/planning-artifacts/architecture.md                                 # 跨版本基线（不重定向）
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md                  # 「2026-10-18」节
version: v1.4.0
theme: tailrent-level
status: complete
updated: '2026-10-18'
---

# petgo-platform V1.4.0 tailrent-level - Epic Breakdown

## Overview

本文件把 V1.4.0 ①「Tailrent Level（用户等级 · 成长值）」PRD（FR-143 / FR-144 · AB-32 / AB-33）、UI 稿与架构增量拆成可实现的 story。范围：后端 + 运营后台 + App。分支 `feat/1.4.0-tailrent-level`（从 `dev_1.4.0` 地基之后切出）。

> 🔴 **口径优先级**：`decision-log.md` ① 节 > PRD > `architecture-v1.4.0-tailrent-level-delta.md`（AD-1 ~ AD-18、P-1 ~ P-6、V-1 ~ V-4）> UI 稿（定稿）。跨分支契约以 `CROSS-STORY-DECISIONS.md`「2026-10-18」节为准。
> 地基 Story 0.1 已落地（共享约束、`GrowthQueryService` 签名、4 个跨分支事件、抽屉页签头），本线 story **不重复**。

## Requirements Inventory

### Functional Requirements

**FR-143 成长值与等级**
- **FR-143.1** 规则入账：14 条规则（`DAILY_LOGIN` / `POST_PUBLISHED` / `COMMENT_POSTED` / `LIKE_GIVEN` / `PLACE_CHECKIN` / `LIKE_RECEIVED` / `COMMENT_RECEIVED` / `COLLECT_RECEIVED` / `MILESTONE_S·M·L` / `OPS_CAMPAIGN` / `GIFT_SENT` / `GIFT_RECEIVED` / `SPEND_DIGITAL` / `SPEND_SHOP`），分值与每日上限按 PRD 表、后台可配；WIB 自然日切天；被动三项合计 50 / 天。
- **FR-143.2** 消费返成长值：按实付总额（现金 + PawCoin）、四舍五入、一笔只算一次；充值不计、送礼不走消费返；按支付用途归档（数字服务 / Toko / 不计），新用途默认数字服务。
- **FR-143.3** 扣回：退款（部分退款按比例、累计不超扣）、取消点赞、取消收藏、违规下架（只扣发帖分）；只扣当初实际入账部分；扣回不返还当日额度；作者自删帖 / 评论被删不扣；下架恢复不补回（P-1）。
- **FR-143.4** 防刷：点赞 / 收藏按（人, 帖）终身一次；自评（含回复）两边都不给、不占额度；里程碑按（用户, 规则, 关联对象）去重且同 code 累计最多 5 次；私密 Diary 照常得分。
- **FR-143.5** 等级曲线：按累计成长值定级，Lv1–20 四段位；等级表可末尾追加、可改门槛并重算；满级后照常累积；扣回 / 运营扣减 / 调高门槛可导致降级。
- **FR-143.6** 升级反馈：跨级弹升级庆祝（段位内卡片 / 跨段位浅色全屏，连跳只弹一次显示最终级）；发 `GROWTH_LEVEL_UP` 通知 + 推送；App 内即时弹、App 外下次进首页弹；升级庆祝优先于运营开屏弹窗；降级不通知不弹窗。
- **FR-143.7** 存量补算：上线一次性补算里程碑分 + 活跃天数 × 3（活跃天数自 2026-09-25 起，P-6）；不弹逐级庆祝、不发通知；老用户首次打开弹一个汇总弹窗（复用运营弹窗卡片）。
- **FR-143.8** 虚拟号：不参与成长值、不入任何流水；显示运营设定的展示等级（上线随机 Lv6–8，新建同样随机，后台可改）；只用于角标与头像框，不享露出加权等任何权益；无等级页与庆祝。
- **FR-143.9** 注销：清理成长值账户、流水及相关数据；后台查不到该用户明细；停用 / 注销账号不显示等级。

**FR-144 等级展示与权益**
- **FR-144.1** 头像等级角标 + 头像框：Feed（Jelajah / Mengikuti）、详情、评论（含二级回复）、他人主页、关注头像条；直径 ≥ 32 显示数字、< 32 只显示段位色圆点；角标在框之上不被遮挡；戴框用户的关注条头像用框替代紫环；运营标签不受影响；点他人角标 = 点头像。
- **FR-144.2** 「我的」页：等级不放头像上，昵称下方等级胶囊（段位色 + 「Lv{n} · {段位名}」+ ›），点进等级页；头像框照常戴、相机角标不动。
- **FR-144.3** 等级页 + EXP 明细页：当前等级 / 段位 / 进度条（每级一色渐变，满级显示已满级）、头像框一排点选、各段位权益（不显示优惠券）、怎么涨成长值（今日已得 / 上限 / 今日已满）、最近 3 条明细 + 独立明细页（按日分组、分页、扣回写原因）；加载中 / 失败重试 / 空态 / 加载更多失败。
- **FR-144.4** 头像框佩戴：默认自动戴最高可用档；手动选或不戴后升级不再自动更换；升级庆祝提供「戴上新头像框」；降级后已戴的不可用档自动退回最高可用档；三档框代码绘制，动效框 8s 一圈、减少动态效果时静止。
- **FR-144.5** 露出加权：Lv15+ 作者在首页推荐流 ×1.05（后台算法参数可调，Lv15 门槛写死），分类 Tab 不受影响；对用户说「Postingan lebih sering tampil」。

**AB-32 成长体系配置（后台）**
- **AB-32.1** 「⚙️ 配置与安全 → 成长体系」页，排在算法参数之后；三页签：成长值规则（480 抽屉编辑、勾选框启停、原生 diff 确认、不能新增规则代码，含被动合计上限）、消费换算（`PAWCOIN_TOPUP` / `GIFT` 锁定）、等级表（末尾追加、改门槛、服务端确认弹窗显示升 / 降人数，保存后重算）。
- **AB-32.2** 权限 `GROWTH_CONFIG_VIEW` / `GROWTH_CONFIG_EDIT`（显示名「查看 EXP 配置」/「改 EXP 配置」）；只读视图输入框禁用 + 卡底提示；修改从下一次入账起生效、不追溯；全部写审计（改前改后）。

**AB-33 用户成长值（后台）**
- **AB-33.1** 用户抽屉「成长值」页签：当前等级、累计、距下一级；明细（时间、规则、增减、关联对象、关联宠物，可按规则与时间筛选）。
- **AB-33.2** 手动调整：行内表单（普通调整 / 运营活动，增减值可负、原因或活动名必填）→ 服务端确认弹窗（显示调整后累计与等级变化）→ 入账、触发升降级、流水记操作人；权限 `GROWTH_USER_ADJUST`（预授 OPS_MANAGER，P-5），无权限置灰不隐藏。
- **AB-33.3** 批量发放：成长体系页右上「批量发放」（无权限隐藏）→ 600 抽屉三步条（填写 / 预览 / 发放），粘贴或 CSV 上传用户 ID、每人 EXP、活动名；预览排除无效 ID（不存在 / 已注销 / 已停用）与虚拟号，校验不过的行不阻塞整批；服务端确认弹窗显示人数 × EXP、合计、批次号；按（批次号, 用户）去重；写审计。
- **AB-33.4** 虚拟号展示等级：「运营发布身份」列表加「展示等级」列、详情抽屉加「展示等级」卡（下拉 + 保存 + 原生确认，Lv1 ~ 当前最高级）；新建表单不加此字段；虚拟号的用户抽屉成长值页签只显示展示等级。

**埋点**
- **FR-AN** App 侧 5 个：`growth_level_up`（from_level / to_level / is_tier_up）、`growth_level_page_viewed`（source）、`growth_celebration_shown`（to_level）、`avatar_frame_changed`（frame_id）、`growth_backfill_popup_shown`（level）；服务端不另埋（V-3）。

### NonFunctional Requirements

- **NFR1 幂等**：入账、扣回、批量发放、消费去重全部由唯一幂等键保证；回调与轮询同时到达只入一次（AC5）。
- **NFR2 并发正确**：每日额度与被动合计在并发下不超上限（原子领额）。
- **NFR3 性能**：列表接口批量取等级，禁止逐条查询（测试断言查询次数）。
- **NFR4 时区**：「每日」一律 WIB 自然日，只用 `ScheduleWindow.WIB`。
- **NFR5 护栏**：只用 `@Async` + DB 状态机，不引 MQ / 缓存；`ddl-auto=validate`；Flyway 时间戳号（预分配 `V20261018_1500 ~ 1503`）。
- **NFR6 审计**：后台所有成长体系修改、调整、批量发放、展示等级修改写审计（改前改后）。
- **NFR7 本地化**：App 不渲染后端显示串（规则名 / 原因 / 段位 / 权益按代码本地化），仅运营填写的 `note` 原样显示；EN / ID 全部新增 key，取 UI 稿文案表。
- **NFR8 可访问性**：段位色白字对比 ≥ 4.5:1；动效框尊重系统「减少动态效果」。
- **NFR9 隐私**：别人的成长值明细不对外；日志不记 PII。

### Additional Requirements

（取自 `architecture-v1.4.0-tailrent-level-delta.md`，story 实现以该文为准）
- AD-1 ~ AD-4 数据模型：7 张 `growth_*` 表（账户 / 流水 / 每日额度 / 规则 / 消费档位 / 等级表 / 重算任务）；被动合计上限为 `growth_rules` 伪行 `PASSIVE_GROUP`（V-1）。
- AD-5 / 5.4 写入单入口 `GrowthLedgerService.accrue / reverse / adjust`；AD-6 规则 → 事件 → 幂等键总表；5.2 幂等键集中在 `GrowthIdemKeys`。
- AD-7 需补事件：`UserDailyActiveEvent`、`ContentUnlikedEvent`、`PlaceCheckedInEvent.checkinId`、`MilestoneCompletedEvent.petId`、`ShopOrderPaidEvent.userId / paidTotal`。
- AD-8 统一消费信号（`PaymentIntentPaidEvent` + `PawCoinSpentEvent` + `ShopOrderPaidEvent`）；AD-9 `RefundSucceededEvent` 五处发布（P-3 / P-4）。
- AD-10 等级变化与头像框读时推导；AD-11 弹窗接口与拉取时机、`StartupPopupLedger` 新增两枚举；AD-12 等级表重算任务；AD-13 存量补算迁移（P-6）。
- AD-14 AuthorView 在 `basicViews` 层批量加 `growth`，拍平 DTO 加 `authorLevel / authorTier / authorFrame`，`/me` 加 growth 摘要；AD-15 Feed 作者等级加权；AD-16 `/api/v1/me/growth/**` 接口；AD-17 后台；AD-18 注销清理。
- 跨线：② 依赖 `GrowthLevelChangedEvent` 与 `getLevel`；③ 的 `GiftSentEvent`、④ 的收藏事件只由本线监听；② 合并前流水宠物 ID 取 `content_posts.pet_id`。

### UX Design Requirements

（UI 稿定稿 2026-10-14，屏号对应稿件；文案取 UI 稿顶部文案表 32 条）
- **UX-DR1（A1）** 角标与头像框规格：段位色 4 个（Pemula #66748C · Sahabat #0A7FA3 · Penjaga #2B4FBF · Bintang #4A2E9E→#B5307F）；数字 / 圆点两形态（阈值 32）；三档框形态（基础 = 段位色实色环 / 升级 = 渐变环 + 外细环 / 动效 = 流光渐变环 8s 一圈）；角标压框。
- **UX-DR2（A2 / A3 / A4 / A8）** Feed 卡片、详情 + 评论区（30 / 24 圆点）、他人主页（84）、关注头像条（56，戴框替代紫环）接入。
- **UX-DR3（A6）** 「我的」页昵称下方等级胶囊，相机角标不动；**（A7）** 已注销 / 停用账号不显示角标与框。
- **UX-DR4（B1–B5）** 等级页首屏 / 下滑 / 已满级 / 加载中 / 加载失败；进度条当前级色 → 下一级色渐变。
- **UX-DR5（B6–B8）** EXP 明细页、空态、加载更多失败；规则行带图标、扣回写原因。
- **UX-DR6（C1–C5）** 头像框一排：切换中 / 成功 / 失败（弹回 + toast）/ 点未解锁（提示 Terbuka di LvN）/ 灰段位全锁。
- **UX-DR7（D1–D7）** 升级庆祝：段位内卡片（进度环，Lihat Level Saya / Nanti saja）、跨段位浅色全屏（柔光、一次性彩屑、等级跳变、权益清单、吸底按钮；手动选过框时主按钮 Pakai Bingkai Baru）、戴上新框成功 / 失败、冷启动连跳多级卡片。
- **UX-DR8（E1）** 存量汇总弹窗：ops_popup_dialog 卡片，上半段位浅色底头像 + LvX + 段位名，下半补算小账单（活跃天数 × 3、已完成里程碑、合计 EXP），按钮 Lihat Level Saya。
- **UX-DR9（F1 / F2）** 通知中心升级条目（图标 / 文案 / 深链到等级页）、系统推送规格。
- **UX-DR10（G1–G7）** 后台成长体系：规则页签、编辑抽屉、校验失败、消费换算、等级表、二次确认、只读视图。
- **UX-DR11（H1–H5）** 批量发放：填写 / 预览 / 二次确认 / 成功 / 失败。
- **UX-DR12（I1–I7）** 用户抽屉成长值页签：概览 + 明细、明细空、运营活动调整、二次确认、成功、虚拟号视图、无调整权限。
- **UX-DR13（J1 / J2）** 运营发布身份列表「展示等级」列、详情抽屉「展示等级」卡。
- **UX-DR14（K1）** 算法参数页新增「作者等级加权（Lv15 及以上作者，默认 1.05；1 = 不加权）」，插在限流系数之后。

### FR Coverage Map

- FR-143.1 规则入账 → Epic 1
- FR-143.2 消费返成长值 → Epic 1
- FR-143.3 扣回 → Epic 1
- FR-143.4 防刷 → Epic 1
- FR-143.5 等级曲线（定级 / 降级；追加与重算在 Epic 4）→ Epic 1 / Epic 4
- FR-143.6 升级反馈 → Epic 2
- FR-143.7 存量补算 → Epic 2
- FR-143.8 虚拟号展示等级 → Epic 3（展示）/ Epic 4（后台设置）
- FR-143.9 注销清理 → Epic 1
- FR-144.1 角标与头像框 → Epic 3
- FR-144.2 「我的」页等级胶囊 → Epic 1
- FR-144.3 等级页 + EXP 明细 → Epic 1（头像框一排在 Epic 3）
- FR-144.4 头像框佩戴 → Epic 3
- FR-144.5 露出加权 → Epic 3
- AB-32.1 / AB-32.2 成长体系配置 → Epic 4
- AB-33.1 ~ AB-33.4 用户成长值与批量发放、虚拟号展示等级 → Epic 4
- FR-AN 埋点 → Epic 1（`growth_level_page_viewed`）/ Epic 2（升级、庆祝、存量弹窗）/ Epic 3（`avatar_frame_changed`）

## Epic List

### Epic 1: 攒成长值、看见自己的等级
用户日常行为与消费获得 EXP，退款 / 取消 / 违规按规则扣回；在「我的」页点等级胶囊进入等级页与 EXP 明细页，看到等级、进度、今日已得与全部流水；注销时数据随之清理。
**FRs covered:** FR-143.1 ~ FR-143.5（定级部分）、FR-143.9、FR-144.2、FR-144.3（不含头像框一排）、NFR1 ~ NFR5、NFR7、NFR9
**实现要点:** AD-1 ~ AD-10、AD-16、AD-18；迁移 `V20261018_1500`、`V20261018_1504`。

### Epic 2: 升级有庆祝，老用户有起点
升级时弹庆祝（段位内卡片 / 跨段位全屏）并收到通知推送；老用户上线后首次打开看到「你已是 LvX」汇总弹窗。
**FRs covered:** FR-143.6、FR-143.7、FR-AN（升级 / 庆祝 / 存量弹窗）、UX-DR7 ~ UX-DR9
**实现要点:** AD-11、AD-13；迁移 `V20261018_1503`；「戴上新框」在本 epic 先跳等级页，Epic 3 换成一键戴上。

### Epic 3: 等级被看见、带来权益
等级角标与三档头像框出现在所有头像上；在等级页选框；Lv15+ 帖子在推荐流更常被看到；虚拟号显示展示等级。
**FRs covered:** FR-144.1、FR-144.4、FR-144.5、FR-143.8（展示）、FR-AN（`avatar_frame_changed`）、UX-DR1 ~ UX-DR3、UX-DR6、UX-DR14、NFR3、NFR8
**实现要点:** AD-14、AD-15、AD-16（切框）；迁移 `V20261018_1501`。

### Epic 4: 运营管理成长体系
运营配置规则 / 消费换算 / 等级表（改后自动重算），在用户抽屉查看与调整成长值，批量发放活动 EXP，设置虚拟号展示等级。
**FRs covered:** AB-32.1、AB-32.2、AB-33.1 ~ AB-33.4、FR-143.5（追加与重算）、FR-143.8（后台设置）、NFR6、UX-DR10 ~ UX-DR13
**实现要点:** AD-12、AD-17；迁移 `V20261018_1505`（`V20261018_1502` 作废）。

**依赖：** Epic 1 → 2 → 3 → 4（只向后依赖，无环）。

## Epic 1: 攒成长值、看见自己的等级

用户日常行为与消费获得 EXP，退款 / 取消 / 违规按规则扣回；在「我的」页进入等级页与 EXP 明细页；注销时数据随之清理。

### Story 1.1: 成长值账本与日常行为入账

As a 活跃用户,
I want 登录、发帖、评论、点赞、打卡、完成里程碑、被收藏、送收礼都按规则自动得到 EXP，取消或违规时按规则扣回,
So that 我在平台上的投入被持续记下来。

**范围：** 后端。迁移 `V20261018_1500`（5 张表：`growth_accounts` / `growth_ledger` / `growth_daily_counters` / `growth_rules` / `growth_levels` + seed 规则 / Lv1–20 / `PASSIVE_GROUP` 伪行；`growth_spend_tiers` 在 Story 1.3、`growth_recalc_jobs` 在 Story 4.1 才建）；`GrowthLedgerService.accrue / reverse`、`GrowthIdemKeys`、每日额度原子领额；替换地基 `GrowthQueryService` 占位方法体（签名不变，读 `growth_accounts.level`，无行 = Lv1）；补事件 `UserDailyActiveEvent` / `ContentUnlikedEvent` / `PlaceCheckedInEvent.checkinId` / `MilestoneCompletedEvent.petId`；监听 AD-6 表中除 `SPEND_*` 外的全部规则与扣回。依据 AD-1 ~ AD-7、5.2 ~ 5.5、5.7。

**Acceptance Criteria:**

**Given** 真实用户当天（WIB）首次发起已认证请求
**When** `user_active_days` 首次写入成功
**Then** 入账一条 `DAILY_LOGIN` +3，同日后续请求不再入账；跨 WIB 零点（UTC 17:00）后可再得（AC1）

**Given** 用户当天已发 3 帖
**When** 发第 4 帖
**Then** 第 4 帖不入账；私密 Diary 与公开帖同样 +5 并计入 3 次（AC15）；同一帖因举报预处置后再审核通过而二次发布事件时，只入账一次

**Given** 作者当天被点赞 / 被评论 / 被收藏已累计 48
**When** 再被评论一次（+3）
**Then** 只入账 2，被动合计恰好 50；之后当天被动类不再入账（AC2）

**Given** A 对作者 B 的帖子点赞
**When** A 点赞 → 取消 → 再点赞
**Then** A 的 `LIKE_GIVEN` 与 B 的 `LIKE_RECEIVED` 最终都为 +1 −1 +0（AC3）；取消点赞时若 B 当初因满 50 未入账，则 B 不被扣（AC14）

**Given** 用户在自己的帖子下评论或回复别人
**When** 评论变为可见
**Then** `COMMENT_POSTED` 与 `COMMENT_RECEIVED` 都不入账，也不占当天 5 次额度（AC15）；他人评论或回复时 `COMMENT_RECEIVED` 只给帖子作者（P-2）

**Given** 收藏事件（④ 的 `ContentCollectedEvent` / `ContentUncollectedEvent`）
**When** 收藏 → 取消 → 再收藏，或收藏人就是作者
**Then** 作者最终 +2 −2 +0；作者自收藏不入账（AC14a）

**Given** 同账号同一里程碑代码已入账 5 次
**When** 第 6 次完成该里程碑
**Then** 里程碑与徽章照常完成，无成长值流水（AC15a）

**Given** 运营违规下架一条帖子
**When** `ContentRemovedEvent` 发出
**Then** 只扣回该帖的 `POST_PUBLISHED`，被赞 / 被评 / 被收藏所得不扣（AC7）；作者自删帖、评论被删不扣；下架后恢复不补回（P-1）

**Given** ③ 发出 `GiftSentEvent`（`totalAmount` = Rp12,500）
**When** 监听处理
**Then** 送礼人 `GIFT_SENT` 入账 63（12.5 × 5 四舍五入）、宠物主人 `GIFT_RECEIVED` 入账 50，不设上限；③ 合并前无事件，规则自然不触发

**Given** 虚拟号作者、已注销或已停用用户
**When** 其行为或他人对其互动触发任何规则
**Then** 该虚拟号 / 注销 / 停用用户没有任何流水（AC12）；真实点赞人的 `LIKE_GIVEN` 照常

**And** 每条规则有「正常 / 当日满额 / 幂等重放 / 扣回」测试；被动合计与日额度有两线程并发测试不超上限；流水只追加，扣回为指向原流水的反向记录；`ck_*` 共享约束不改；`mvn -B clean package` 绿。

### Story 1.2: 等级变化与注销清理

As a 用户,
I want 累计 EXP 达到门槛时等级自动变化、注销后成长值数据被清理,
So that 我的等级总是准确的，个人数据不残留。

**范围：** 后端。AD-10 定级 / 降级与 `GrowthLevelChangedEvent`（升降都发）、头像框读时推导的服务方法（供 Story 1.4 / Epic 3 使用）；`GrowthLevelChangeHandler` 为单一连锁出口（本 story 只做定级与事件，庆祝与通知在 Epic 2）；AD-18 `GrowthAccountDeletionService` 接入 `AccountDeletionService`。

**Acceptance Criteria:**

**Given** 用户累计 495、等级 Lv4
**When** 入账 +5 达到 500
**Then** 等级变为 Lv5（BUDDY），发布一次 `GrowthLevelChangedEvent(u, 4, 5)`

**Given** 用户一次入账跨越多级（Lv4 → Lv7）
**When** 入账完成
**Then** 只发布一次 `GrowthLevelChangedEvent(u, 4, 7)`

**Given** Lv10 用户因退款累计跌破 3,300
**When** 扣回完成
**Then** 等级降为 Lv9 并发布事件（AC6）；`GrowthQueryService.getLevel` 立即返回 Lv9

**Given** 达到当前最高级的用户
**When** 继续入账
**Then** 累计照常增加，等级不变

**Given** 用户手动选了 UPGRADED 框后从 Lv10 降到 Lv9
**When** 查询其生效头像框
**Then** 返回 BASIC（AC19）；未手动选过的用户返回当前段位最高可用档，NEWBIE 无框

**Given** 用户注销
**When** `AccountDeletionService.execute` 执行
**Then** 该用户的 `growth_accounts` / `growth_ledger` / `growth_daily_counters` 行全部删除，后台查不到其明细（AC10）；调用位于 tailsonality 之后、PawCoin 作废之前

**And** ② 可依赖的 `GrowthLevelChangedEvent` 字段与地基一致；`mvn -B clean package` 绿。

### Story 1.3: 消费返成长值与退款扣回

As a 付费用户,
I want 每笔消费按实付金额返 EXP、退款时按比例扣回,
So that 付费也算在我的成长里，而且规则公平不重复。

**范围：** 后端。迁移 `V20261018_1504`（`growth_spend_tiers` + seed 档位，`PAWCOIN_TOPUP` / `GIFT` 锁定）；AD-8（`PawCoinSpentEvent`、`ShopOrderPaidEvent` 补 `userId / paidTotal`、`GrowthSpendListener`）、AD-9（`RefundSucceededEvent` 五处发布、`GrowthRefundListener`）。逐支付用途核对 `ref` 口径（退款能反查到原消费流水），写入架构增量 §5 口径表并配测试。

**Acceptance Criteria:**

**Given** 用户用 PawCoin 充值
**When** 充值到账
**Then** 不产生任何成长值（AC4）

**Given** 用户用纯 PawCoin 买 KTP 高清（Rp20,000）
**When** 扣币成功
**Then** `SPEND_DIGITAL` 入账 100；同一笔不会因 `IdHdPawcoinUnlockedEvent` 再算一次

**Given** 用户用 QRIS 买 Tailsonality 解锁
**When** 回调与轮询同时到达
**Then** 只入账一次（AC5）；`KeepsakeUnlockedEvent` 不被计入

**Given** 用户用 MIXED（PawCoin + QRIS）支付 Toko 订单 Rp200,000
**When** 支付成功
**Then** 按订单只入账一次 `SPEND_SHOP` 100（AC4 / AC13）；币段 `debit` 与现金段都不再单独计

**Given** 支付用途为 `GIFT`
**When** 付款成功
**Then** 不走消费返（只走 Story 1.1 的送礼规则）

**Given** Toko 订单 Rp200,000 已得 100
**When** 先退 Rp60,000、再退 Rp140,000
**Then** 依次扣回 30、70，共 100，不超扣（AC13）

**Given** 某笔消费全额退款
**When** 退款成功
**Then** 该笔消费的成长值全部扣回；累计跌破当前等级门槛时等级下降（AC6，降级由 Story 1.2 已实现的等级逻辑生效）

**Given** 五处真实退钱路径（咨询退回 PawCoin、咨询真钱打款、电商退货含退到银行、电商异常单退币段、问诊中断退款）
**When** 退款记录落为成功 / `REFUNDED`
**Then** 均发布 `RefundSucceededEvent` 并扣回（P-3 / P-4）；`AdminTicketRefundService` 经 `RefundService` 覆盖；原消费当初未入账则不扣

**And** 支付链路既有测试全绿；新增事件只在原事务内 `publishEvent`，不改变支付 / 退款结果；`mvn -B clean package` 绿。

### Story 1.4: 等级页与 EXP 明细接口

As a 用户,
I want 一个接口拿到等级页所需的全部信息、另一个接口分页看 EXP 明细,
So that App 能展示我的等级、进度、今天能涨多少和历史流水。

**范围：** 后端。AD-16 的 `GET /api/v1/me/growth`、`GET /api/v1/me/growth/ledger?cursor=`；`/me`（`UserProfileResponse`）加 `growth` 摘要（AD-14 的 `/me` 部分）；V-2「今日已得」口径。规则名 / 原因 / 段位 / 权益只下发代码，不下发显示串。

**Acceptance Criteria:**

**Given** 登录的真实用户
**When** 请求 `GET /api/v1/me/growth`
**Then** 一次返回：等级、段位、累计、下一级门槛（满级为 null）、完整等级表、头像框四档（可用 / 生效 / 是否手动）、全部启用规则（代码、每次分值、每日上限、今日已得、是否今日已满）、各段位权益代码（不含优惠券，AC20）、最近 3 条流水

**Given** 用户今天已得 `LIKE_GIVEN` 3 次（+3）
**When** 请求等级页
**Then** 该规则今日已得为 3、未满；达到 5 次后标为今日已满（AC17）

**Given** 用户有 45 条流水
**When** 请求 `/ledger` 首页与下一页
**Then** 按 `created_at DESC, id DESC` 每页 20 条、游标翻页无重复无遗漏；每行含规则代码、增减值、原因代码、`note`、时间；扣回行带原因（REFUND / UNLIKE / UNCOLLECT / TAKEDOWN），运营调整与活动带 `note`

**Given** 虚拟号或游客
**When** 请求上述接口
**Then** 虚拟号返回不参与（无等级页数据）；游客 401

**Given** `GET /api/v1/me`
**When** 真实用户请求
**Then** 响应含 `growth: {level, tier, frame, lifetimeTotal, nextThreshold}`

**And** 错误走 RFC 9457；别人的明细无任何接口可取；接口有 controller 测试；`mvn -B clean package` 绿。

### Story 1.5: 「我的」页等级胶囊、等级页与 EXP 明细页（App）

As a 用户,
I want 在「我的」页看到自己的等级胶囊，点进去看等级页和完整明细,
So that 我知道自己在哪一级、离下一级还差多少、EXP 是怎么来的。

**范围：** App。UI 稿 A6、B1 ~ B8（头像框一排 C 组在 Epic 3，本 story 等级页先不放该区块或仅占位不可点）；`features/growth/`；段位色常量与 `growthLevelColor` 插值（5.6）；arb 新增 `growth*` 键（取 UI 稿文案表）；埋点 `growth_level_page_viewed`（source = me_page）。

**Acceptance Criteria:**

**Given** 真实用户打开「我的」页
**When** 页面加载
**Then** 昵称下方显示等级胶囊（段位色 + 「Lv{n} · {段位名}」+ ›），头像右下相机角标位置不变（A6）；点胶囊进入等级页并上报 `growth_level_page_viewed`

**Given** 等级页
**When** 加载成功
**Then** 显示当前等级、段位名、段位色、进度条（当前级色 → 下一级色渐变）与「还差 N EXP 升到 LvX」；满级显示「已满级」（B1 / B3）；各段位权益已解锁高亮、未解锁置灰写解锁等级，不出现优惠券（AC20）；「怎么涨成长值」列出每条规则分值、今日已得 / 上限、今日已满标记（B2，AC17）；最近 3 条明细 +「Lihat semua」

**Given** 等级页加载中或失败
**When** 网络慢或出错
**Then** 显示骨架（B4）或失败态 + 重试（B5）

**Given** EXP 明细页
**When** 打开 / 下拉到底 / 加载更多失败 / 无记录
**Then** 按 WIB 日分组分页显示（B6），规则名与扣回原因按代码本地化，运营 `note` 原样显示；空态（B7）；加载更多失败可重试（B8）

**And** 所有文案来自 arb（EN / ID），不渲染后端显示串；段位色只在 `colors.dart` 定义；`flutter analyze` 与相关 `flutter test` 绿；完成后在 Android 模拟器打开「我的」页与等级页给用户看（L2 视觉对照 UI 稿）。

## Epic 2: 升级有庆祝，老用户有起点

升级时弹庆祝并收到通知推送；老用户上线后首次打开看到「你已是 LvX」汇总弹窗。

### Story 2.1: 升级通知与弹窗接口

As a 用户,
I want 升级时收到一条通知和推送，并且不论在不在 App 里都能看到一次升级庆祝,
So that 升级这件事被郑重地告诉我。

**范围：** 后端。AD-10 连锁部分（在 `GrowthLevelChangeHandler` 内：升级且非 `LAUNCH_BACKFILL` → 写 `celebrate_from_level / celebrate_to_level`、AFTER_COMMIT 发 `GROWTH_LEVEL_UP`）；AD-11 `GET /api/v1/me/growth/popups` 与 `POST /api/v1/me/growth/popups/{kind}/ack`（本 story 先实现 `celebration`，`backfill` 字段在 Story 2.2 填充）。通知走 `NotificationService.sendWithCopy`（参照 `MilestoneNotifyListener`），深链 → 等级页，推送文案 EN / ID 走 i18n。

**Acceptance Criteria:**

**Given** 用户从 Lv4 升到 Lv5
**When** 入账事务提交
**Then** 写入待弹庆祝（from 4 → to 5），发送一条 `GROWTH_LEVEL_UP` 通知与推送，深链到等级页

**Given** 用户已有待弹庆祝（from 4 → to 5）尚未确认
**When** 又升到 Lv7
**Then** 待弹变为 from 4 → to 7（只弹一次、显示最终等级），通知只新增一条（AC8：一次跨多级只发一条）

**Given** 用户降级
**When** 等级下降
**Then** 不写待弹、不发通知（PRD：降级不通知不弹窗）

**Given** 请求 `GET /popups`
**When** 存在待弹庆祝
**Then** 返回 `celebration: {fromLevel, toLevel, tierUp, unlockedBenefits[], frameAction}`；`tierUp` = 是否跨段位；`frameAction` 在用户手动选过框且新段位解锁新框时为 `OFFER_NEW_FRAME`，否则为 `AUTO`（未手动选过、已自动换上）或 `NONE`

**Given** App 调用 `POST /popups/celebration/ack`
**When** 重复调用
**Then** 清掉待弹，幂等无错

**And** 虚拟号不会触发；推送前跳过虚拟号（`sendWithCopy` 既有行为）；`GROWTH_LEVEL_UP` 的推送分类映射由 ④ 负责、本 story 不处理；`mvn -B clean package` 绿。

### Story 2.2: 存量用户补算与虚拟号展示等级初始化

As a 老用户,
I want 新版本上线时，我过去完成的里程碑和活跃天数被折算成 EXP,
So that 我不是从零开始，而是从「过去的陪伴」起步。

**范围：** 后端。迁移 `V20261018_1503`（纯 SQL，参照 V77 不发通知，须在 1500 之后）；虚拟号新建路径随机展示等级；`/popups` 的 `backfill` 字段与 ack。依据 AD-13、P-6。

**Acceptance Criteria:**

**Given** 真实用户活跃 60 天（自 2026-09-25 起的记录）、完成 10 个 S 级与 3 个 M 级里程碑
**When** 迁移执行
**Then** 写入一条 `LAUNCH_BACKFILL` 流水 = 60 × 3 + 10 × 5 + 3 × 15 = 275，账户等级 Lv4（AC11 示例）；同 code 里程碑超过 5 次的部分不计

**Given** 迁移执行
**When** 补算完成
**Then** 不发 `GrowthLevelChangedEvent`、不发通知、不写待弹庆祝；EXP > 0 的用户 `backfill_popup_pending = true`

**Given** 存量虚拟号
**When** 迁移执行
**Then** 每个虚拟号建账户行，`display_level` 随机 6–8，无流水；后台新建的虚拟号同样随机 6–8

**Given** 老用户首次请求 `GET /popups`
**When** `backfill_popup_pending = true`
**Then** 返回 `backfill: {level, activeDays, milestoneExp, total}`；`ack` 后不再返回；EXP = 0 的用户不返回

**Given** 迁移在已有数据的库上重复执行（scratch 库重放）
**When** Flyway 校验
**Then** 幂等键 `BACKFILL:{u}` 保证不重复入账；空库从头迁移通过

**And** 注销 / 停用用户不补算；L1 在 scratch 库造数验证补算结果；`mvn -B clean package` 绿。

### Story 2.3: 升级庆祝、存量汇总弹窗与通知中心（App）

As a 用户,
I want 升级时看到庆祝页、老用户首次打开看到汇总弹窗、通知中心里有升级条目,
So that 我能感知到自己的成长。

**范围：** App。UI 稿 D1 ~ D7、E1、F1、F2；`growth_popup_coordinator`；`StartupPopupKind` 新增 `growthCelebration` / `growthBackfill`；拉取时机（冷启动进首页、回前台、发帖 / 评论 / 点赞 / 打卡 / 支付成功后约 1.5s、前台收到 `GROWTH_LEVEL_UP` 推送）；通知中心 `_iconStyle` / `_typeLabel` / `_typeBody` / 深链；埋点 `growth_level_up`、`growth_celebration_shown`、`growth_backfill_popup_shown`、`growth_level_page_viewed`（source = celebration / notification）。「戴上新框」按钮本 story 先跳等级页（Story 3.3 改为一键戴上）。

**Acceptance Criteria:**

**Given** 段位内升级（Lv6 → Lv7）
**When** 拉到待弹庆祝
**Then** 当前页上方弹白卡：进度环从旧等级色走满变新等级色、「Lv6 → Lv7」+ EXP、「Lihat Level Saya」/「Nanti saja」（D1）

**Given** 跨段位升级（Lv4 → Lv5 / Lv9 → Lv10 / Lv14 → Lv15）
**When** 拉到待弹庆祝
**Then** 浅色全屏：新段位色柔光、一次性彩屑（不循环）、等级跳变、「Terbuka di {段位}」权益清单、吸底按钮（D2 ~ D4）；`frameAction = OFFER_NEW_FRAME` 时主按钮为「Pakai Bingkai Baru」（本 story 点击跳等级页）

**Given** 冷启动时有段位内连跳多级的待弹
**When** 进入首页
**Then** 只弹一次卡片显示最终等级（D7）；弹出前登记 `StartupPopupLedger`，本次冷启动运营开屏弹窗让路

**Given** 老用户首次打开新版本
**When** `backfill` 存在
**Then** 弹 E1：`ops_popup_dialog` 卡片，上半段位浅色底头像 + LvX + 段位名，下半补算账单（活跃天数 × 3、已完成里程碑、合计 EXP），按钮进等级页；同一次冷启动若同时有升级庆祝，升级庆祝优先、汇总顺延

**Given** 弹窗关闭
**When** 用户点任一按钮或关闭
**Then** 调用对应 ack；再次触发拉取不重复弹

**Given** 通知中心有 `GROWTH_LEVEL_UP`
**When** 用户查看与点击
**Then** 显示升级图标与本地化文案（F1），点击进等级页并上报 `growth_level_page_viewed`（source = notification）

**And** 动效与彩屑尊重「减少动态效果」；文案全部来自 arb；不复用 `showMilestoneCelebration`；`flutter analyze` 与相关测试绿；Android 模拟器上演示 D1 / D2 / E1。

## Epic 3: 等级被看见、带来权益

等级角标与三档头像框出现在所有头像上；可在等级页选框；Lv15+ 帖子在推荐流更常被看到；虚拟号显示展示等级。

### Story 3.1: 作者等级批量下发

As a 浏览内容的用户,
I want 看到每个作者的等级与头像框,
So that 我能认出活跃、资深的宠物家长。

**范围：** 后端。AD-14：`GrowthQueryService.getBadges(ids)`；`AuthorView` 加 `growth`，在 `AccountQueryService.basicViews` 层批量填充（覆盖 `findAuthorViews` 与 `findAuthorViewsWithoutTags`）；拍平 DTO 加 `authorLevel / authorTier / authorFrame`——全仓检索 AuthorView 使用点逐一覆盖（至少 Feed、详情、评论、分享、场所评论、他人主页、关注列表 / 关注条、@ 候选）。

**Acceptance Criteria:**

**Given** Feed 一页 20 条来自 15 个作者
**When** 组装响应
**Then** 每条带作者等级 / 段位 / 生效头像框；成长值相关查询次数为常数（测试断言，禁 N+1）

**Given** 作者为虚拟号（展示等级 Lv15）
**When** 下发
**Then** 显示 Lv15 与 STAR 段位、自动最高档框；权益判定（`getLevel`）仍为不参与

**Given** 作者已注销或已停用
**When** 下发
**Then** 等级字段为空，App 不显示角标与框（A7）

**Given** 作者无账户行（新用户）
**When** 下发
**Then** Lv1 / NEWBIE / 无框

**Given** 他人主页、关注列表、关注条、评论区、@ 候选
**When** 请求
**Then** 均带上述字段；运营标签字段与现状一致不受影响（AC16）

**And** 新字段均可空、向后兼容旧 App；`mvn -B clean package` 绿。

### Story 3.2: 头像等级角标与头像框（App）

As a 浏览内容的用户,
I want 在所有头像上看到等级角标和头像框,
So that 等级在社区里被看见。

**范围：** App。UI 稿 A1 ~ A4、A7、A8；共享组件 `shared/widgets/growth_avatar_decoration.dart`（角标 + 三档框，代码绘制）；`LetterAvatar`、`InitialAvatar`（「我的」页只戴框不放角标）、他人主页头像、关注条（`following_strip.dart`）接入；`FeedItem` / `ContentDetail` / `Comment` / 主页仓储 / `UserProfile` 模型加字段。

**Acceptance Criteria:**

**Given** Feed（Jelajah 与 Mengikuti）卡片 32、详情 36、他人主页 84
**When** 显示作者头像
**Then** 右下角显示「数字 + 段位色」角标（A2 / A3 / A4）

**Given** 评论 30、二级回复 24
**When** 显示头像
**Then** 角标只显示段位色圆点（阈值常量 32）

**Given** 作者戴着头像框
**When** 显示头像
**Then** 框在外圈、角标压在框之上不被遮挡（A1）；基础框 = 段位色实色环、升级框 = 渐变环 + 外细环、动效框 = 流光渐变环 8 秒一圈；系统开启「减少动态效果」时动效框静止

**Given** Mengikuti 关注头像条（56）
**When** 被关注者戴框
**Then** 头像框替代紫色装饰环；没戴框的保留紫环；显示数字角标（A8）

**Given** 「我的」页
**When** 显示本人头像
**Then** 只戴框、不放等级角标，相机角标不动（A6 与 Story 1.5 胶囊一致）

**Given** 点击他人头像上的角标
**When** 点击
**Then** 等同点头像进入对方主页，角标不单独响应

**Given** 作者已注销 / 停用或字段为空
**When** 显示
**Then** 不显示角标与框（A7）

**And** 段位色只取 `colors.dart`；`flutter analyze` 与组件测试（数字 / 圆点阈值、框形态、disableAnimations）绿；Android 模拟器逐屏对照 A1 ~ A8。

### Story 3.3: 选择头像框

As a Lv5 及以上的用户,
I want 在等级页挑选头像框或不戴，升级时一键戴上新框,
So that 我能决定自己怎么被看见。

**范围：** 后端 + App。后端 `PUT /api/v1/me/growth/frame`（AD-16，不可用 → 409 `frame-locked`）；App 等级页头像框一排（C1 ~ C5）、庆祝页「Pakai Bingkai Baru」改为一键戴上（D5 / D6）；埋点 `avatar_frame_changed`。

**Acceptance Criteria:**

**Given** Lv10 用户在等级页
**When** 查看头像框一排
**Then** 显示「不戴」+ 基础 / 升级 / 动效三张卡，基础与升级可选，动效置灰写「Terbuka di Lv15」；灰段位用户四张中只有「不戴」可用（C5）

**Given** 用户点选一个已解锁的框
**When** 请求中 / 成功 / 失败
**Then** 切换中态（C1）→ 成功即生效、头像即时更新（C2）；失败弹回原选择 + toast（C3）；不另设保存按钮；上报 `avatar_frame_changed`（frame_id，「不戴」为 none）

**Given** 用户点未解锁的框
**When** 点击
**Then** 只提示「Terbuka di LvN」（C4），不发请求

**Given** 用户手动选过框后升级
**When** 升级
**Then** 不自动更换；跨段位庆祝页主按钮「Pakai Bingkai Baru」一键戴上新段位框，成功（D5）/ 失败（D6）有反馈

**Given** 直接调用接口设置未解锁的框
**When** 请求
**Then** 返回 409 `frame-locked`，`frame_choice` 不变

**And** 后端接口测试 + App 组件测试绿；Android 模拟器演示 C1 ~ C5、D5。

### Story 3.4: 推荐流作者等级加权

As a Lv15 及以上的用户,
I want 我的帖子在首页推荐流更常被看到,
So that 高等级真正带来曝光权益。

**范围：** 后端 + 后台。AD-15：迁移 `V20261018_1501`（`feed_rank_config.author_level_boost` 默认 1.05，CHECK 1.00 ~ 2.00）；`FeedRecommendationService` 按作者构建系数 Map 传入 `FeedRankEngine.Input`，`FeedRankScorer` 乘法链末尾乘作者系数；算法参数页新增「作者等级加权（Lv15 及以上作者，默认 1.05；1 = 不加权）」插在限流系数之后（K1），沿用原生 diff 确认与 `config_change_logs`。

**Acceptance Criteria:**

**Given** 同条件下 Lv15 与 Lv14 作者各一帖
**When** 推荐流打分
**Then** Lv15 帖分数为 Lv14 的 1.05 倍（AC18）

**Given** 分类 Tab（按时间排序）
**When** 请求
**Then** 排序不受影响（AC18）

**Given** 虚拟号作者展示等级 Lv15
**When** 推荐流打分
**Then** 不吃加权（AC12）

**Given** 运营在算法参数页把加权改为 1.00
**When** 保存（原生 diff 确认）
**Then** 不再加权；改动记入 `config_change_logs`；输入超出 1.00 ~ 2.00 校验失败

**And** Lv15 门槛写死不做参数；推荐流既有测试全绿；`mvn -B clean package` 绿。

## Epic 4: 运营管理成长体系

运营配置规则 / 消费换算 / 等级表（改后自动重算），在用户抽屉查看与调整成长值，批量发放活动 EXP，设置虚拟号展示等级。

### Story 4.1: 成长体系配置页（规则 / 消费换算 / 等级表）

As a 运营,
I want 在后台调整成长值规则、消费换算比例和等级表,
So that 不发版也能调控用户成长节奏。

**范围：** 后台。`/admin/growth`（`AdminPageCatalog` 登记于「⚙️ 配置与安全」、排在算法参数之后）三页签；权限 `GROWTH_CONFIG_VIEW` / `GROWTH_CONFIG_EDIT`（入 `GROUPS`，不预授）；迁移 `V20261018_1505`（`growth_recalc_jobs`）+ AD-12 重算任务（`@Async`）；审计。UI 稿 G1 ~ G7。

**Acceptance Criteria:**

**Given** 有编辑权限的运营在「成长值规则」页签
**When** 打开某条规则的 480 抽屉修改分值 / 上限 / 启停并保存
**Then** 原生 diff 确认后生效，从下一次入账起使用新值、不追溯（G1 / G2）；非法值（负数、上限 < 0）校验失败提示（G3）；不能新增规则代码；`PASSIVE_GROUP`（被动合计上限）可在此修改

**Given** 「消费换算」页签
**When** 修改某支付用途的档位或档位比例
**Then** 保存生效；`PAWCOIN_TOPUP`（不计）与 `GIFT`（走送礼规则）为锁定行不可改（G4）

**Given** 「等级表」页签
**When** 修改门槛或在末尾追加 Lv21 后保存
**Then** 弹服务端确认弹窗，显示改前改后与「升级 N 人 / 降级 N 人」并提示降级会失去段位权益（G5 / G6）；确认后异步重算：升级者写待弹庆祝并发通知，降级不通知，全部变化者发 `GrowthLevelChangedEvent`（AC9）；Lv1 门槛固定 0 不可改；门槛须严格递增

**Given** 只有查看权限
**When** 打开页面
**Then** 输入框禁用，卡底提示「只读：需要「改 EXP 配置」权限才能修改」（G7）

**And** 每次修改写审计（改前改后）；页面显示最近一次重算任务状态；`AdminPageCatalogTest` 等守卫测试绿；`mvn -B clean package` 绿。

### Story 4.2: 用户抽屉「成长值」页签与手动调整

As a 运营,
I want 在用户抽屉里查看某个用户的成长值并手动调整或发放活动 EXP,
So that 能处理客诉、补发漏账、做单个用户的活动奖励。

**范围：** 后台。填充地基已建的 `data-utab-panel="growth"` 容器（不改页签头）；`GROWTH_USER_ADJUST` 预授 OPS_MANAGER（P-5，在 `AdminRole` 枚举加码，`V20261018_1502` 作废）；`GrowthLedgerService.adjust`；`AdminConfirmModal`；审计。UI 稿 I1 ~ I7。

**Acceptance Criteria:**

**Given** 打开真实用户抽屉的「成长值」页签
**When** 加载
**Then** 显示当前等级、累计、距下一级；明细含时间、规则、增减值、关联对象（可点开）、关联宠物，可按规则与时间筛选（I1）；无明细时空态（I2）

**Given** 有 `GROWTH_USER_ADJUST` 的运营
**When** 在行内表单选「普通调整」或「运营活动」，填增减值（可负）与原因 / 活动名后提交
**Then** 弹服务端确认弹窗显示调整后累计与等级变化（I3 / I4）；确认后入账，流水记「运营调整」或 `OPS_CAMPAIGN`（带 `note` 与操作人），照常触发升降级与升级通知（I5）；负向调整使累计 < 0 时拒绝

**Given** 虚拟号的用户抽屉
**When** 打开「成长值」页签
**Then** 只显示展示等级，不显示明细、不能调整（I6）

**Given** 没有 `GROWTH_USER_ADJUST` 的运营
**When** 打开页签
**Then** 调整卡置灰不隐藏，提示「需要「调整用户 EXP」权限」（I7）

**And** 重复提交（同一 grant token）不重复入账；每次调整写审计；`mvn -B clean package` 绿。

### Story 4.3: 批量发放活动 EXP

As a 运营,
I want 一次给一批用户发放活动 EXP,
So that 能做站内活动奖励而不用逐个调整。

**范围：** 后台。成长体系页右上「批量发放」（仅 `GROWTH_USER_ADJUST` 可见）→ 600 抽屉三步条；照 `SeedBatchPublishService` 的预览与不阻塞整批；批次号 `GB-{yyyyMMddHHmm}-{rand}`，幂等键 `BATCH:{batchNo}:{u}`；审计。UI 稿 H1 ~ H5。

**Acceptance Criteria:**

**Given** 运营粘贴或上传 CSV（一列 user_id），填每人 EXP 与活动名
**When** 点「预览」
**Then** 显示有效用户数、无效 ID（不存在 / 已注销 / 已停用）与被排除的虚拟号；校验不过的行不阻塞整批（H1 / H2）

**Given** 预览无误
**When** 点「发放」
**Then** 弹服务端确认弹窗显示人数 × 每人 EXP、合计与批次号（H3）；确认后逐人以 `OPS_CAMPAIGN` 入账并带活动名，成功页显示结果（H4）；失败有失败态（H5）

**Given** 同一批次重复提交
**When** 再次确认
**Then** 按（批次号, 用户）去重，不重复发放

**Given** 没有 `GROWTH_USER_ADJUST`
**When** 打开成长体系页
**Then** 「批量发放」按钮隐藏

**And** 每批写审计（批次号、人数、合计、活动名）；`mvn -B clean package` 绿。

### Story 4.4: 虚拟号展示等级设置

As a 运营,
I want 在「运营发布身份」页看到并修改每个虚拟号的展示等级,
So that 虚拟号在社区里的等级看起来合理。

**范围：** 后台。`/admin/virtual-accounts` 列表加「展示等级」列（J1）；虚拟账号详情抽屉加「展示等级」卡，形态同「账号物种定位」卡：下拉（Lv1 ~ 当前最高级）+ 保存 + 原生确认（J2）；新建表单不加此字段；审计。

**Acceptance Criteria:**

**Given** 运营发布身份列表
**When** 打开
**Then** 每个虚拟号显示当前展示等级（J1）

**Given** 详情抽屉「展示等级」卡
**When** 选择 Lv12 并确认保存
**Then** 该虚拟号在 App 各处的角标与头像框按 Lv12 / GUARDIAN 显示；不产生任何成长值流水、不享受任何权益（AC12）；写审计

**Given** 新建虚拟号
**When** 提交新建表单
**Then** 表单无展示等级字段，系统随机分配 Lv6 ~ 8

**And** 下拉上限随等级表追加而变化；`mvn -B clean package` 绿。

## 最终校验（2026-10-18）

- **覆盖**：FR-143.1 ~ .9、FR-144.1 ~ .5、AB-32.1 ~ .2、AB-33.1 ~ .4、FR-AN、UX-DR1 ~ UX-DR14、PRD AC1 ~ AC20 均至少落在一个 story 的验收标准里。
- **修正 1（前向依赖）**：原 1.2「消费返」的 AC6 依赖原 1.3「等级变化」→ 两者对调：**1.2 = 等级变化与注销，1.3 = 消费返与退款**。
- **修正 2（表按需创建）**：`growth_spend_tiers` 移到 Story 1.3（迁移 `V20261018_1504`），`growth_recalc_jobs` 移到 Story 4.1（迁移 `V20261018_1505`）；架构增量 §6.4 同步。
- **跨 epic 依赖**：只向后（2.3「戴上新框」先跳等级页、3.3 改为一键戴上；1.5 等级页的头像框一排留给 3.3；4.1 重算复用 2.1 的连锁出口；3.1 / 4.4 使用 2.2 初始化的展示等级）。
- **文件交叠**：等级页（1.5 / 3.3）、`GrowthLevelChangeHandler`（1.2 / 2.1）有交叠，属同一组件按序递进，保留拆分（单会话上下文与验收节奏）。
