---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.0/送礼/1-4-0送礼prd.md
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-gift-delta.md
  - _bmad-output/planning-artifacts/v1.4.0/decision-log.md                         # ③ 节
  - _bmad-output/planning-artifacts/v1.4.0/送礼/ui-1-4-0-gift-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.0/送礼/ui-admin-1-4-0-gift-integrated-v1.html
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md
version: v1.4.0
theme: gift
status: complete
updated: '2026-10-18'
---

# petgo-platform V1.4.0 gift - Epic Breakdown

## Overview

V1.4.0 ③「送礼」PRD（FR-148 / FR-149 · AB-35 / AB-36）拆分。分支 `feat/1.4.0-gift`（从 `dev_1.4.0` 切出，与 ①② **并行开发**）。

> 🔴 **口径优先级**：`decision-log.md` ③ 节 > PRD > `architecture-v1.4.0-gift-delta.md`（AD-1 ~ AD-13、P-1 ~ P-3）> UI 稿（定稿）。
> 🔴 **并行约束**：② 的多宠接口、① 的等级页在本分支不存在。取宠物只经自有端口 `GiftPetDirectory`（单宠过渡实现）；专属礼物按地基占位等级（全员 Lv1）判断；Epic 4 对齐 story **不在本功能分支做**，①②③ 均合入 `dev_1.4.0` 后在对齐分支补做。

## Requirements Inventory

### Functional Requirements
- **FR-148.1** 入口：他人宠物 Diary Tab（访客视图）、Diary 详情页礼物区（多宠先选）、礼物页底部；自己的宠物 / Moment / Tips 无入口；只读宠物可收、已删宠物不可；送礼人拉黑主人 → 拦截（单向）；主人封号仍可收、注销不可。
- **FR-148.2** 礼物目录（后台配置）、专属礼物按等级可见可送、未解锁只显示锁区块（下一批最低门槛）、按收礼宠物物种排序。
- **FR-148.3** 流程：面板（默认第一个、×1/×5/×10 + 微调、≤ ×99、总价）→ 选渠道抽屉（依次弹、关闭回面板保留选择）→ PawCoin 当场送达 / QRIS 付款后送达 → 1–2 秒送出动效 + Toast。
- **FR-148.4** 支付：单渠道、QRIS 单笔 ≥ Rp10,000（可配，只对送礼）、余额不足先充值并回原流程、全部为平台收入、送达后不退款。
- **FR-148.5** 成长值：送达发 `GiftSentEvent`（① 入账）。
- **FR-148.6** 我的送礼记录（钱包页入口，只送出，状态：已送达 / 等待付款 / 已退回 PawCoin）；钱包流水按关联类型显示。
- **FR-148.7** 异常：QRIS 离开后付款照常送达；未付过期不送达；付款后无法送达自动退回 PawCoin + 通知、无成长值、后台标「已付款未送达」，退回失败自动重试。
- **FR-149.1** Diary Tab 统计卡中间格 = 收到礼物总价值（主人 / 访客一致），点进礼物页（礼物墙 + 粉丝榜同页）。
- **FR-149.2** 礼物墙按单价降序、同价按数量降序，按订单快照显示；粉丝榜一人一行按累计金额降序、显示累计金额、前三名特殊样式、主人看时隐藏其拉黑的送礼人、注销者显示已注销用户。
- **FR-149.3** Diary 详情页礼物区（关联宠物各自礼物缩略，点进礼物页）。
- **FR-149.4** H5 名片 Konsultasi → Hadiah（总价值，0 显示 Rp0），Tonggak 维持现状。
- **FR-149.5** 收礼通知：同宠物未读即合并、推送每次都发（单笔文案），点击进 Diary Tab 展开礼物墙。
- **AB-35** 礼物目录配置（列表 / 新建编辑抽屉 / 图标校验 / 下线不删 / 改价只影响新单 / QRIS 最低金额配置）+ 权限 + 审计。
- **AB-36** 全站送礼记录（筛选、导出、异常单）+ 抽屉「收送礼记录」（送出 / 收到）+ 只读权限。
- **FR-AN** 埋点 7 个：`gift_panel_opened` / `gift_send_tapped` / `gift_paid` / `gift_qris_blocked_low_amount` / `gift_locked_block_tapped` / `gift_wall_opened` / `gift_sender_list_opened`。

