---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
inputDocuments:
  - _bmad-output/planning-artifacts/v1.3.2/PRD-v1.3.2-batch-a.md
  - _bmad-output/planning-artifacts/v1.3.2/PRD-v1.3.2-batch-a-admin.md
  - _bmad-output/planning-artifacts/v1.3.2/tailsonality-内容设计.md
  - _bmad-output/planning-artifacts/v1.3.2/ui-v1.3.2-batch-a.html
  - _bmad-output/planning-artifacts/v1.3.2/决策日志-batch-a.md
  - _bmad-output/planning-artifacts/v1.3.2/architecture-v1.3.2-batch-a-delta.md
  - _bmad-output/planning-artifacts/v1.3.2/代码核对报告-batch-a.md
  - _bmad-output/planning-artifacts/v1.3.2/宠物身份码护照编码规则.md
  - _bmad-output/planning-artifacts/architecture.md                      # 跨版本基线（不重定向）
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md       # 跨 story 契约（不重定向）
version: v1.3.2
theme: batch-a
status: final
updated: '2026-09-29'
---

# petgo-platform V1.3.2 batch-a - Epic Breakdown

## Overview

本文件把 V1.3.2 batch-a（插画依赖包）的 PRD、后台 PRD、内容设计、UI 稿与架构 delta 拆成可实现的 story。

**范围**：FR-117 Tailsonality / FR-120 宠物护照与登机牌 / FR-112 §8 场所打卡 / FR-111 里程碑徽章视觉 / 后台 AB-18A、AB-18B；App + 后端 + 后台同分支（D-1）。

> 🔴 **口径优先级**：`决策日志-batch-a.md`（D-1~D-14、C-1~C-13）> PRD（App / 后台）> 内容设计 > UI 稿。
> 技术口径以 `architecture-v1.3.2-batch-a-delta.md`（AD-1~AD-20）为准；代码现状以 `代码核对报告-batch-a.md` 为准；跨 story 契约冲突以 `CROSS-STORY-DECISIONS.md` 为准。
> **素材**：插画未到齐，一律先占位开发（D-12）；徽章 / 章 / 场所图映射为空时回落现有渲染（AD-15 / AD-5）。

## Requirements Inventory

### Functional Requirements

沿用母版 FR 编号，子项用小数位细分。

**FR-117 Tailsonality 宠物人格测试**

- **FR-117.1** 入口：聚合页「Kenali Hewanmu」新增 Tailsonality 卡（五卡之一，C-8 顺序）；点卡弹**测试说明贴底抽屉**：海报 + 「对【{pet}】·【品种】进行性格测试」+ 18 题 · 约 3 分钟 + 「Mulai Tes」
- **FR-117.2** 题库按宠物物种自动选（猫 / 狗 / 通用，不问用户）；18 题 = 15 行为题 + 3 图片题；**一页 6 题共 3 页**（每页 5 文字 + 末尾 1 图片），进度「X / 3」；可返回改上一页；4 选 1 无中立项；图片题**图 + 文字标签成对**
- **FR-117.3** 中途返回 = 放弃：二次确认「已答的题不会保存」，确认退出、下次从第 1 题重来（C-5）
- **FR-117.4** 计分：五轴逐轴求和，E/N/J/-H 正分为先写字母、**情绪轴正分为 F**；平局取锚题方向；代号形如 `ENTJ-H`；V-1~V-5 验收用例全过；不做分布校准
- **FR-117.5** 结果生成中过渡页：五条维度进度条逐条点亮，标签用四字母体系（`E / I · Orientasi sosial` 等）
- **FR-117.6** 结果页（免费态）：结果卡 3:4（插画 + 代号 + 角色名 + slogan，**带水印**，点开大图）· 免费摘要 · 配型引流模块（未配型：左 {pet} 四字母 + 右虚线 `?`；已配型：对照缩略 + 档位）· 锁态区（**可见标题、正文遮罩**）· 解锁 CTA「Buka Rp5.000」
- **FR-117.7** 结果解锁 Rp5,000（后台可调，D-2）：解锁内容 = 角色专属深读 → 四段维度解读 → 能量段 + 结果卡无水印；粒度为本次结果；复用 KTP 支付链路（PawCoin / QRIS）；成交价写入购买记录
- **FR-117.8** 挽留弹窗：锁态下点返回且未解锁时弹，卖点是「佩戴」；同一次结果只弹一次
- **FR-117.9** 右上 ⋯ 菜单三项：分享 / 发个帖子炫耀 / 重新测试（C-7）；点卡图 → 大图查看（`ImageLightbox`，未解锁仍带水印）
- **FR-117.10** 重测：免费不限次；点重测前确认弹窗「新结果需要重新解锁，已解锁的这次完整保留」；无配额、无计次
- **FR-117.11** 配型页（独立页，全免费）：4×4 类型选择器（格内只显四字母、**无别名、无跳过**）+ 选中态 + **吸底确认按钮**（未选禁用）→ 配型卡预览（页首）· 逐字母对照 · 档位标签 · 一句总结 · 档位总评 + 16 段逐轴详解（E/I→N/S→T/F→J/P）；主人类型可随时更换、不收费；只比四字母不比后缀
- **FR-117.12** 配型卡：主体 = 双人插画（5 档各一套，C-2）+ 双方代号 + 档位标签 + 最多 2 条差异句（优先级 E/I > T/F > J/P > N/S；4/4 时放总评首句）；**永不带水印**；信息段「{宠物名} × {主人昵称}」，不写 Kamu
- **FR-117.13** 结果卡分享：⋯「分享」→ 结果卡预览页（三段式；信息段宠物名 + 主人昵称；品牌段二维码 → `/get`，「Scan buat unduh 🐾」）→ 出图 → 存相册 / 系统分享；只出 9:16；未解锁带水印
- **FR-117.14** 发个帖子炫耀：结果卡 ⋯、配型页底部主按钮、配型卡预览底部主按钮 → 同一发帖页，预上传卡图 + 预填文案（结果卡 / 配型卡各一句）；配型卡预览另有次按钮「Bagikan ke Story」
- **FR-117.15** 结果列表页「Riwayat Tailsonality」：每条日期 · 代号 + 角色名 · 解锁状态 · 佩戴态；点进结果页；**行内切换佩戴**，同一时刻一条，未解锁行置灰不可点；底部「Tes Ulang」
- **FR-117.16** 佩戴：未解锁不显示任何小标；首次解锁自动佩戴、之后不自动替换；宠物档案与 FR-118 公开主页宠物卡显示 4 字母小标
- **FR-117.17** Diary banner：仅解锁后插入（插画缩略 + 代号 + 角色名 + 测试日期），点击进该次结果页；多次解锁多条并存
- **FR-117.18** 分享奖励：卡一、卡二各算一次，粒度宠物 × 卡类型，带水印卡同样计；重测后不再发；受账号日上限与跨渠道月上限约束
- **FR-117.19** 已解锁结果为购买当时快照（本版本文案随发版固定）
- **FR-117.20** 合规：App / 素材 / 接口 / 埋点不得出现「MBTI」；不显示 16Personalities 角色别名
- **FR-117.21** 第二次引导蒙层：对已看过第一版（KTP 迁移）蒙层的账号，在聚合页高亮 Tailsonality 卡补弹一次，按账号记一次

**FR-112 §8 场所打卡**

- **FR-112.8.1** 场所详情页新增「Check-in」按钮（放距离行下方，不吸底，避开评论输入条）
- **FR-112.8.2** 到场校验 ≤500m（服务端判定，D-7），距离不足提示「Kamu harus berada di lokasi untuk check-in」不显示差距；无定位权限 → 引导弹窗「Izinkan lokasi」→ 系统设置；同一场所每宠物每 WIB 自然日限 1 次，当日已打卡按钮**禁用态**（不隐藏）
- **FR-112.8.3** 打卡成功页 C2（首次，新章）：小尺寸落章轻反馈 + 「Momo dapat cap baru」；底部**左右并排**：次级「Lihat Paspor」+ 主 CTA「Rekam Momen Ini」（更宽实心，D-14）
- **FR-112.8.4** 重复到访 C2b：只给角标 ×N 跳动轻反馈，**不出**「去看护照」、不播整页落章
- **FR-112.8.5** 「顺手记录这一刻」→ 发帖页预选 Diary、帖子与本次打卡关联；帖子详情页内嵌场所条（正文后、评论前），点击进场所详情
- **FR-112.8.6** Diary：只打卡未发帖 → 打卡 banner「📍 场所名 · 日期」；打卡且发帖 → 只展示帖子；关联帖删除后 banner 恢复；场所下架 → banner / 场所条保留，点击「场所不存在」
- **FR-112.8.7** 多宠：打卡与宠物多对多，本版本界面单选（当前宠物），控件预留多选
- **FR-112.8.8** 新建场所必须带照片（D-5；App 端已必填，后台补校验）

**FR-120 宠物护照与登机牌**

- **FR-120.1** 聚合页新增「Paspor Hewan」「Boarding Pass」两卡（C-8 顺序）
- **FR-120.2** 护照号：12 位 `TT`+物种 2+`P`+年 2+序号 5；有 KTP 沿用 KTP 护照号，无则首次进护照页 / 首次打卡时新发（D-6 / AD-6）；12 位须单独占一行的展示位
- **FR-120.3** 集章页三态：空态 B1（空白内页内写「Belum ada cap」，同尺寸同版心，无纵览入口、无翻页箭头，吸底「Cari Tempat」→ 场所列表）· **单章页 B2 = 默认**（一页一枚章，左右翻页，场所名 / 首次日期 / 到访次数，页脚「Cap N / 总章数」，右上 ⊞ 切纵览，吸底「Bagikan」）· **纵览页 B2b**（3×N 网格，右上 ◎ 切回，点章回单章页并停在该页，吸底「Buka versi ini · Rp2.000」）
- **FR-120.4** 章：同一场所章唯一，重复到访角标 ×N；章面 = 场所专属章（后台上传）或按类型 7 款默认章，按原色展示；无收集上限、不出现「x / 12」
- **FR-120.5** 整页落章 B4（仅首次新章，自 C2「Lihat Paspor」进入）：「Cap baru! {场所}」+「N cap terkumpul」→「Lihat Paspor」
- **FR-120.6** 章详情页 B5（独立页）：章面放大 + 场所名 + 首次日期 + 次数 + 所在内页局部 + 地址 + 「Lihat tempat」；场所下架 B6：只把地址与入口换成「Tempat tidak ditemukan」
- **FR-120.7** 护照快照解锁 Rp2,000（后台可调）：发起时冻结当前章；此后新盖章 → 整页重新带水印，含新章须再买；已购版本永久可回看、可重新导出；抽屉文案必须写出「这一版」概念（B7）
- **FR-120.8** 已购版本回看入口两处：护照页「已购版本」+ 我的订单（D-4）
- **FR-120.9** 登机牌列表 B3：该宠物每个打卡过的场所一张卡，逐张标解锁态（Terbuka / Rp1.000）与次数
- **FR-120.10** 登机牌详情：一张完整卡（顶部场所图带 + BOARDING PASS 字段：PASSENGER / BREED / TO / PASSPORT（单独一行）/ DATE / VISITS / SEAT）→ 场所名 → 地址条 → 首次 / 最近到访；未解锁 B3b 带水印 + 「Buka Rp1.000」；已解锁 B3c 无水印 + 「Pamer di postingan」；无照片场所用类型默认图（D-5）；SEAT 为装饰号
- **FR-120.11** 登机牌单张解锁 Rp1,000（后台可调）：粒度宠物 × 场所，再到访不二次收费；抽屉写清「只是这一张」（B7b）
- **FR-120.12** 两类分享卡（护照内页 B8 / 登机牌 B9）：复用三段式，只出 9:16，带下载引导；未解锁带水印
- **FR-120.13** 分享奖励：护照内页、登机牌各算一次（登机牌整体一个卡类型），按宠物
- **FR-120.14** 场所合并：两枚章并成一枚、次数相加；下架：章保留

**FR-111 里程碑徽章视觉（仅视觉）**

- **FR-111.1** 徽章系统替换：逐条专属插画、按语义去重（约 39–40 枚）；六处 + H5 同套：庆祝页大徽章 / KOLEKSI 圆点 / 列表墙 / 列表底抽屉 / Diary 里程碑条 / 通知中心 / H5 `/m`（D-13）
- **FR-111.2** 同一张图等比缩放，不做大小两套资产
- **FR-111.3** 保留清单不动：三种关闭方式、解锁振动、无新解锁不弹、已完成徽章重温、`{name}` 与双语机制、补庆祝与只弹最高级；**不做** L 级强化仪式（D-11）
- **FR-111.4** 新分享的 H5 KOLEKSI 按 code 出图，旧分享保持原样（C-10）

**后台**

- **AB-18A** 定价配置组更名「一次性解锁定价」，四行：KTP 卡高清 / 护照内页（每次快照）/ 登机牌（每张）/ Tailsonality 结果解锁；≥1 不支持 0；「快照 ≥ 登机牌」写进说明与确认弹层、不强校验；改价只影响新购买；审计
- **AB-18B** 场所编辑页「专属章」区：上传 / 预览 / 替换 / 移除；PNG、512×512、≤300KB、透明底；不校验颜色与形状（D-10）；未上传不是错误态；换章对已盖章生效；合并保留方章生效，下架保留素材
- **AB-3M** 分享奖励配置卡新增 Tailsonality、护照两渠道的奖励额与日上限

### NonFunctional Requirements

- **NFR-1 安全 / 隐私**：打卡坐标只用于本次判定，不落库、不进日志、不进埋点；日志禁 PII / 健康 / 令牌 / 签名 URL
- **NFR-2 支付正确性**：幂等（重复回调不重复解锁 / 扣币）；到账永不因业务冲突回滚；重复付款、孤儿付款可在后台查出供人工退款；成交价以购买记录为准
- **NFR-3 D1/D2 级联**：每张新表接入注销 / 删档，个人行为数据物理删除，资金流水保留
- **NFR-4 老客户端兼容**：新时间线类型、新订单类型经能力参数下发，老 App 不受影响
- **NFR-5 对外标识**：全部用不可枚举 token
- **NFR-6 合规**：不出现「MBTI」字样
- **NFR-7 语言**：App 只展示 EN / ID；新文案 EN / ID 由 dev agent 翻译（D-8）；角色名 / slogan 为 Jaksel 原文三语共用
- **NFR-8 交互细节**（UI 稿第三步规格）：按压 scale(0.96)；最小热区 44×44 且不重叠；变动数字等宽（进度、×N、护照号）；图标线重跟随文字、单一图标库；高频交互不加自定义动画
- **NFR-9 素材可替换**：插画以文件为单位替换，替换不改代码；缺失时有回落

### Additional Requirements

（摘自架构 delta，story 须引用对应 AD）

- 三个新 PaymentPurpose + CHECK 全量重建（跨分支取并集）+ purpose 分支处补齐（AD-1）
- `keepsake_purchases` + 单一到账监听器 `KeepsakePaidHandler` + `KeepsakeGranter` 接口；幂等键 `{sku}:{业务行 token}`（AD-1）
- 后端 `TailsonalityCatalog` 编译期常量 + 跨库题号一致性测试；提交格式 `{Qn: 0..3}`（AD-2）
- 打卡表扩列 + 存量回填 + `place_checkin_pets` 按宠物唯一（AD-4）
- 章为聚合不建表；`places.stamp_object_key`（AD-5 / AD-18）
- `pet_passports` 签发规则（AD-6）；快照 hash 唯一定义、登机牌合并在合并事务内改挂（AD-7）
- 订单中心 `includeKeepsake` 能力闸（AD-8）；时间线 / 日详情 / 日历 `supports` 能力参数、访客态不下发（AD-9）
- `content_posts.place_checkin_id` ON DELETE SET NULL（AD-10）；`PublishComposePage.open` 预填参数（AD-11）
- `pricing_config.tailsonality_unlock_price`；扩展既有 pricing 接口（AD-12）
- 两个分享奖励渠道表 + `pawcoin_config` 4 列（AD-13）
- 改造 `share_card_template` / 预览页为通用骨架；`/get` 二维码；`ImageLightbox` 支持内存图（AD-14）
- `MilestoneBadge` 公共组件 + `assets/milestone/` ↔ `static/milestone/` 同步测试 + `milestone_shares.collection_codes`（AD-15）
- `OnboardingMarkKey.TAILSONALITY_ENTRY`（AD-16）；级联测试（AD-17）；后台上传校验（AD-18）；埋点双白名单（AD-19）；MBTI 静态扫描测试（AD-20）
- 会被按设计打破的既有测试须更新断言（代码核对报告 §0）

### UX Design Requirements

（UI 稿 52 屏；图面为占位示意，字段 / 主次 / 水印位置以稿为准）

- **UX-DR1** 聚合页五卡：一行两张共三行（2+2+1，第 5 张整宽），`Row + Expanded + IntrinsicHeight` 等高，不改 `InsightEntryCard`（A1）
- **UX-DR2** 引导蒙层第二次触发：底层为聚合页，挖空高亮 Tailsonality 卡（A2）
- **UX-DR3** 测试说明抽屉：贴底、顶部圆角、半透明遮罩、顶部海报（A3）
- **UX-DR4** 答题页：选中态；最后一题选项区与吸底「Lanjut」留 ≥12px（A4 / A5）
- **UX-DR5** 生成中：中心动效 + 五轴逐条点亮（A7）
- **UX-DR6** 结果页：3:4 卡 + 摘要 + 配型引流 + 锁态区；已解锁态仅一个底部主 CTA「Bagikan」（A8 / A9）
- **UX-DR7** 支付抽屉复用 `qr_payment_sheet` 与 KTP 选渠道抽屉，不新做（A11）
- **UX-DR8** 类型选择器：四字母分列、选中主色 1.5px 描边 + 浅紫底 + 加粗，按钮同时由禁用转可点（A12 / A12b）
- **UX-DR9** 配型结果页顺序：卡（3:4）→ 逐字母对照 → 档位 → 一句总结 → 逐轴详解（A13）
- **UX-DR10** 发帖页按 `publish_compose_page` 真实结构，预填卡图与文案（A16b / C6）
- **UX-DR11** 结果列表单条 / 多条两态（A17 / A18）
- **UX-DR12** 时间线两类新条目复用 banner 类样式、左侧 34px 日期槽，专属底色（A21 / C7）
- **UX-DR13** 公开主页宠物卡小标位（A22）
- **UX-DR14** 护照三屏同尺寸同版心；右上 ⊞ / ◎ 切换；appbar 无 ⋯（B1 / B2 / B2b）
- **UX-DR15** 登机牌卡：场所图是卡内顶部图带，护照号单独一行（B3b / B3c）
- **UX-DR16** 两个付费抽屉文案写死版本 / 单张语义（B7 / B7b）
- **UX-DR17** 打卡按钮三态：可点 / 禁用（当日已打卡）/ 失败提示（C1 / C3 / C4 / C5）
- **UX-DR18** 庆祝页与列表页结构照现状，只换徽章位（D1~D4）；H5 徽章与 App 共用资源（D5）