### NonFunctional Requirements
- **NFR1 资金正确**：每笔幂等；回调与轮询只交付一次；退回幂等可重试；不可转让守卫照常绿、钱包服务零改动。
- **NFR2 快照**：礼物下线 / 改名 / 改价不影响已收展示。
- **NFR3 并行**：取宠物只经 `GiftPetDirectory`；不自造 ①② 代码。
- **NFR4 护栏**：不引新依赖 / MQ / 缓存；Flyway 预分配 `V20261018_1700` / `1701`；对外标识用 token。
- **NFR5 本地化**：App 不渲染后端显示串（礼物名按语言取快照 EN / ID 字段）。
- **NFR6 合规**：OQ-1 税务口径、P-1 退回记账须在上线前与法务 / 财务确认（不阻塞开发）。

### UX Design Requirements
A1–A7 入口与选宠物 · B1–B7 送礼面板 · C1–C9 选渠道与支付 · D1–D2 动效与 Toast · E1–E7 礼物页 · F1–F4 送礼记录 · G1–G4 通知与推送 · H1–H2 H5 名片 · I1–I3 图标规范（运营出图，非代码）· L1–L7 后台礼物目录 · M1–M6 后台送礼记录与抽屉。

### FR Coverage Map
FR-148.1 / .3 / .4 → 1.1 / 1.3 · FR-148.2 → 1.1 / 1.3 · FR-148.5 → 1.1 · FR-148.6 → 2.3 · FR-148.7 → 1.1 / 1.2 · FR-149.1 ~ .4 → 2.1 / 2.2 · FR-149.5 → 1.2 / 2.2 · AB-35 → 3.1 · AB-36 → 3.2 · 对齐 → 4.1 · FR-AN → 1.3 / 2.2

## Epic List

### Epic 1: 给别人的宠物送出礼物
用户在他人宠物的 Diary Tab / Diary 详情页选礼物、数量和渠道付款，礼物送达并有动效；付款后无法送达时自动退回并通知。
**FRs covered:** FR-148.1 ~ .5、FR-148.7、FR-149.5（通知）· **迁移:** `V20261018_1700`、`V20261018_1701`

### Epic 2: 礼物被看见
宠物的统计卡、礼物页（墙 + 粉丝榜）、详情页礼物区、H5 名片展示收到的礼物；送礼人在钱包页查看送礼记录。
**FRs covered:** FR-149.1 ~ .4、FR-148.6

### Epic 3: 运营管理礼物
运营配置礼物目录与 QRIS 最低金额，查询全站送礼记录与异常单，在用户抽屉看收送礼记录。
**FRs covered:** AB-35、AB-36

### Epic 4: 与等级线、多宠物线对齐（对齐分支）
①②③ 合入后，取宠物换成多宠实现、统计按宠物、详情多宠礼物区、App 页面合并、专属锁区块跳等级页。

**依赖：** Epic 1 → 2 → 3（功能分支）；Epic 4 在对齐分支，前置 ①②③ 均合入 `dev_1.4.0`。

## Epic 1: 给别人的宠物送出礼物

### Story 1.1: 礼物目录与送礼后端

As a 想给别人宠物送礼的用户,
I want 选礼物和数量后用 PawCoin 或 QRIS 付款、礼物送达,
So that 我能表达对这只宠物的喜爱。

**范围：** 后端。迁移 `V20261018_1700`（四表）、`V20261018_1701`（`gift_qris_min_amount`）；`GiftPetDirectory` + `SinglePetGiftPetDirectory`；`GET /api/v1/gifts/catalog?petId=`（排序、专属过滤、锁区块）；`POST /api/v1/pets/{petId}/gifts`（AD-6 全部校验与幂等）；`GiftDeliveryService`（AD-7，交付、汇总表、`GiftSentEvent`）；`GiftPaidHandler` / `GiftPaymentFailedHandler`；`GiftIdemKeys`。测试数据用迁移外的测试夹具录入礼物（首批 40 个由运营在后台录入）。

**Acceptance Criteria:**

**Given** 用户给自己的宠物送礼
**When** 调用接口
**Then** 422 `gift-own-pet`（AC1 后端）

**Given** A 拉黑了 B
**When** A 给 B 的宠物送礼
**Then** 403 `gift-blocked`；B 拉黑了 A 时 A 仍可送达（AC9 后端）

**Given** Lv1（地基占位）用户
**When** 拉取目录 / 送专属礼物
**Then** 目录不含任何专属礼物、`lockBlock.requiredLevel = 5`；直接送专属礼物 403 `gift-locked`（AC3 后端）

**Given** 给猫送礼
**When** 拉取 Rp1,000 档
**Then** 标「猫」的排最前、通用其次、只标「狗」的最后；给狗时相应调整（AC3a）

**Given** 总价 Rp5,000 选 QRIS
**When** 发起
**Then** 422 `gift-qris-below-min`；Rp10,000 可用 QRIS（AC4 后端）

**Given** PawCoin 送 Rp10,000
**When** 发起成功
**Then** 余额 −10,000（`debit SPEND`），订单 DELIVERED，墙与粉丝榜更新，发布一次 `GiftSentEvent(…, totalAmount = 10000)`（AC5 后端）

**Given** QRIS 送礼，回调与轮询同时到达
**When** 处理
**Then** 只交付一次（AC8）；离开 App 后付款照常交付（AC6）；未付过期 → EXPIRED、无事件（AC7）

**Given** 同一 `requestId` 重复提交
**When** 第二次请求
**Then** 返回同一订单，不重复扣款

**And** `ConsultShopBoundaryTest` 全绿、`PawCoinWalletService` 零改动；数量 0 / 100 被拒；只读宠物可收（AC10）；`mvn -B clean package` 绿。

### Story 1.2: 未送达退回与送礼通知

As a 送礼人 / 收礼宠物的主人,
I want 付款后无法送达时自动退回并告诉我原因；收到礼物时收到通知,
So that 钱不会白花，收礼也有提醒。

**范围：** 后端。AD-8（扩 `INTERACTION_TYPES` 与 `InteractionCopyRenderer`，`GiftNotifyListener`，拉黑屏蔽，未读即合并 `GIFT:PET:{petId}`）；AD-9（退回 `credit(TOPUP, refType = GIFT_REFUND)`、`GIFT_UNDELIVERED_REFUND` 通知、`GiftRefundRetryJob` 每 10 分钟 ≤ 5 次 → `REFUND_MANUAL`）。

**Acceptance Criteria:**

**Given** QRIS 发起后、付款前主人删除了宠物
**When** 付款成功
**Then** 不送达，送礼人钱包 +等额 PawCoin（流水 refType `GIFT_REFUND`），收到说明原因的通知，无 `GiftSentEvent`、无送礼成长值，订单 `UNDELIVERED_REFUNDED`（AC10a）；付款期间送礼人拉黑主人、主人注销同理

**Given** 退回时钱包写入失败
**When** 重试任务运行
**Then** 最多 5 次，成功后补发通知；仍失败置 `REFUND_MANUAL`；退回幂等不重复入账

**Given** 主人未读期间 3 人各送一次
**When** 查看通知中心
**Then** 只有一条合并通知「Andi 等 3 人给 Mochi 送了礼物」，推送收到 3 次（单笔文案）（AC16）；已读后再收到新起一条；多宠物按宠物分开

**Given** 主人拉黑了送礼人
**When** 送礼送达
**Then** 主人不收到通知（AC9）

**And** 既有点赞 / 评论 / 关注通知测试全绿；`mvn -B clean package` 绿。

### Story 1.3: App——送礼入口、面板、支付与动效

As a 想送礼的用户,
I want 在他人宠物页和 Diary 详情页点「送礼」，挑礼物、数量、渠道后送出,
So that 送礼又快又不出错。

**范围：** App。UI 稿 A1、A3、A4、A6（入口与不显示）、A7（多宠选宠物抽屉——本分支单宠过渡：详情页按 `content.petId` 送，A7 在对齐 story 接入）、B1 ~ B7、C1 ~ C9、D1 ~ D2；`PayChannelPicker` 加可选参数（`qrisDisabledReason`、扣款中保持打开、充值 await 回原流程，不传时行为不变）；QRIS `onAborted` 提示；动效 CustomPainter（尊重 `disableAnimations`）；锁区块本分支点击提示「Terbuka di LvN」（对齐 story 改为跳 ① 等级页）；埋点 `gift_panel_opened` / `gift_send_tapped` / `gift_paid` / `gift_qris_blocked_low_amount` / `gift_locked_block_tapped`。