### FR Coverage Map

| FR | Epic | 说明 |
|---|---|---|
| FR-112.8.1–8.8 | Epic 1 | 打卡全链路、Diary 打卡条目、帖子关联、新建场所必带照片 |
| FR-120.1（护照卡）、FR-120.2–120.6、FR-120.14 | Epic 1 | 护照号、集章三态、落章、章详情、合并与下架 |
| AB-18B | Epic 1 | 场所专属章上传 |
| FR-117.1–117.6、117.10、117.11、117.15（除佩戴）、117.19、117.20、117.21 | Epic 2 | 入口 / 引导 / 答题 / 计分 / 结果免费态 / 配型 / 重测 / 列表 / 合规 |
| FR-117.7、117.8、117.15（佩戴）、117.16、117.17 | Epic 3 | 结果解锁、挽留、佩戴、小标、Diary 测试条目 |
| FR-120.1（登机牌卡）、FR-120.7–120.11 | Epic 3 | 快照、已购版本、登机牌 |
| AB-18A | Epic 3 | 一次性解锁定价 |
| FR-117.9、117.12–117.14、117.18 | Epic 4 | ⋯ 菜单、大图、配型卡、结果卡分享、发帖炫耀、分享奖励 |
| FR-120.12、120.13 | Epic 4 | 护照 / 登机牌分享卡与奖励 |
| AB-3M | Epic 4 | 分享奖励配置 |
| FR-111.1–111.4 | Epic 5 | 徽章组件、六处替换、H5 |

## Epic List

### Epic 1：场所打卡与护照集章
用户在场所 500m 内打卡，每只宠物每场所每天一次；首次到访盖出新章并看整页落章，重复到访章上次数 +1；护照（自动签发护照号）可按单章翻页 / 纵览浏览、看章详情；打卡后可顺手发帖并与打卡关联，Diary 出打卡条目。后台可上传场所专属章，新建场所必带照片。本 Epic 内护照页不出付费 CTA（Epic 3 接入）。
**FRs covered:** FR-112.8.1–8.8、FR-120.1（护照卡）、FR-120.2–120.6、FR-120.14、AB-18B

### Epic 2：Tailsonality 性格测试（免费部分）
用户从聚合页进入，确认对象后答完 18 题，得到代号结果与免费摘要（锁态区可见但暂不可买）；可选自己的类型做配型、重测、在结果列表回看历史；老账号补弹一次入口引导。全程不出现 MBTI 字样。
**FRs covered:** FR-117.1–117.6、117.10、117.11、117.15（除佩戴）、117.19、117.20、117.21

### Epic 3：付费解锁
三类一次性付费（Tailsonality 结果 / 护照快照 / 登机牌单张）共用一套购买与到账链路；解锁后结果看完整解读、宠物可佩戴角色小标（档案与公开主页）、Diary 出测试条目；护照可买当前版本并在「已购版本」回看；登机牌列表 / 详情 / 单张解锁；三类购买进「我的订单」；后台可调三项价格。
**FRs covered:** FR-117.7、117.8、117.15（佩戴）、117.16、117.17、FR-120.1（登机牌卡）、FR-120.7–120.11、AB-18A

### Epic 4：分享与发帖回流
结果卡、配型卡、护照卡、登机牌卡都能出 9:16 分享图（带下载二维码、按解锁态决定水印）或一键发成帖子（预填卡图与文案）；结果卡可看大图；分享可领 PawCoin，后台可配两个新渠道的奖励。
**FRs covered:** FR-117.9、117.12–117.14、117.18、FR-120.12、120.13、AB-3M

### Epic 5：里程碑徽章焕新
所有显示里程碑徽章的地方（庆祝页、KOLEKSI、列表墙、底抽屉、Diary 里程碑条、通知中心、H5 分享页）换成逐条专属插画，素材未到的继续显示原奖杯。
**FRs covered:** FR-111.1–111.4

---

> **全批次通用纪律**（每条 story 都适用）：
> ① 每条 AC 标验证层级 —— **L0** 静态（analyze / test / compile）· **L1** 集成（Docker + DB 真跑）· **L2** 端到端（模拟器 / 真机视觉 / 真实支付）；
> ② 对外 DTO 一改，**后端 record + App data DTO + App mock + 契约 test 四处同步**（CROSS-STORY C5）；
> ③ 新增可点元素 44×44 热区、按压 scale(0.96)、变动数字等宽（NFR-8）；
> ④ 日志 / 埋点禁 PII、健康数据、令牌、签名 URL、**坐标**（NFR-1）；对外标识一律 token（NFR-5）；
> ⑤ Flyway 迁移**时间戳版本号**，打包 `mvn -B clean package`；
> ⑥ 每张新表 / 新列接入注销与删档级联并配测试（AD-17）；
> ⑦ 新文案 EN / ID 两套由 dev agent 翻译落地（D-8），不得出现「MBTI」（AD-20）；
> ⑧ 碰到代码核对报告 §0 列出的既有测试，**更新断言表达新规则**，不删测试。

## Epic 1: 场所打卡与护照集章

用户在场所 500m 内打卡，首次到访盖出新章、重复到访章上次数 +1；护照自动签发，可单章翻页 / 纵览浏览、看章详情；打卡后可顺手发帖，Diary 出打卡条目。后台可上传场所专属章，新建场所必带照片。

> **本 epic 另注**：护照页的付费 CTA 不在本 epic（Epic 3 Story 3.4 接入），本 epic 内**不显示**该按钮，不是占位。

### Story 1.1: 场所详情页打卡与到场校验

**覆盖**：FR-112.8.1 · FR-112.8.2 · FR-112.8.3（页面骨架）· FR-112.8.4 · FR-112.8.7 · NFR-1 · UX-DR17 · AD-4

As a 带着宠物到了一家宠物友好场所的用户,
I want 在场所详情页点一下就能打卡,
So that 这次到访被记下来。

**Acceptance Criteria:**

**Given** 迁移执行
**When** 查看 `place_checkins`
**Then** 已扩列 `public_token` / `origin_place_id` / `visit_date`，**存量行先回填**（`origin_place_id = place_id`、`visit_date` = `checked_at` 的 WIB 日期、随机 token）再加 NOT NULL `[L1]`
**And** 新表 `place_checkin_pets(checkin_id, pet_profile_id, origin_place_id, visit_date)` 带 **UNIQUE(pet_profile_id, origin_place_id, visit_date)** `[L0]`
**And** App 侧新建 `place.domain.PlaceCheckin` 映射，与既有 admin 侧 `AdminPlaceCheckin` 列定义一致 `[L0]`

**Given** `POST /api/v1/places/{token}/checkins` 带精确坐标与 `petIds`
**When** 距场所 ≤500m（haversine，复用 `GeoBox`）、宠物属于本人、今日该宠物在当前场所（含已合并进来的原场所）未打卡、场所 ACTIVE
**Then** 写入打卡行与宠物关联行，返回 `checkinToken`、`isNewStamp`、该场所次数 `[L1]`
**And** 距离不足 / 今日已打卡 / 宠物不属于本人 / 无宠物档案各返回**不同 `type` 的 ProblemDetail**，距离不足**不返回距离值**；场所下架 / 不存在沿用既有 404「场所不存在」（二者刻意不可区分，V1.3.0 1.5 决定）`[L1]`
**And** 并发两次提交只成功一次（DB 唯一约束兜底） `[L1]`
**And** 坐标**不落库、不进日志、不进埋点**，有测试扫描日志与埋点属性 `[L0]`

**Given** 场所详情页
**When** 渲染
**Then** 距离行下方出现「Check-in」按钮（不吸底，吸底评论输入条原样保留）`[L2]`
**And** 无定位权限 → 点按钮弹「Izinkan lokasi」引导，「Buka Pengaturan」跳系统设置，复用既有 `LocationGateway` / `openSettings` `[L2]`
**And** 距离不足 → 提示「Kamu harus berada di lokasi untuk check-in」`[L2]`
**And** 今日已打卡 → 按钮为**禁用态**「Sudah check-in hari ini」（不隐藏），详情接口下发今日是否已打卡 `[L1]`
**And** 本页反向验收仍成立：无收藏、无评分、无营业时间 / 电话、无编辑入口 `[L0]`
**And** 请求体 `petIds` 为数组、界面默认当前唯一宠物且不显示选择器；选择控件按多选形态设计但本版本不渲染（FR-112.8.7）`[L0]`

**Given** 打卡成功
**When** `isNewStamp = true`
**Then** 进 C2 成功页：场所名 · 日期 · 小尺寸落章轻反馈（章面用按类型默认章占位）+「{pet} dapat cap baru」`[L2]`
**And** `isNewStamp = false` 进 C2b：角标 ×N 跳动轻反馈，不出「Lihat Paspor」、不播整页落章 `[L2]`
**And** 两个底部按钮（「Lihat Paspor」「Rekam Momen Ini」）本 story **先不显示**，分别由 1.2 / 1.5 接上 `[L0]`

**Given** 既有契约测试钉着「场所详情 DTO 无打卡字段」与页面注释「没有打卡按钮」
**When** 本 story 合入
**Then** 测试改为钉新字段，注释更新 `[L0]`
**And** 后台打卡计数显示开关 `admin.places.checkin-visible` 在 stag 环境打开，prod 随发版打开（列入上线检查单）`[L0]`
**And** 删档时删除 `place_checkin_pets`，以及删后无关联宠物的 `place_checkins`；配级联测试 `[L1]`

---

### Story 1.2: 护照签发与集章页

**覆盖**：FR-120.1（护照卡）· FR-120.2 · FR-120.3 · FR-120.4 · FR-120.14 · FR-112.8.3（「Lihat Paspor」）· UX-DR1 · UX-DR14 · AD-5 · AD-6

As a 打过卡的用户,
I want 打开宠物护照看到集到的每一枚章,
So that 去过的地方变成一本会长大的收藏。

**Acceptance Criteria:**

**Given** 新表 `pet_passports(pet_profile_id UNIQUE, passport_no UNIQUE, issued_at, source)`
**When** 用户首次打开护照页，或首次打卡
**Then** 按 AD-6 签发：取「本人、`profile_deleted_at IS NULL`、建于当前宠物建档之后、物种段一致」的最早一张 KTP 卡的 `passport_no` 沿用（`source=KTP`），否则经 `CardNumberService.allocatePassportNo` 新发（`source=ISSUED`）`[L1]`
**And** 并发签发只产生一行；**不回写** `id_cards` `[L1]`
**And** 打卡接口响应补上 `passportNo` 与 `stampCount` `[L1]`
**And** 护照号格式 12 位连写（`TT02P2600128`），单测覆盖猫 / 狗 / 其他三种物种码 `[L0]`

**Given** 护照接口
**When** 读取某宠物的章
**Then** 章 = 按当前 `place_id` 聚合的打卡（首次日期 = min(`visit_date`)，次数 = count），**不建章表** `[L1]`
**And** 每枚章带场所 token / 名 / 类型 / 状态 / `stampImageUrl`（本 story 恒为 null）；不下发任何总数分母 `[L0]`
**And** 场所合并后（打卡已改挂保留方）两枚章自动合为一枚、次数相加；场所下架后章仍在 `[L1]`

**Given** 聚合页「Kenali Hewanmu」
**When** 渲染
**Then** 改为一行两张的多行排布（`Row + Expanded + IntrinsicHeight`，不改 `InsightEntryCard`），新增「Paspor Hewan」卡，顺序 KTP / 年龄卡 / 护照 `[L2]`
**And** `pet_insights_test` 的「禁词 + InkWell 恰好 2 个」断言按新卡数更新 `[L0]`

**Given** 护照页
**When** 无章
**Then** B1 空态：空白内页内写「Belum ada cap」+ 引导文案，护照块与有章时同尺寸同版心；无纵览入口、无翻页箭头；吸底「Cari Tempat」→ 场所列表 `[L2]`
**And** 有章时默认 B2 单章页：一页一枚章，左右翻页，场所名 / 首次日期 / 到访次数，页脚「Cap N / 总数」，右上 ⊞ → B2b 纵览（3×N 网格，右上 ◎ 切回，点章回 B2 并停在该页）`[L2]`
**And** 章面：`stampImageUrl` 为空时按 `placeType` 用包内 7 款默认章（素材未到用占位），**原色展示、不着色、不圆形裁切** `[L2]`
**And** 护照号在页眉完整显示、等宽数字；本 story **不显示**付费按钮与「Bagikan」`[L2]`

**Given** 打卡成功页 C2（首次新章）
**When** 渲染
**Then** 出现次级按钮「Lihat Paspor」→ 护照页并定位到新章 `[L2]`
**And** 删档时 `pet_passports` 物理删除，配级联测试 `[L1]`
**And** 埋点 `place_checkin`、`passport_issued`、`passport_stamped` 由服务端发，事件名与属性键登记 `AnalyticsEventGuard` 双白名单 `[L0]`

---

### Story 1.3: 整页落章与章详情页

**覆盖**：FR-120.5 · FR-120.6 · AD-5

As a 刚在新地方打完卡的用户,
I want 看到章「啪」地盖进护照、也能点开任意一枚章看细节,
So that 集章这件事有仪式感，也能回想起那次到访。

**Acceptance Criteria:**

**Given** C2 点「Lihat Paspor」（仅首次新章）
**When** 进入
**Then** 先播 B4 整页落章：「Cap baru! {场所}」+ 落章动效 +「N cap terkumpul」（**无分母**）+「Lihat Paspor」→ 回 B2 停在新章 `[L2]`
**And** 重复到访路径（C2b）**不会**进入 B4 `[L0]`
**And** 动效有静态兜底线索（章面 + 文案），不只靠动画传达 `[L2]`

**Given** B2 单章页点章本体
**When** 进入 B5 章详情（独立页，非抽屉）
**Then** 显示章面放大（与格子同一张图等比放大）+ 场所名 + 首次打卡日期 + 到访次数 + 所在内页局部 + 地址 + 「Lihat tempat」→ 场所详情 `[L2]`
**And** 场所 DELISTED / MERGED 时（B6）：章面、日期、次数照常，地址与入口换成「Tempat tidak ditemukan / Tempat ini sudah tidak terdaftar」，不做整页空态 `[L2]`

---

### Story 1.4: 场所专属章与新建场所必带照片（后台）

**覆盖**：AB-18B · FR-120.4（专属章显示）· FR-112.8.8 · AD-18

As a 运营,
I want 给重点场所上传一枚专属章,
So that 用户在这些地方盖到的是独一无二的章。

**Acceptance Criteria:**

**Given** 后台场所编辑页
**When** 打开
**Then** 新增「专属章」区：未上传显示「使用默认章（{类型}）」（**不是警告**、不阻断保存）；已上传显示预览 +「替换」「移除」`[L2]`
**And** 上传复用 `AdminSeedImageService.upload`，另加校验**只做**：PNG、正方形 512×512、≤300KB、带透明通道；**不校验颜色、不校验形状**；不合规给出具体原因 `[L1]`
**And** 写入新列 `places.stamp_object_key`；替换 / 移除即时生效，经统一审计入口留痕 `[L1]`
**And** 合并、下架**不删** OSS 文件、**不清空**该字段；只有「移除」才清空 `[L1]`

**Given** App 护照 / 章详情 / 成功页
**When** 该场所有专属章
**Then** 接口下发 `stampImageUrl`，App 显示专属章（原色、不着色）；移除后回落默认章 `[L2]`
**And** 换章后已盖出的章也立即显示新章 `[L1]`

**Given** 后台新建场所
**When** 未上传照片提交
**Then** 拒绝并提示「至少上传一张照片」`[L1]`
**And** 后台 i18n 四个语言文件同步新增文案 `[L0]`

---

### Story 1.5: 打卡后顺手发帖

**覆盖**：FR-112.8.3（「Rekam Momen Ini」）· FR-112.8.5 · UX-DR10 · AD-10 · AD-11（`placeCheckinToken` 部分）

As a 刚打完卡的用户,
I want 顺手发一条带这个场所的帖子,
So that 这次出门在 Diary 里有一条真正的记录。

**Acceptance Criteria:**

**Given** 迁移执行
**When** 查看 `content_posts`
**Then** 新增可空 `place_checkin_id`，外键 **ON DELETE SET NULL** `[L0]`

**Given** C2 / C2b
**When** 渲染底部
**Then** C2 为**左右并排**：次级「Lihat Paspor」+ 主 CTA「Rekam Momen Ini 📸」（更宽、实心）；C2b 只有主 CTA `[L2]`
**And** 点主 CTA → `PublishComposePage.open(preset: Diary, placeCheckinToken: …)`，发帖页预选 Diary，文字区后显示场所条 `[L2]`

**Given** 发布请求带 `placeCheckinToken`
**When** 服务端处理
**Then** 校验为**本人**的打卡后写入 `place_checkin_id`；非本人 / 不存在 → 拒绝 `[L1]`
**And** 任何帖子类型都可关联；普通发帖无此字段 `[L1]`
**And** 发帖失败不影响打卡本身；sheet 仍 autoDispose、无草稿 `[L0]`

**Given** 帖子详情页
**When** 帖子有关联打卡
**Then** 正文之后、分隔线之前显示场所条（📍 场所名），点击进场所详情；场所不可用时点击提示「Tempat tidak ditemukan」`[L2]`
**And** 详情 DTO 新增 `checkinPlace {token, name, status}`，四处同步 `[L0]`
**And** 埋点 `place_checkin_post_created`（属性用 place token）`[L0]`

---

### Story 1.6: Diary 打卡条目

**覆盖**：FR-112.8.6 · FR-112.8.7 · NFR-4 · UX-DR12 · AD-9

As a 打过卡的用户,
I want 在宠物的 Diary 里看到「去过哪里」,
So that 成长时间线里也记着一起出门的日子。

**Acceptance Criteria:**

**Given** 时间线、日详情（`/me/day`）、日历月视图三处接口
**When** 新 App 请求
**Then** 三处都支持能力参数 `supports`；未声明 `place_checkin` 时**不下发**新类型（老 App 行为不变）`[L1]`
**And** 前后端 `TimelineItemType` 同名追加 `PLACE_CHECKIN_BANNER`，查询时拼装、不落库 `[L0]`

**Given** 某宠物有一次打卡
**When** 该打卡**没有**「出现在同一次时间线结果里」的关联帖（GROWTH_MOMENT + 同一宠物 + 满足可见性与审核过滤）
**Then** 时间线出打卡条目「📍 {场所} · {日期}」，有效日期与排序键 = `checked_at` 的 UTC 日期 `[L1]`
**And** 有这样的关联帖时只出帖子、不出条目；帖子删除 / 下架 / 转私后条目恢复 `[L1]`
**And** 条目 DTO 带 `{placeToken, name, status}`；场所不可用时点击显示「Tempat tidak ditemukan」`[L2]`