**Acceptance Criteria:**

**Given** 他人宠物的访客 Diary Tab 与他人 Diary 详情页
**When** 打开
**Then** 都有「送礼」按钮；自己的宠物页与 Moment / Tips 详情页没有（AC1）

**Given** 送礼面板
**When** 打开 / 选数量
**Then** 礼物按档分组、默认选中第一个；×1 / ×5 / ×10 与 +/−，上限 ×99（B5）；总价实时「🦴 × 5 = Rp5.000」；加载中 / 失败（B6 / B7）；Lv1–4 底部「Lv5 解锁更多礼物」锁区块（B1）

**Given** 点「送出」
**When** 面板收起、选渠道抽屉弹出（不叠层）
**Then** 总价 < 最低额时 QRIS 置灰 + 提示（C2）；余额不足 PawCoin 置灰 + 先充值（C3 / C4），充值返回后自动重开选渠道抽屉、礼物与数量保留；关闭抽屉回到面板保留选择

**Given** PawCoin 扣款
**When** 进行中 / 成功 / 失败
**Then** 抽屉保持打开主按钮转圈（C5）→ 成功关闭并播放 1–2 秒动效 + Toast「已送给 Mochi 🎁」（D1 / D2）；失败抽屉保留可重试（C8）；被拒（如拉黑 / 自己的宠物）显示 C9

**Given** QRIS
**When** 二维码 / 过期 / 失败
**Then** C6 二维码；过期提示「Pembayaran kedaluwarsa. Coba lagi.」、失败「Pembayaran gagal. Coba lagi.」（C7）

**And** 文案取 arb（EN / ID），礼物名按语言取 EN / ID；`flutter analyze` 与相关测试绿；Android 模拟器演示 B1 / C2 / D1。

## Epic 2: 礼物被看见

### Story 2.1: 礼物展示与统计后端

As a 浏览宠物的用户,
I want 看到这只宠物收到的礼物总价值、礼物墙和粉丝榜,
So that 礼物成为宠物的荣誉展示。

**范围：** 后端。`GET /api/v1/pets/{petId}/gifts`（汇总 + 墙）、`/gifts/supporters?cursor=`（粉丝榜，主人看时隐藏其拉黑者）；`VisitorStats` / `ArchiveStatsResponse` 新增 `giftTotalValue`（保留 `consultCount`）；内容详情（Diary）新增 `giftPets`（经 `GiftPetDirectory.petsOfPost`）；H5 `CardPageController` + `card.html`（Hadiah 列）；Java 金额缩写工具（与 App 同规则同用例）。

**Acceptance Criteria:**

**Given** 宠物收到 3 个 🦴（Rp1.000）与 1 个 🎂（Rp50.000）
**When** 请求统计 / 礼物页
**Then** `giftTotalValue = 53000`；墙为 🎂×1、🦴×3（单价降序）；汇总「4 hadiah dari N pendukung」（AC11）；主人与访客一致

**Given** 粉丝榜
**When** 请求
**Then** 一人一行、按累计金额降序（同额按最近送礼时间倒序）、含累计金额；注销者「已注销用户」（AC12）；B 拉黑 A 后 B 看自己宠物的榜没有 A、其他访客看得到、墙数量含 A 的礼物（AC12a）

**Given** 运营下线某礼物
**When** 请求墙
**Then** 已收到的照常按快照显示（AC17）

**Given** H5 名片
**When** 渲染
**Then** 票根「Momen · Hadiah · Tonggak」，Hadiah 为礼物总价值缩写（0 → Rp0），Tonggak 为 0 时仍按现状隐藏（AC15）

**And** 退回 / 未送达的订单不计入；`mvn -B clean package` 绿。

### Story 2.2: App——统计卡礼物格、礼物页、详情礼物区、通知中心

As a 宠物主人 / 访客,
I want 在 Diary Tab 看到礼物总价值、点进礼物页看礼物墙和粉丝榜、在详情页看到礼物区,
So that 礼物被看见。

**范围：** App。DiaryHeader / `PetInfoCard` 中间格 Consultations → 礼物总价值（主人与访客共用），点击进礼物页；礼物页 E1 ~ E7（墙默认两行可展开、粉丝榜分页、访客底部「送礼」、主人无）；详情页礼物区 A3 ~ A6（缩略点击进礼物页）；通知中心 G1 ~ G4（点收礼通知进 Diary Tab 并展开礼物墙、点退回通知进送礼记录）；App 金额缩写公共工具 `shared/format/idr_compact.dart`；埋点 `gift_wall_opened` / `gift_sender_list_opened`。

**Acceptance Criteria:**

**Given** 宠物收到总价值 Rp53.000
**When** 打开 Diary Tab（主人 / 访客）
**Then** 统计卡为「Diary · Rp53rb · Milestone」，不再有 Consultations（AC11）；点进礼物页汇总卡「Rp53.000」+「4 hadiah dari N pendukung」

**Given** 礼物页
**When** 访客 / 主人 / 无礼物 / 加载中 / 失败 / 加载更多失败
**Then** E1 / E2 / E3 / E4 / E5 / E6 / E7；访客底部固定「送礼」，主人不显示；粉丝榜前三名特殊样式、显示累计金额

**Given** 关联宠物的 Diary 详情页
**When** 查看礼物区
**Then** 显示该宠物收到的礼物缩略与「送礼」；无礼物 A5；点缩略进礼物页（AC14 单宠部分，多宠在对齐 story）

**Given** 通知中心
**When** 收礼（单条 / 合并）与退回通知
**Then** 礼物图标色 #F6A609 与本地化文案；点收礼通知进 Diary Tab 并展开礼物墙，点退回通知进送礼记录（G1 ~ G3）

**And** 金额缩写与后端同规则（Rp188rb、Rp1,5rb、Rp1,2jt，向下截断）；`flutter analyze` 与相关测试绿；Android 模拟器演示 E1 / 统计卡。

### Story 2.3: 我的送礼记录

As a 送礼人,
I want 在钱包页看到我送出的每一笔礼物和状态,
So that 我知道礼物是否送达、退回是否到账。

**范围：** 后端 `GET /api/v1/me/gift-orders?cursor=`；App 钱包页「送礼记录」入口（F1）、列表 F2 ~ F4；钱包流水按 `refType` 显示「Kirim hadiah」/「Hadiah dikembalikan」。

**Acceptance Criteria:**

**Given** 送礼人有 QRIS 等待付款、已送达、已退回各一笔
**When** 打开送礼记录
**Then** 时间倒序，状态分别「等待付款」/「已送达」/「已退回 PawCoin」；付款后刷新变「已送达」（AC10b）；每行时间、宠物头像 + 名字、礼物 × 数量、金额、渠道

**Given** 收礼宠物已被删除
**When** 查看该行
**Then** 显示「该宠物已不存在」、不可跳转；其他行点头像进宠物 Diary Tab

**Given** 钱包流水
**When** 有送礼扣款与退回
**Then** 分别显示「Kirim hadiah」「Hadiah dikembalikan」，其他流水文案不变

**And** 空态 F3、失败 F4；`mvn -B clean package` 与 `flutter analyze` 绿。

## Epic 3: 运营管理礼物

### Story 3.1: 后台礼物目录与 QRIS 最低金额

As a 运营,
I want 在后台管理礼物目录和送礼 QRIS 最低金额,
So that 不发版也能上新、调价、下线礼物。

**范围：** 后台。「⚙️ 配置与安全」组「礼物目录」页（照用户标签页）：列表、新建 / 编辑抽屉、图标复用 `AdminTagIconService`、适用物种多选、普通 / 专属与最低等级、排序、上下线（原生 confirm）、不可物理删除；顶部 QRIS 最低金额（服务端确认弹窗）；权限 `gift.catalog_view` / `gift.catalog_edit`（不预授）；审计。UI 稿 L1 ~ L7。

**Acceptance Criteria:**

**Given** 有编辑权限
**When** 新建礼物（图标 PNG / WebP ≤ 512KB 正方形、EN / ID 名、价格 ≥ 100、物种、类型）
**Then** 保存后出现在送礼面板；图标不合规显示 L4 校验失败；专属礼物必须填最低等级（Lv1 ~ 当前最高级）

**Given** 下线某礼物
**When** 原生确认
**Then** 送礼面板不再出现，已收到的照常展示（AC17），无删除按钮

**Given** 改价
**When** 保存
**Then** 只影响之后的新订单，已发起未付款的 QRIS 订单按原价