**Given** App 时间线组件
**When** 渲染打卡条目
**Then** 复用 banner 类样式 + 左侧 34px 日期槽 + 专属底色；游客示例时间线与真实时间线复用同一组件 `[L2]`
**And** 访客态（`VisitorProjectionService`）**不下发**打卡条目，有测试钉住 `[L1]`

## Epic 2: Tailsonality 性格测试（免费部分）

用户从聚合页进入，确认对象后答完 18 题，得到代号结果与免费摘要；可做主人配型、重测、在结果列表回看历史；老账号补弹一次入口引导。

> **本 epic 另注**：结果页锁态区在本 epic 内**可见标题、正文遮罩，但不显示购买按钮**（Epic 3 Story 3.2 接入）；⋯ 菜单本 epic 只有「Tes Ulang」一项（分享 / 发帖由 Epic 4 加入）。角色卡与配型卡插画先用已到的设计图占位（D-12）。

### Story 2.1: 题库常量、计分与结果落库

**覆盖**：FR-117.2（题套选择）· FR-117.4 · FR-117.19 · AD-2 · AD-3（结果行）

As a 做完测试的用户,
I want 我的答案被准确地算成一个性格代号,
So that 结果可信、每次同样的答案得到同样的结果。

**Acceptance Criteria:**

**Given** 后端 `TailsonalityCatalog`（编译期常量，照 `MilestoneCatalog`）
**When** 加载
**Then** 含 CAT / DOG / GENERAL 三套 × `Q1..Q15, P1..P3` × 4 个权重，权重逐字取自内容设计 §6.2–§6.5；题号归轴与锚题 Q1/Q4/Q8/Q11/Q14 固定 `[L0]`
**And** 计分：五轴逐轴求和；E/N/J/-H 为先写字母正分，**情绪轴正分为 F**；得分 0 取锚题方向；代号 `ENTJ-H` 形态 `[L0]`
**And** 内容设计 §6.7 验收用例 **V-1~V-5 全部以单测钉死**（V-1 → `ENFJ-H`、V-2 → `ISTP-L`、V-3 → `ISFP-L`、V-4 → `ENTJ-H`、V-5 → 第 4 位 J），且三套题各跑一遍 `[L0]`

**Given** 新表 `tailsonality_results(public_token, pet_profile_id, user_id, question_set, answers JSONB, type_code, energy, content_version, unlocked_at NULL, created_at)`
**When** `POST /api/v1/pet-profiles/me/tailsonality/results` 提交 `{ "Q1": 0, …, "P3": 3 }`
**Then** 服务端按宠物 `PetType` 选题套（CAT→CAT、DOG→DOG、OTHER→GENERAL），计分、落库，返回结果 DTO（token、完整代号、4 字母、能量、`resultIndex`、`createdAt`、`unlocked=false`）`[L1]`
**And** 题号集合不是恰好 18 个、或值不在 0..3 → 422 `[L1]`
**And** `GET …/tailsonality/results`（本宠物历史，按时间倒序）与 `GET …/results/{token}`（非本人 404）可用 `[L1]`
**And** `content_version` 本版本恒为 1；重测 = 再提交一次，无配额 / 计次字段 `[L0]`
**And** 删档时物理删除结果行，配级联测试 `[L1]`

---

### Story 2.2: 测试内容表（EN / ID）与合规扫描

**覆盖**：FR-117.2（文案）· FR-117.11（配型文案）· FR-117.20 · NFR-6 · NFR-7 · AD-2 · AD-20

As a 印尼或英语用户,
I want 题目和结果都用我的语言、读起来自然,
So that 测试不像机翻、愿意做完并分享。

**Acceptance Criteria:**

**Given** App 内容表（Dart 常量，按 `题套 + 题号` / 4 字母代号寻址，EN / ID 两套）
**When** 落地
**Then** 包含：三套题干与选项（含 3 道图片题的**图 + 文字标签**）、16 角色名与 slogan（Jaksel 原文三语共用、不翻译）、16 段免费摘要、8 段维度解读、16 段角色专属深读、2 段能量段、5 档总评 + 5 档总结句、8 条差异句、16 段逐轴详解、挽留弹窗与重测确认文案 `[L0]`
**And** EN / ID 由 dev agent 依内容设计中文版翻译，口吻与内容设计已给出的 EN / ID 样例一致（年轻、口语、印尼语可混 Jaksel），`{pet}` 占位保留 `[L0]`
**And** 能量后缀展示为「High energy / Low energy」「Energi tinggi / Energi rendah」 `[L0]`

**Given** 跨库测试
**When** 运行
**Then** 钉「后端 `TailsonalityCatalog` 题号集合 = App 内容表题号集合，三套每题恰好 4 选项」，16 个代号两端一致 `[L0]`

**Given** 合规静态扫描测试
**When** 运行
**Then** ARB、Dart 内容表、API 路径与 DTO 字段、埋点事件与属性、`assets/` 文件名中**不出现 mbti（不区分大小写）** `[L0]`
**And** 内容表中无 16Personalities 角色别名（Architect / Mediator 等 16 个英文别名）`[L0]`

---

### Story 2.3: 入口、说明抽屉与答题

**覆盖**：FR-117.1 · FR-117.2 · FR-117.3 · FR-117.5 · UX-DR1 · UX-DR3 · UX-DR4 · UX-DR5 · AD-2

As a 想了解自家毛孩子性格的用户,
I want 从档案里点进去就能开始测,
So that 三分钟左右就能知道结果。

**Acceptance Criteria:**

**Given** 聚合页
**When** 渲染
**Then** 新增 Tailsonality 卡，顺序 KTP / 年龄卡 / 护照 / Tailsonality（2+2 排布）`[L2]`
**And** 该宠物**无结果**时点卡 → 弹测试说明**贴底抽屉**：顶部海报（`性格测试底部抽屉海报.png`）+「Tes kepribadian {pet} · {品种}」+「18 pertanyaan · ± 3 menit」+「Mulai Tes」；**有结果**时点卡 → 结果列表页（Story 2.6；在 2.6 合入前暂进最近一次结果页）`[L2]`

**Given** 答题页
**When** 进行中
**Then** 3 页：第 1 页 Q1–Q5 + P1、第 2 页 Q6–Q10 + P2、第 3 页 Q11–Q15 + P3；进度「X / 3」；每题 4 选 1 有选中态；图片题图 + 文字标签成对，素材未到用占位 `[L2]`
**And** 本页题目全部作答前「Lanjut」禁用；最后一题选项区与吸底按钮留 ≥12px；可返回上一页改答 `[L2]`
**And** 第 1 页点返回 → 弹「Keluar dari tes? / Jawaban yang udah kamu isi nggak akan disimpan.」，确认即退出、**不保存任何进度** `[L2]`
**And** 题干中的 `{pet}` 替换为宠物名 `[L2]`

**Given** 第 3 页点完成
**When** 提交
**Then** 显示生成中过渡页：中心动效 + 五条维度进度条逐条点亮（`E / I · Orientasi sosial` / `N / S · Cara menjelajah` / `T / F · Reaksi emosi` / `J / P · Gaya hidup` / `Tingkat energi`）→ 结果页 `[L2]`
**And** 提交失败保留答案并给重试 `[L2]`
**And** 埋点 `tailsonality_started`（进答题页）/ `tailsonality_completed`（`role_code` 完整代号）`[L0]`

---

### Story 2.4: 结果页（免费态）与重测

**覆盖**：FR-117.6 · FR-117.10 · UX-DR6 · AD-3

As a 刚测完的用户,
I want 看到它是哪种性格、以及还能解锁什么,
So that 我对结果有兴趣、愿意继续看下去。

**Acceptance Criteria:**

**Given** 结果页（未解锁）
**When** 渲染
**Then** 自上而下：结果卡 3:4（插画 + 代号 + 角色名 + slogan，**带水印**，复用 `card_watermark`）· 免费摘要 · 配型引流模块（本 story 为「{4 字母} ↔ ? / Seberapa mirip kalian?」静态卡，点击进配型页由 2.5 接上）· 锁态区（「Analisis lengkap」等**标题可见、正文遮罩**，不是空白 + 按钮）`[L2]`
**And** 本 epic 内锁态区**不显示**购买按钮 `[L0]`
**And** 角色卡插画按 4 字母取包内素材（先用已到的 16 张设计图占位）`[L2]`

**Given** 右上 ⋯
**When** 点击
**Then** 本 epic 内只有「Tes Ulang」一项 `[L2]`
**And** 点「Tes Ulang」先弹确认「Tes ulang? / Hasil barunya perlu di-unlock lagi. Yang udah kamu unlock tetap tersimpan.」，确认 → 说明抽屉 → 答题；重测免费、无次数提示 `[L2]`
**And** 埋点 `tailsonality_retake_confirmed` `[L0]`

---

### Story 2.5: 主人配型页

**覆盖**：FR-117.11 · FR-117.12（页内部分）· UX-DR8 · UX-DR9 · AD-3

As a 知道自己四字母类型的主人,
I want 看看我和我家毛孩子有多像,
So that 更懂彼此的相处方式（顺便有个好玩的东西能晒）。

**Acceptance Criteria:**

**Given** 新表 `tailsonality_owner_types(user_id PK, type_code)`
**When** `PUT /api/v1/me/tailsonality/owner-type` 提交 16 种之一
**Then** 保存 / 覆盖；非法值 422；读取随结果 DTO 或独立 GET 下发 `[L1]`
**And** 注销时物理删除，配级联测试 `[L1]`