**Given** 把 QRIS 最低金额改为 Rp15,000
**When** 服务端确认弹窗确认
**Then** 送礼按新阈值校验，月租等其他付费不受影响；写审计

**Given** 只有查看权限
**When** 打开
**Then** 只读视图（L7）

**And** `AdminPageCatalogTest` 绿；`mvn -B clean package` 绿。

### Story 3.2: 后台送礼记录与抽屉「收送礼记录」

As a 运营,
I want 查询全站送礼记录、异常单，并在用户抽屉看某人收送的礼物,
So that 能处理送礼客诉与退回异常。

**范围：** 后台。「订单」组「送礼记录」页（`gift.order_view`，不预授）：字段、筛选（时间 / 送礼人 / 主人 / 渠道 / 状态）、异常单（`UNDELIVERED_*` / `REFUND_MANUAL`，显示退回结果）、xlsx 导出（`AdminExportWriter`）；填充地基 `data-utab-panel="gifts"`（送出 / 收到两子列表，不改页签头）。UI 稿 M1 ~ M6。只读，不提供改单。

**Acceptance Criteria:**

**Given** 全站送礼记录页
**When** 按条件筛选与导出
**Then** 列表与 xlsx 字段一致（时间、送礼人、收礼宠物、主人、礼物、数量、总价、渠道、状态、payment_intent）；无结果 M3

**Given** 有一笔付款后宠物被删的订单
**When** 筛选异常单
**Then** 显示状态「已付款未送达」与退回结果（已退回 / 待重试 / 需人工）（M2）

**Given** 用户抽屉「收送礼记录」
**When** 打开
**Then** 送出与收到两子列表（M4 / M5），空态 M6

**And** 注销送礼人显示「已注销用户」；`mvn -B clean package` 绿。

## Epic 4: 与等级线、多宠物线对齐（对齐分支）

### Story 4.1: 送礼与 ①② 对齐

As a 多宠物用户 / 高等级用户,
I want 给多宠 Diary 选择送给哪只、每只宠物分开统计礼物、点锁区块进等级页,
So that 送礼与多宠物、等级体系完整衔接。

**范围：** 后端 + App，**对齐分支 `feat/1.4.0-gift-align`，前置 ①②③ 均已合入 `dev_1.4.0`**（不在 `feat/1.4.0-gift` 上做、云端不执行）。`GiftPetDirectory` 换 ② 实现（`ContentPetQuery.petsOf` / `PetQueryService.get`），删除单宠过渡实现；`giftTotalValue` 接 ② 按宠物的统计；详情 `giftPets` 多宠；App：A7 选宠物抽屉接入、DiaryHeader（② 切换器 + 礼物格）与详情页（② 关联宠物胶囊 + 礼物区）合并、礼物墙随宠物切换、锁区块跳 ① 等级页 `/me/growth`。

**Acceptance Criteria:**

**Given** 关联两只宠物的 Diary 详情页
**When** 点「送礼」
**Then** 先选送给哪只（A7）再进面板（AC2）；礼物区分别显示两只宠物的礼物（AC14）

**Given** 多宠物主人切换宠物
**When** 查看 Diary Tab 与礼物页
**Then** 礼物总价值与礼物墙随之切换（AC13）

**Given** Lv4 用户点锁区块
**When** 点击
**Then** 进入 ① 等级页（AC3）；升到 Lv5 后第一批专属礼物出现、锁区块变「Lv15 解锁更多礼物」

**Given** PawCoin 送 Rp10,000
**When** 送达
**Then** 送礼人 +50、主人 +40 成长值（AC5 成长值部分，① 已合入）

**And** 单宠过渡实现已删除；①②③ 各自测试全绿；`mvn -B clean package` 与 `flutter analyze` 绿。

## 最终校验（2026-10-18）
- **覆盖**：FR-148.1 ~ .7、FR-149.1 ~ .5、AB-35、AB-36、FR-AN、PRD AC1 ~ AC17（含 AC3a / AC10a / AC10b / AC12a）均落在至少一个 story；AC2 / AC13 / AC14 多宠部分与 AC3 跳等级页、AC5 成长值数值在 4.1。
- **依赖**：只向后；4.1 在对齐分支。
- **表按需**：1700 / 1701 均在 1.1（目录、订单、汇总同属送礼交付闭环）。