**Given** 结果页配型引流模块
**When** 点击
**Then** 进配型页（独立页）；未设类型时先显示 4×4 选择器：格内只显四字母、分列排布，**无别名、无「跳过」**；选中态主色 1.5px 描边 + 浅紫底 + 加粗，**吸底「Lihat Hasil」同时由禁用转可点** `[L2]`
**And** 点确认才出结果（不选中即跳）`[L2]`

**Given** 已设类型
**When** 显示配型结果
**Then** 顺序：配型卡（3:4，双人插画按档位取占位图，**无水印**）→ 逐字母对照（两行对齐、相同位打勾）→ 档位标签 → 一句总结 → 档位总评 + 四段逐轴详解（E/I→N/S→T/F→J/P）`[L2]`
**And** 档位按相同字母数（只比四字母、不比后缀）：4 Literally Twins … 0 Total Opposite；差异句取法与优先级 E/I > T/F > J/P > N/S 以单测钉住 `[L0]`
**And** 可随时换类型、不收费；返回结果页后引流模块显示对照缩略 + 档位 `[L2]`
**And** 本 story 不含「Pamer di postingan / Bagikan ke Story」（Epic 4）`[L0]`
**And** 埋点 `tailsonality_match_entered` / `tailsonality_owner_type_set`（`owner_type`、`pet_type`、`match_level`）`[L0]`

---

### Story 2.6: 结果列表页

**覆盖**：FR-117.15（除佩戴）· UX-DR11

As a 测过好几次的用户,
I want 看到这只宠物所有的测试结果,
So that 可以回看以前的结果、也能从这里重测。

**Acceptance Criteria:**

**Given** 「Riwayat Tailsonality」页
**When** 该宠物有结果
**Then** 每条显示代号（4 字母 + 后缀）· 角色名 · 测试日期 · 解锁状态（未解锁「Belum dibuka」置灰）；点击进该次结果页；底部「Tes Ulang」（同 2.4 确认弹窗）`[L2]`
**And** 聚合页 Tailsonality 卡在有结果时进本页（替换 2.3 的临时去向）`[L2]`
**And** 本 story 不含佩戴切换（Epic 3 Story 3.3 加在行内）`[L0]`

---

### Story 2.7: 老账号的第二次入口引导

**覆盖**：FR-117.21 · UX-DR2 · AD-16

As a 之前看过「KTP 搬家了」引导的老用户,
I want 被告知性格测试也在这里,
So that 我不会错过这个新功能。

**Acceptance Criteria:**

**Given** `OnboardingMarkKey` 追加 `TAILSONALITY_ENTRY`（wire `tailsonality_entry`）
**When** 账号无 `tailsonality_entry` 标记（老账号，以及本版本新账号——D-17），进入聚合页，且本会话未刚弹过第一次引导
**Then** 盖一层蒙层，挖空高亮 Tailsonality 卡，文案「Tes kepribadian anabulmu ada di sini / Ketuk buat mulai」+「Oke, ngerti」`[L2]`
**And** 关闭后记 `tailsonality_entry`，按账号只弹一次；两次引导互不影响 `[L1]`
**And** 本会话刚看完第一次引导的新账号，本次不弹、下次冷启动再弹（不连弹两层）`[L1]`
**And** `OnboardingMarkTest` 中「只有 1 个 key」「`tailsonality_intro` 必须被拒」两条断言按新规则更新 `[L0]`

## Epic 3: 付费解锁

三类一次性付费（Tailsonality 结果 / 护照快照 / 登机牌单张）共用一套购买与到账链路；解锁后看完整解读、佩戴角色小标、Diary 出测试条目；护照可买当前版本并回看已购版本；登机牌按张解锁；三类购买进「我的订单」；后台可调三项价格。

> **本 epic 另注**：支付是资金攸关路径，AD-1 的每一条（单一监听器、幂等键粒度、`grant` 不抛异常、同一业务行只一笔 PENDING）都要有测试。真实 QRIS 付款属 L2，在 stag 用测试渠道验收。

### Story 3.1: 一次性解锁的购买链路与后台定价

**覆盖**：AB-18A · NFR-2 · AD-1 · AD-12 · AD-17（购买流水）

As a 运营,
I want 在后台设置三项新付费的价格，并且用户付的每一笔都有据可查,
So that 上线即可调价，对账不出错。

**Acceptance Criteria:**

**Given** 迁移
**When** 执行
**Then** `PaymentPurpose` 末尾追加 `TAILSONALITY` / `PASSPORT_SNAP` / `BOARDING_PASS`；`ck_payment_intents_purpose` **DROP + ADD 全量重建**，值 = 全部活跃分支该约束最新值的**并集** + 三项，PR 描述列出全集 `[L0]`
**And** `PaymentDisplayNo`、`GemPayGateway.safeDescription`、后台 i18n `admin.payments.purpose.*`（四语）、仪表盘付费用户指标、`AdminPaymentQueryService` 均补齐三项 `[L0]`
**And** 新表 `keepsake_purchases(sku, ref_id, pet_profile_id NULL, user_id, price_idr, pay_channel, payment_intent_id UNIQUE NULL, status, created_at, paid_at)`，status ∈ PENDING / PAID / CANCELED / EXPIRED / DUPLICATE_PAID / ORPHAN_PAID `[L0]`
**And** `pricing_config` 加 `tailsonality_unlock_price BIGINT NOT NULL`（初值 5000，CHECK ≥1）；护照两列只更新 COMMENT、不改值 `[L1]`

**Given** purchase 包
**When** 提供发起购买能力
**Then** `KeepsakeGranter` 接口（`grant(refId, purchaseId)`）定义在 purchase 包，由各 SKU 模块实现 `[L0]`
**And** 幂等键一律 `{sku}:{业务行 public_token}`，PawCoin `debit` 与 QRIS `createIntent` 同用；**禁止**以 petId / userId 为键（有测试：同宠物两次购买不同业务行都真实扣款）`[L1]`
**And** 同一业务行已有 PENDING → 复用该笔；业务行已解锁 → ProblemDetail 拒绝 `[L1]`
**And** `price_idr` = 实际扣款额（QRIS 取 `intent.amount`，PawCoin 取扣币额）`[L1]`
**And** MIXED 渠道显式拒绝 `[L1]`

**Given** `PaymentIntentPaidEvent`
**When** 三类 purpose 到账
**Then** **只有** `KeepsakePaidHandler` 一个监听器消费（同步 + `Propagation.MANDATORY`），置购买行 PAID 后同步调 `grant` `[L1]`
**And** `grant` 不抛异常：业务行已解锁 → `DUPLICATE_PAID`；业务行不存在 → `ORPHAN_PAID`；到账状态永不回滚（有测试：模拟重复到账、删档后到账）`[L1]`

**Given** 后台运营配置页
**When** 打开定价组
**Then** 组名改为「一次性解锁定价」，四行：KTP 卡高清 / 护照 · 护照内页（每次快照）/ 护照 · 登机牌（每张）/ Tailsonality · 结果解锁；每卡自带保存 + 高危确认复述新旧值 `[L2]`
**And** 校验 1..1e8，**不支持 0**；「快照价 ≥ 登机牌价」写进卡片说明与确认弹层，**不强校验** `[L1]`
**And** 改价写审计日志；只影响新发起的购买 `[L1]`
**And** 既有 `GET /api/v1/pet-profiles/me/id-cards/pricing` 响应加 `tailsonality` 字段，三项价格统一从此读 `[L1]`

**Given** 删档 / 注销
**When** 执行
**Then** `keepsake_purchases` **保留**：删档置空 `pet_profile_id`，注销随既有口径（users 匿名化、流水不删）；配测试 `[L1]`
**And** 上线检查单写入：prod 护照两价由运营改为 2000 / 1000 `[L0]`

---

### Story 3.2: Tailsonality 结果解锁与挽留弹窗

**覆盖**：FR-117.7 · FR-117.8 · UX-DR7 · AD-1 · AD-3

As a 想看完整解读的用户,
I want 花 Rp5.000 解锁这次结果,
So that 读到只属于我家毛孩子的深度解读、拿到无水印的卡。

**Acceptance Criteria:**

**Given** 结果页锁态区
**When** 未解锁
**Then** 显示「Buka Rp{价格}」（价格读接口，**不硬编码**）→ 复用 KTP 选渠道抽屉（PawCoin / QRIS）→ QRIS 走既有 `qr_payment_sheet`，不新做支付界面 `[L2]`
**And** PawCoin 余额不足沿用既有提示与充值入口 `[L2]`

**Given** 付款成功
**When** tailsonality 的 `grant` 执行
**Then** 结果行置 `unlocked_at`；结果页刷新为已解锁态：角色专属深读 → 四段维度解读（按字母拼装）→ 能量段；结果卡去水印；底部仅一个主 CTA 位（「Bagikan」由 Epic 4 接上，本 story 先不显示）`[L2]`
**And** 重测产生的新结果为锁态，已解锁的旧结果不受影响 `[L1]`

**Given** 锁态下点返回
**When** 本次结果未解锁、且本机未对该结果弹过
**Then** 弹「Yakin keluar? / Kalau di-unlock, badge kepribadian {pet} bisa dipasang di profilnya…」+「Nanti aja」「Unlock」`[L2]`
**And** **客户端本地按结果 token 记录**，同一结果只弹一次 `[L0]`
**And** 埋点 `tailsonality_unlock_viewed` / `_unlock_initiated`（App，`role_code`、`price`、`result_index`）、`tailsonality_unlocked`（**服务端**，登记双白名单）、`tailsonality_paywall_abandoned` `[L0]`

---

### Story 3.3: 佩戴角色小标与 Diary 测试条目

**覆盖**：FR-117.15（佩戴）· FR-117.16 · FR-117.17 · UX-DR11 · UX-DR12 · UX-DR13 · AD-3 · AD-9

As a 解锁过结果的用户,
I want 把性格标签挂在宠物档案上、在 Diary 里留下这次测试,
So that 来看主页的人都能看到，我也能回顾。

**Acceptance Criteria:**

**Given** 新表 `tailsonality_badges(pet_profile_id PK, result_id)`
**When** 某宠物**首次**有结果解锁
**Then** `grant` 内 `INSERT … ON CONFLICT DO NOTHING` 自动佩戴；之后再解锁**不替换** `[L1]`
**And** 只能佩戴已解锁结果（接口校验）；可卸下，卸下后再解锁不自动戴回（D-16）`[L1]`

**Given** 结果列表页
**When** 渲染
**Then** 已解锁行内显示「Dipakai」/「Pakai ini」切换，同一时刻只一条佩戴，点另一条即替换；未解锁行控件置灰不可点 `[L2]`
**And** 埋点 `tailsonality_badge_equipped`（`result_index`）`[L0]`

**Given** 本人宠物档案与 FR-118 公开主页宠物卡
**When** 佩戴行存在且所指结果已解锁
**Then** 两处 DTO 下发 `tailsonalityBadge`（4 字母），宠物卡显示小标；否则 null、不显示 `[L1]`
**And** `public_profile_pet_test` 的「findsNothing」断言按新规则更新；`public_profile_posts_test` 反向清单仍通过（实现标识符避开被禁词）`[L0]`

**Given** Diary 时间线（`supports` 含 `tailsonality`）
**When** 该宠物有已解锁结果
**Then** 出 `TAILSONALITY_BANNER`：插画缩略 + 代号 + 角色名 + 测试日期；有效日期与排序键 = `unlocked_at`；点击进该次结果页；多次解锁多条并存 `[L1]`
**And** 未声明能力的老 App、访客态均不下发 `[L1]`
**And** 删档时 `tailsonality_badges` 物理删除，配级联测试 `[L1]`

---

### Story 3.4: 护照快照解锁与已购版本

**覆盖**：FR-120.7 · FR-120.8 · UX-DR16 · AD-7

As a 集了一些章的用户,
I want 买下「现在这一版」的无水印护照,
So that 我有一份干净的纪念，以后集了新章还能再买新版。

**Acceptance Criteria:**

**Given** 新表 `passport_snapshots(public_token, pet_profile_id, stamps JSONB, stamp_count, place_set_hash, paid_at NULL, created_at)`
**When** 用户发起快照购买
**Then** **发起时冻结**当前章列表（场所 token / 名 / 类型 / 首次日期 / 次数）`[L1]`
**And** `place_set_hash` 按 AD-7 唯一定义（沿 `merged_into_id` 解析到最终场所、排序后的 `place_id` 做 sha256；DELISTED 计入），购买与读取调用同一函数现算 `[L0]`
**And** 当前章集合 hash 与某个已付快照相同 → 拒绝再买 `[L1]`

**Given** 护照页
**When** 当前版本未买
**Then** 纵览页 B2b 吸底「Buka versi ini · Rp{价格}」→ B7 抽屉「Buka paspor versi ini / {N} cap sekarang · Rp{价格} / Nanti dapat cap baru? Versi barunya dibuka terpisah.」→ 支付 `[L2]`
**And** 单章页 B2 不出付费按钮 `[L0]`
**And** 买后当前护照无水印；**再盖一枚新章 → 整页重新带水印**；次数变化不算新版本 `[L1]`
**And** 场所合并后，已买版本仍判为当前版本（有测试）`[L1]`

**Given** 护照页右上「已购版本」
**When** 有已付快照
**Then** 列出每个已买版本（购买日期 · 章数），点进按快照 JSON 回看该版本（无水印），不读实时数据 `[L2]`
**And** 列表只含 PAID `[L1]`
**And** 删档时快照物理删除，配级联测试 `[L1]`

---

### Story 3.5: 登机牌列表、详情与单张解锁

**覆盖**：FR-120.1（登机牌卡）· FR-120.9 · FR-120.10 · FR-120.11 · UX-DR15 · UX-DR16 · AD-5 · AD-7

As a 去过好几个地方的用户,
I want 每个去过的地方都有一张登机牌,
So that 每一趟出门都像一张可以收藏的票。

**Acceptance Criteria:**

**Given** 聚合页
**When** 渲染
**Then** 新增第 5 张「Boarding Pass」卡，单独一行整宽（2+2+1）`[L2]`

**Given** 新表 `boarding_pass_unlocks(pet_profile_id, place_id, unlocked_at NULL, superseded_at NULL, created_at)` UNIQUE(pet_profile_id, place_id)
**When** 读登机牌列表
**Then** 该宠物每个打卡过的场所一张卡：场所名 · 宠物名 · 护照号 · 最近日期 · 次数 · 解锁态（「Terbuka」/「Rp{价格}」）`[L1]`

**Given** 登机牌详情
**When** 渲染
**Then** 一张完整卡：顶部场所图带（`placeImageUrl`；为空按 `placeType` 用包内 7 张默认场所图）+ BOARDING PASS 字段 PASSENGER / BREED / TO / **PASSPORT 单独一行** / DATE / VISITS / SEAT → 场所名 → 地址条 → 首次 / 最近到访 `[L2]`
**And** SEAT 为服务端按 `(pet, place)` 确定性生成的装饰号，同一张卡每次一致 `[L0]`
**And** 未解锁 B3b：卡带水印，底部「Buka Rp{价格}」→ B7b 抽屉「Buka boarding pass ini / {场所} · Rp{价格} / Cuma kartu tempat ini. Kartu tempat lain dibuka terpisah.」`[L2]`
**And** 已解锁 B3c：无水印；该场所再到访只更新次数、不再收费 `[L1]`
**And** 场所下架：卡仍在，地址与跳转换成「Tempat tidak ditemukan」`[L2]`

**Given** 后台合并场所
**When** `PlaceMergeService` 执行
**Then** **同一事务内**调用 passport 包 `reassignForMerge`：被合并方解锁行改挂保留方；保留方已有 → 被合并方置 `superseded_at`，后台可筛出供人工退款 `[L1]`
**And** 删档时解锁行物理删除，配级联测试 `[L1]`

---

### Story 3.6: 我的订单与后台支付查询接入

**覆盖**：FR-120.8（订单入口）· NFR-2 · NFR-4 · AD-8

As a 买过东西的用户 / 处理售后的运营,
I want 在「我的订单」和后台支付列表里查到这三类购买,
So that 用户能回到买过的东西，运营能处理重复付款。

**Acceptance Criteria:**

**Given** `OrderType` 末尾追加 `TAILSONALITY` / `PASSPORT_SNAP` / `BOARDING_PASS`
**When** 新 App 带 `includeKeepsake` 请求订单列表
**Then** `OrderCenterService` 从 `keepsake_purchases` 聚合 PAID 及退款态，新 RANK 常量；老 App 不带参数则不下发 `[L1]`
**And** 前端按类型本地化标题与图标；详情点击分别进结果页 / 已购快照 / 登机牌 `[L2]`

**Given** 后台支付查询
**When** 运营筛选
**Then** 可查出 `DUPLICATE_PAID`、`ORPHAN_PAID` 购买与 `superseded_at` 非空的登机牌解锁，走既有人工退款流程 `[L1]`

## Epic 4: 分享与发帖回流

结果卡、配型卡、护照卡、登机牌卡都能出 9:16 分享图（带下载二维码、按解锁态决定水印）或一键发成帖子；结果卡可看大图；分享可领 PawCoin，后台可配两个新渠道的奖励。

> **本 epic 另注**：Story 4.1 改造既有内容分享卡的模板与预览页，**帖子分享卡的现有行为必须一丝不变**（既有测试全绿即为验收）。

### Story 4.1: 通用分享卡骨架与结果卡分享

**覆盖**：FR-117.9 · FR-117.13 · AD-14

As a 测出有趣结果的用户,
I want 把结果卡存成图片或分享到 Story,
So that 朋友也能看到我家毛孩子是什么性格（顺便知道去哪下载）。

**Acceptance Criteria:**

**Given** `share_card_template.dart` 与 `share_card_preview_page.dart`
**When** 改造
**Then** 模板变为「主体区插槽 + 信息区 + 固定 15% 品牌段」通用骨架，帖子分享卡作为其一种用法，**输出像素级不变**（既有分享卡测试全绿）`[L0]`
**And** 预览页新增参数：可关闭 9:16 / 1:1 切换、接受通用卡；帖子卡默认行为不变 `[L0]`
**And** `CardRenderPipeline` / `CardExport` / `card_watermark` 零改动 `[L0]`
**And** `card_link.dart` 重建 `petDownloadUrl` → `GET /get`，本批次卡二维码均用它 `[L0]`

**Given** 结果页右上 ⋯
**When** 点击
**Then** 菜单变为三项：「Bagikan」/「Pamer di postingan」（由 4.4 接上，本 story 先不显示）/「Tes Ulang」`[L2]`
**And** 「Bagikan」→ 结果卡预览页「Pratinjau Kartu」：主体段角色插画铺满（`BoxFit.cover`）· 信息段宠物名 + 主人昵称 · 品牌段字标 +「Scan buat unduh 🐾」+ 下载二维码；**只出 9:16**、无比例切换 `[L2]`
**And** 未解锁带水印、已解锁无水印（水印按服务端解锁态）；吸底「Bagikan ke Story」→ 出图 → `CardExport.showSheet`（存相册 / 系统分享）`[L2]`
**And** 已解锁结果页底部主 CTA「Bagikan」进同一预览页 `[L2]`

**Given** 结果页点卡图
**When** 打开大图
**Then** 复用 `ImageLightbox`，扩展支持内存图片（`Uint8List`）来源；深色背景、双指缩放、下滑关闭；未解锁大图仍带水印 `[L2]`
**And** `ImageLightbox` 既有 URL 用法不变 `[L0]`
**And** 埋点 `tailsonality_card_shared`（`role_code`、`is_unlocked`）`[L0]`

---

### Story 4.2: 配型卡分享

**覆盖**：FR-117.12 · FR-117.13（配型卡）· AD-14

As a 做完配型的主人,
I want 把「我和它有多像」的卡分享出去,
So that 朋友看了也想测测自己和宠物。

**Acceptance Criteria:**

**Given** 配型页
**When** 点配型卡
**Then** 进配型卡预览：主体段双人插画（按档位取素材）· 信息段「{pet} × {主人昵称}」（**不写 Kamu**）+ 双方代号 + 档位标签 + 最多 2 条差异句（4/4 时放总评首句）· 品牌段同 4.1 `[L2]`
**And** **永不带水印**，与结果是否解锁无关（有测试）`[L0]`
**And** 吸底两个堆叠按钮：主「Pamer di postingan」（由 4.4 接上，本 story 先不显示）+ 次「Bagikan ke Story」→ 出图 → 存相册 / 分享；只出 9:16 `[L2]`
**And** 埋点 `tailsonality_match_card_shared`（`owner_type`、`pet_type`、`match_level`）`[L0]`

---

### Story 4.3: 护照卡与登机牌卡分享

**覆盖**：FR-120.12 · FR-120.7（已购版本重新导出）· AD-14

As a 集了章的用户,
I want 把护照或某张登机牌晒出去,
So that 朋友看到我们去过哪些地方。

**Acceptance Criteria:**

**Given** 护照单章页 B2 吸底「Bagikan」
**When** 点击
**Then** 进护照卡预览（B8）：主体段为护照内页（章格 + 护照号）· 信息段「Paspor {pet} · {N} cap · {护照号}」· 品牌段同 4.1；只出 9:16 `[L2]`
**And** 当前版本未买 → 带水印；已买 → 无水印 `[L1]`
**And** 「已购版本」里每个版本可重新导出，按快照 JSON 渲染、无水印 `[L2]`

**Given** 登机牌详情
**When** 分享
**Then** 进登机牌卡预览（B9）：主体段整张登机牌 · 信息段「{pet} · {场所}」+ 日期 · 次数；未解锁带水印、已解锁无水印；只出 9:16 `[L2]`
**And** 埋点 `passport_card_shared`（`stamp_count`）`[L0]`

---

### Story 4.4: 一键发帖炫耀

**覆盖**：FR-117.14 · UX-DR10 · AD-11

As a 想把结果发到社区的用户,
I want 点一下就带着卡图和一句话进发帖页,
So that 不用自己截图、也不用想文案。

**Acceptance Criteria:**

**Given** `PublishComposePage.open`
**When** 扩展
**Then** 新增可选 `initialText`、`initialImages`（`List<Uint8List>`，直接 `controller.addImage`，**不经相册、不申请权限**）；不传时行为不变 `[L0]`

**Given** 三处「Pamer di postingan」入口：结果页 ⋯、配型页、配型卡预览；以及已解锁登机牌 B3c 的「Pamer di postingan」
**When** 点击
**Then** 先渲染对应卡图（`CardRenderPipeline`），再打开发帖页：图片区已有卡图、输入框已预填文案（结果卡 / 配型卡各一句，EN / ID），用户可改可删后照常发布 `[L2]`
**And** 关闭发帖页即清空（autoDispose、无草稿）`[L0]`

---

### Story 4.5: 两个新渠道的分享奖励

**覆盖**：FR-117.18 · FR-120.13 · AB-3M · AD-13

As a 分享了卡片的用户,
I want 第一次分享能领到 PawCoin,
So that 分享更有动力。

**Acceptance Criteria:**

**Given** 新表 `tailsonality_share_rewards` UNIQUE(pet_profile_id, card_type ∈ RESULT | MATCH) 与 `passport_share_rewards` UNIQUE(pet_profile_id, card_type ∈ PAGE | BOARDING)
**When** 分享成功回调
**Then** 按 KTP 渠道范式发放，经 `ShareRewardService.tryReward` 全局闸门（总开关 + 月度上限）与本渠道日上限 `[L1]`
**And** 每只宠物每卡类型只发一次；**带水印卡同样计**；重测后再分享不再发；登机牌整体一个卡类型，不按张计 `[L1]`

**Given** `pawcoin_config`
**When** 迁移
**Then** 每渠道加 `…_share_reward BIGINT NOT NULL DEFAULT 0` + `…_share_daily_cap INT NOT NULL DEFAULT 0`（默认 0 = 不发）`[L0]`
**And** 后台分享奖励配置卡新增两个渠道的奖励额与日上限，改动留审计 `[L2]`
**And** 删档时奖励流水保留、`pet_profile_id` 置空（与 KTP 渠道一致）`[L1]`

## Epic 5: 里程碑徽章焕新

所有显示里程碑徽章的地方换成逐条专属插画；素材未到的继续显示原奖杯。**只换视觉，不碰任何里程碑逻辑。**

> **本 epic 另注**：素材按语义去重约 39–40 枚，陆续交付。代码先把「按 code 取图、取不到回落」的机制做好，素材到一枚放一枚，**放素材不需要改代码**（NFR-9）。

### Story 5.1: 公共徽章组件与 App 六处替换

**覆盖**：FR-111.1 · FR-111.2 · FR-111.3 · UX-DR18 · NFR-9 · AD-15

As a 完成了里程碑的用户,
I want 看到属于这个里程碑的专属徽章,
So that 每一次解锁都有辨识度、值得收藏。

**Acceptance Criteria:**

**Given** 新建 `MilestoneBadge(code, size, locked)` 公共组件与 `assets/milestone/` 素材目录
**When** 渲染
**Then** 按**完整 code** → 语义键 → 素材文件取图（映射表覆盖三物种 78 个 code，跨物种同语义共用一张，如 Camilan = C-S8 / D-S8 / G-S6）；**禁止按后缀 / 前缀寻址**（有测试）`[L0]`
**And** 取不到素材时回落现有「奖杯 + 级别色圆底」渲染；未完成态为灰色 + 锁 `[L0]`
**And** 同一张图等比缩放，不存在大小两套资产 `[L0]`

**Given** App 六处徽章
**When** 替换
**Then** 庆祝页大徽章、KOLEKSI 圆点、列表页徽章墙、列表底抽屉、Diary 里程碑条、通知中心图标**全部改用** `MilestoneBadge`，删除六处内联实现 `[L0]`
**And** 庆祝页结构、三种关闭方式、解锁振动、彩纸、补庆祝、「只弹最高级」、无新解锁不弹、已完成徽章重温**一律不变**（既有测试全绿；`milestone_celebration_test` 的统一全屏断言仍成立）`[L0]`
**And** 放入一枚测试素材后，六处该 code 同时显示新图 `[L2]`

**Given** 跨库测试
**When** 运行
**Then** 钉「映射表里的 code 均在 `MilestoneCatalog` 内、Catalog 的 78 个 code 均有映射条目」`[L0]`

---

### Story 5.2: H5 分享页徽章

**覆盖**：FR-111.1（H5）· FR-111.4 · AD-15

As a 收到里程碑分享链接的人,
I want 在网页上看到同样的专属徽章,
So that App 内外看到的是同一枚徽章。

**Acceptance Criteria:**

**Given** 后端新建 `static/milestone/`
**When** 构建
**Then** 与 App `assets/milestone/` **文件集一致**（跨库测试钉住）；公开路径放行规则与 `/brand/**` 一致 `[L0]`

**Given** `milestone_shares` 加可空 `collection_codes`
**When** 新分享产生
**Then** App 分享时写入 KOLEKSI 的 code 列表；H5 `/m` 大徽章按 `code` 出图、KOLEKSI 按 code 逐枚出图；素材缺失时回落现有 CSS 奖杯 `[L1]`
**And** **旧分享**（`collection_codes` 为空）保持原样式，不回填 `[L1]`
**And** 顺带补上被模板引用但缺失的 `static/brand/wordmark_brand.svg`（或改引用），不再 404 `[L1]`

---

## 发版检查单（**不是 story**，不进 sprint）

零代码或纯运营动作，归发版负责人，上线前逐项确认。

- **RC-1 定价**：prod 后台「一次性解锁定价」把护照内页改 **Rp2,000**、登机牌改 **Rp1,000**（当前 prod 为 Rp100，来自 V1.3.0 迁移初值）；确认 Tailsonality 为 Rp5,000
- **RC-2 打卡计数开关**：prod 打开 `ADMIN_PLACES_CHECKIN_VISIBLE`
- **RC-3 分享奖励**：运营在后台为 Tailsonality、护照两个新渠道填奖励额与日上限（默认 0 = 不发）
- **RC-4 素材**：16 角色卡与 5 配型卡换成**纯插画无字版**；7 款默认章、7 张默认场所图、12 张图片题、约 40 枚徽章按到货替换；确认无 "TaiTopia" 拼写错误的旧图残留
- **RC-5 翻译复核**：dev agent 翻译的 EN / ID 文案（题库、解读、配型）建议由印尼语母语同事过一遍
- **RC-6 老版本兼容**：用上一版 App 验一次 Diary 与「我的订单」，确认看不到新条目且无错乱
