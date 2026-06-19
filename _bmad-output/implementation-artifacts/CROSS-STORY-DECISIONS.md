# 跨 Story 决策与风险台账（TailTopia V1.0）

> 来源：46 份 story 文件交叉冲突扫描（2026-06-02）。本文件是**跨 story 契约/数据生命周期决策的单一事实源**。dev 实现时如遇本表覆盖的点，以此为准。
>
> **2026-06-08 增补（F1–F8）**：源自 incoming PRD V1.0.0(2026-06-06) 的 correct-course 影响评估，见 `planning-artifacts/sprint-change-proposal-2026-06-08.md`（已批准）。F 系列与既有 C/D/E 同级权威。
>
> **2026-06-08 第二轮增补（F9–F15）**：源自上游 PRD「12 处逻辑补齐」提交（异常态 / 退出态 / 并发竞争 / 跨模块一致性），见 `planning-artifacts/sprint-change-proposal-2026-06-08-gapfixes.md`。**F10 反转 F1 的内容审核条款**（按用户 2026-06-08「最新 PRD 决定优先」指令）。

## 已拍板决策（已回填进对应 story）

| # | 类别 | 决策 | 落点（已改文件） |
|---|---|---|---|
| **C1** | 契约 | 「当前用户」资源统一用 **`/api/v1/me`**（GET 聚合 / PATCH 改昵称头像状态 / DELETE 注销），**全平台不用 `/users/me`** | 1-6, 1-7, 2-4（原 `/users/me` 已改）；7-1/7-3 本就用 `/me` |
| **C2** | 契约 | `role` 三态归属：**`USER`@1.3、`ADMIN`@3.1（建 Admin shell 时）、`VET`@5.1**；5.1 只补 VET，不重复引入 ADMIN | 5-1（措辞 + Project Structure + References） |
| **C3** | 契约 | 点赞/举报表统一 **`content_likes` / `content_reports`**（带模块前缀），勿用 `likes`/`reports` | 3-4（去掉「或 likes」）；3-7 本就用 content_reports |
| **C4** | 契约 | **接口契约「后端主导」**：对外 JSON 形状以后端 `*Request`/`*Response` record（+ springdoc OpenAPI 3.1）为**权威落地**，契约的「应然」以本文件 / architecture 文档为**单一事实源**。App 的 `mock_backend.dart` 与 data 层 DTO 是**后端契约的镜像，不得自创字段**。需求方向允许 App→后端（consumer-driven：App 列页面所需字段反推 DTO），但裁决一律落到后端 DTO + 文档。**联调以真后端为准——mock 漂了改 mock，禁止在客户端兜底转换抹平契约差异** | 全局约定；新对外接口在对应 story 的「联调验收」段引用本条 |
| **C5** | 契约 | **契约一致性是可证伪验收**：每个对外 `*Response` 的**字段集 / 枚举线格式**是一条带层级的 AC——① 后端字段集回归（**L0**：纯 Jackson 序列化金标 test，无 DB）② App `mock` ↔ data DTO 字段对齐（**L0**）③ 真后端 ↔ mock 同请求字段集一致（**L1**）。**改任一对外 DTO 必须同步改：后端 record + App data DTO + App mock + 对应契约 test，四处不同步即视为契约破坏，PR 不绿**。示范实现：`FeedResponseContractTest`（content 模块，钉 `FeedPageResponse`/`FeedItemResponse`） | 全局约定；3-2 Feed 为首个落地范例 |
| **C6** | 契约（C4/C5 落地） | **兽医工作台 DTO：无数据源字段「后端不下发 + 前端兜底」**（2026-06-19，consumer-driven 裁决落后端，守 C4「mock 漂了改 mock、禁客户端兜底转换抹平」）。① **`petSex` 后端不返回**——`pet_profiles` 无性别列、建档不收集性别；`VetInboxItem`/`VetSessionView`/（历史）`VetHistoryItem` 均不含该字段，前端工作台身份行去性别段、mock 同步去 `petSex`。② **`unread`/`lastMessage` 后端不返回**——属腾讯 IM 侧状态、V1 不接 IM 回调入库；`/vet/consult-sessions/in-progress` 仅回 `{sessionId,source,petName}`，客户端读 IM SDK 取未读/最近消息，空值优雅降级，mock 兼任 IM 离线占位。③ 同步**新建** `GET /vet/consult-sessions/in-progress`、`/history` 两端点，并对 `VetInboxItem`/`VetSessionView` **富化宠物身份**（跨模块只读端口 JOIN `pet_profiles` + 机主昵称，注销匿名化后兜底 null） | 后端 consult `dto`(VetInboxItem/VetSessionView/新 VetActiveItem/VetHistoryItem)+`VetConsultService`+`VetConsultController`+repo；profile `PetProfileQueryService`/新 `PetIdentityView`；前端 `vet_inbox_item.dart`/`vet_workbench_lists.dart`+工作台3屏+`mock_backend.dart`+`mock_contract_test.dart`；`docs/api-reference.md` §7 |
| **D1** | 数据生命周期 | 注销时 **`consult_sessions`/`consult_ratings` 匿名化保留**（剥 user PII，保留症状/评级/评分供运营 FR-33 与未来 FR-5 库），与 UGC 一致；**`triage_tasks` 仍物理删除**（纯个人 AI 健康记录） | 7-3（AC2 + B3 删除列表 + 新增 B5b + Dev Notes 权威分类清单 + J2） |
| **D2** | 数据生命周期 | 注销时**腾讯 IM 聊天媒体**：调 IM 删除该用户会话媒体，或确认 IM 侧 TTL 自动清理（二选一，dev 落实并记录）；存档到私密桶②的副本随个人图删除。**不可「按隐私边界处理」含糊带过** | 7-3（新增 B5c + Dev Notes） |
| **E1** | 状态机 | `SessionStatus` 接受第 6 态 **`CANCELLED`**（`WAITING → CANCELLED`，等待中用户主动取消，5.3 引入） | architecture.md §Communication Patterns 状态机已回填 |
| **E2** | 基建 | **Flyway 序号按 dev 执行顺序单调分配**，勿照搬 architecture 示例号（示例 `V2__init_profile` 与 1.3 实占 `V2__init_auth` 会撞）；各 story 用 `V<n>__` 占位 | sprint-status.yaml 头注（全局约定） |
| **E3** | 依赖 | **Admin shell 是跨 Epic 硬依赖**：3.1（Epic3）首建 `/admin/**` 门控 + `role=ADMIN` + `admin/layout.html` + ADMIN 账号种子；5.1/5.6/5.7（Epic5）复用，不重复建 | 5-1 References 标注依赖 3-1 |
| **E4** | 隐私 | EXIF 剥离**客户端为主路径 + 公开桶服务端兜底**：公开桶对外图（尤其 H5 名片）必须经 OSS `x-oss-process` 去元数据或后端重处理，防改过的客户端绕过客户端剥离 | 2-1（AC2 + B2 兜底方法）；2-6（B2 H5 图必走服务端去 EXIF） |
| **E5** | 安全 | 4.2 安全层加**保守否定语境处理**：仅当高危词被紧邻否定**且全文无其他命中**才不计；**存疑即升红**，否定逻辑绝不漏真实急症 | 4-2（B3 匹配逻辑 + J1 否定测试用例） |
| **F1** | 权威源 | **incoming PRD V1.0.0(6/6) 的 §8 Open Questions / §9 假设 / 附录C 为更早底稿，一律不作权威**：PDP 合规（仍 `⏭️V1暂不处理` + D1/D2/R-PDP 匿名化/级联删除照旧）、~~内容审核（人工审核，不做机器/关键词过滤）~~ **← 已被 F10 反转（见下，2026-06-08 第二轮）**、AI 选型（仍 **✅Gemini 单模型**）、产品名（命名 TBD，不阻塞）、GPS（随攒局后延）一律以现行 `PRD.md` + 本台账为准。**严禁据 incoming 尾部回退 7-3 合规实现或既有决策（7-3 仍不动，见 F14）。** PRD 合并时只取 incoming §4 正文 + §6 排期表 + 附录B 变现路线图，尾部保留现行决策版 | 全局；7-3 **不动** |
| **F2** | 范围/排期 | **FR-42 里程碑系统分层纳入（产品 6/8 拍板）**：①进 V1.0.0—`pet_type` 字段、档案统计栏、第一条内容🌟标记、生日提醒 FR-40、陪伴纪念日 FR-41（经 6-7 定时推送）、成长档案双视图日历(FR-37)；②~~**FR-42 本体**（…）**拆为独立 mini-epic，可降至 1.0.x/1.1.0**~~ **← 已被 F16 反转**（2026-06-09 产品拍板：本体全量纳入 V1.0.0，Epic 8 转入实现）；③**2-6 名片里程碑区块走零态降级**，不硬依赖 FR-42 先落地（F16 后仍保留零态降级作为渲染容错，但 V1 内 FR-42 本体会真实供数） | 2-4/2-6；Epic 8（F16 起实现）|
| **F3** | 安全 | **红色态去导航化（安全攸关，incoming §4.1 FR-3 / §2.3 UJ-1）**：4-5 **删除「去导航 + 系统地图深链」整条**（含 `triage_navigation.dart`、地图搜索词 i18n、J2 真机地图验收），改为 5 秒后单一「我已知晓」按钮关闭遮罩、保留结果页。**红色态零兽医入口 / 零变现护栏保持不变**（与方向更收敛一致）。architecture「红色态→系统地图深链」描述同步删除 | 4-5（已实现，需回退/重做）|
| **F4** | 范围 | **V1.0.0 全程无视频（全功能面，2026-06-08 产品确认）**：AI 分诊仅图片、兽医 IM 聊天仅文字/图片、**内容发布仅图片**、成长档案/名片仅图片。视频（含 VOD/转码）随收费模式后置。5-5 删视频聊天能力（视频≤60s 校验整条移除）；4-3 注脚「视频 V2」→「**收费模式启用后开放**(MP4/MOV ≤60s/≤100MB)」；architecture 媒体三层③「IM 图/视频」→仅图片。**全仓代码清查结论（2026-06-08）**：后端零视频实现；前端仅 3 处「video」文案残留（原型换肤遗留硬编码串），已清除——`im_chat_placeholder.dart` 注释、`triage_page.dart`「foto/video gejala」、`publish_compose_page.dart`「foto/video」均改为仅图片。无视频校验/收发/转码逻辑存在 | 5-5；4-3；2-3/4-3 文案（已修）|
| **F5** | 护栏 | **定时类系统推送（生日 FR-40 / 纪念日 FR-41 / L级里程碑节点 FR-42）必须用 Spring 原生 `@Scheduled` 每日扫描 + `@Async` 逐条投递 + DB 去重标记位**（同 6-2「批量在线兽医循环走 @Async」既有范式）。**禁 Quartz / Kafka / 任何调度或消息中间件**（CLAUDE.md 护栏）。≤500 DAU 单机日扫足够。生日扫 `pet_profiles.birthday`、纪念日扫 `profile.created_at + {30,100,365}d`，DB 标记"当年/该节点是否已推"去重 | 新 6-7 |
| **F6** | 数据 | `pet_profiles` **加列 `pet_type varchar not null`**（`CAT`/`DOG`/`OTHER`，UPPER_SNAKE）；**创建后不可修改**（服务端硬拒 + 前端置灰，避免已完成里程碑数据错乱）。Flyway 加列迁移，序号按执行顺序顺延（E2） | 2-2（建列+创建校验）；2-8（编辑禁改）|
| **F7** | 交互 | **推送权限申请单时机→双时机**（incoming FR-22D）：「完成首次问诊后」**或**「完成宠物档案创建后（仅从未问诊用户）」取最早触发，仅弹一次；双时机均触发过/已授权则不再弹。现有 `pushPermissionAsked` 单布尔判定改为 `(首次问诊完成 OR 建档完成) && !asked` | 6-4（改 AC1）|
| **F8** | UX/品牌 | **UX 品牌/视觉真相 = `petgo_app/lib/core/theme/colors.dart`（薄荷绿 `#7FD1AE`，2026-06-04 全面换肤）+ Claude Design mint 原型**，**非** UX markdown 文档。`planning-artifacts/UX_DESIGN.md`/`UX_EXPERIENCE.md`（现行）与 `incoming/UX_*.new.md` **均为焦糖色旧版（#C8874A）**，不作品牌权威；**不晋升 incoming `.new` 覆盖现行**（覆盖=品牌回退，否决提案 §2.4 该步）。新功能 UX/交互结构（个人中心重组 / 名片6区块 / 红色态去导航「我已知晓」/ 里程碑三级动效 / 双视图日历 / 推送扩展）以 **PRD §4 + mint 原型**为准，落地一律套 mint token（colors.dart 第①层新 token 优先）。incoming `.new` 仅作新功能交互结构的参考底稿 | 全局 UX；提案 §2.4 修正 |
| **F9** | 数据/口径 | **成长日历「事件日期」与发布时间分离**：`content_posts` 加列 `event_date date null`（仅成长日历类型有值；日常/科普为 null）。**排序口径**：Feed / 「我的发布」按 `created_at` 倒序；成长档案时间线 / 日历视图 / H5 名片快乐时刻流按 `event_date` 排序。事件日期**不可选未来**、可选任意过去；日历格子取「该 `event_date` 下最早 `created_at` 记录的首图」为背景；未来日期格子置灰不可点。Flyway 加列顺延（E2） | 2-3（建列+发布表单事件日期字段+默认值/校验）；2-4（双视图按 event_date + 当天详情页 + 未来格子置灰）；2-6（H5 快乐时刻流按 event_date） |
| **F10** | 护栏（**反转 F1 内容审核**） | **内容审核：人工 → 发布时三方自动审核 + 用户举报双层**（上游最新 PRD §4/§8.6 主动推进，覆盖 F1「人工、不做关键词过滤」；依用户「最新 PRD 决定优先」指令）。发布时三方系统秒级审：**文字关键词过滤 + 三方图像识别**；任一拦截即发布失败、停留编辑页、不进人工队列、改后可重提；文字图片均过才发布。**三方图像识别 V1 接口占位（`ContentModerationService` stub，流程真跑、真实三方后接，假设 A-6）**；举报模块仅处理已发布内容，运营判定违规 → 内容下架（Feed/我的发布/档案同步移除 + 通知作者、不说举报人、无申诉）。**禁引入新中间件**，stub 走应用内实现（护栏）。F1 其余条款（PDP/AI 选型/产品名/GPS/7-3 不回退）仍有效 | 2-3（发布审核流程 + `ContentModerationService` 接口 + 图像 stub）；3-7（举报描述对齐 + 违规下架 admin action）；PRD §6.1/§8.7/§9 A-6/附录C 已改 |
| **F11** | 并发/安全 | **兽医接单改抢单模式 + 后端原子写入先到先得**（FR-30）：多在线兽医并发抢接，接单走 **DB 层原子条件更新**（`UPDATE consult_sessions SET vet_id=?,status='IN_PROGRESS' WHERE id=? AND status='WAITING'` 判影响行数，或乐观锁版本列）——**影响 0 行 = 已被抢 → 失败提示**，杜绝双接单。请求详情 **3 分钟预览超时**回队列；**退单**重新广播、最多 2 次、超限运营介入。**禁 MQ / 分布式锁中间件，纯 DB 原子写**（CLAUDE.md 护栏） | 5-3（接单原子写 + 退单 + 预览超时）；5-2（待接单抢单列表 UI） |
| **F12** | 退出态/会话 | **退出 / kill 进程语义**：① 用户**等待匹配期间**退出/kill → 系统自动取消匹配请求、从待接单队列删除（`WAITING→CANCELLED`，复用 E1）；② 用户**对话进行中** kill → 视为断线、进 30 分钟保护窗口、重开 App 可从「进行中」恢复、超时自动关闭。需前端生命周期检测 + 后端取消/保活接口 | 5-3（等待退出取消）；5-6/5-7（会话断线保护窗口 + 恢复） |
| **F13** | 横切/异常态 | **加载失败 / 输入失败统一口径**：网络/服务器错误 →「加载失败，（下拉）重试 / 请检查网络后重试」+ 重试入口，已加载内容保留、仅增量失败时底部重试；输入类失败（评论/发布/登录）→ 保留输入、可直接重试。覆盖 FR-0D 授权失败、FR-14 H5 失败/链接失效、FR-17 Feed 失败、FR-37 时间线/日历/当天详情页失败、FR-24 评论失败 | 1-4（授权失败回跳）；2-6（H5 失败/失效）；3-2（Feed 失败）；2-4（档案视图失败）；3-5（评论失败保留） |
| **F14** | 数据生命周期（**重申 D1**） | 上游 FR-26「问诊记录匿名化保留」为**笼统措辞**，按既有 **D1** 落：**consult 匿名化保留 / triage 物理删除**，**7-3 合规实现不回退**（守 F1 末句）。注销时 **H5 名片链接立即失效**（`AccountDeletionJob` 增名片 token 失效，D2 邻接）。PRD FR-20 措辞已对齐 D1 | 7-3（确认 D1 + 名片 token 失效；triage 删除/consult 匿名化已在 B 列）|
| **F15** | 交互/时序 | **建档「创建成功」庆祝页 + 推送权限时序**：FR-0G 建档完成 → 庆祝页（头像+名字 / 副 CTA 分享名片 / 主 CTA 开始探索）→ 触发推送权限弹窗（FR-22D 建档时机，庆祝页后、进首页前）→ 首页。**经 FR-16 / FR-12 灰选触发的建档完成「跳过庆祝页」**直接回原流程（存档 / 返回发布页预选成长日历） | 1-7（庆祝页+推送时序）；6-4（建档弹出位置=庆祝页后）；2-5/2-3（跳过庆祝页串接）|
| **F16** | 范围/排期（**反转 F2②**） | **FR-42 里程碑本体全量纳入 V1.0.0（产品 2026-06-09 拍板，推翻 F2 的「拆 mini-epic 降至 1.0.x/1.1.0」）**。原始 incoming PRD §6.1 本就把里程碑系统列入 V1.0.0 包含；6/8 因 V1 轻量姿态曾建议降级，现产品确认按**全量** FR-42 实现：猫30/狗30/通用15 固定清单 + S/M/L 三级 + 系统自动完成（订阅既有领域事件）+ 用户打卡两路径（已打卡内容关联选择器 / 去发布成长日历）+ S/M/L 三级庆祝动效 + 列表页徽章（彩色/灰锁）+ L级达成推送（经 6-1 `MILESTONE_NODE`）+ L级分享卡（WhatsApp/Instagram）。新表 `pet_milestones`/`milestone_completions`（profile 域）。**护栏不变**：自动完成靠 `@TransactionalEventListener`/`@Async` 订阅既有事件 + 定时类走 6-7 `@Scheduled`，**禁 MQ/Quartz/缓存/新中间件**；`pet_type` 创建后不可改（F6）。Epic 8 从 backlog 转入实现，拆 8-1…8-6 story（见 sprint-status）。**F4 全程无视频不受影响**；**F10 内容审核 stub 预留位置维持不变** | Epic 8（8-1 数据基建 / 8-2 列表页徽章 / 8-3 系统自动完成事件订阅 / 8-4 用户打卡两路径 / 8-5 三级庆祝动效 / 8-6 L级推送+分享卡）；连带 2-6 名片真供数、6-6 铃铛里程碑条真数据、6-7 L级里程碑推送接真本体 |

## 风险台账（V1 不阻塞，记账待评）

| # | 风险 | 现状 | 触发重评条件 |
|---|---|---|---|
| **D3** | FR-5 历史判断库 vs 注销删除 | V1 历史匹配为空（G-2 延后），**无实际冲突**；且 D1 已让 consult 数据匿名化保留，未来库可从匿名数据长出 | V2 实现 FR-5 历史匹配时，确认库**只从匿名化后的 consult 数据**派生，不依赖 user PII |
| **R-EXIF** | 关键词/EXIF 等"规则化"手段的固有局限 | E5 否定处理 + E4 服务端兜底已缓解主要面 | 出现误升红投诉聚集 / 真实漏兜个案 → 兽医顾问评估清单与匹配策略 |
| **R-PDP** | 印尼数据出境（后端留德国 + Gemini 出境） | 架构既有挂账（暂缓≠豁免） | 印尼监管收紧 → 后端迁亚太/只读副本 + 迁 Vertex |

## 表归属总表（扫描确认：每张表恰好一个创建者，无重复建表）

| 表 | 创建 story | 备注 |
|---|---|---|
| `schema_meta` | 1.1 | 基线迁移占位 |
| `users` | 1.3 | 1.6 仅 UPDATE，1.7 读 `hasPetProfile` 信号 |
| `pet_profiles` | 2.2 | 1.7 期 `hasPetProfile` 恒 false 占位 |
| `content_posts` | 2.3 | 3.x 全部复用 |
| `content_posts.event_date` | 2.3 | F9：成长日历事件日期 `date null`，与 `created_at` 分离；日常/科普为 null。**并入 2.3 `init_content` CREATE，不另起 ALTER / 不额外占号** |
| `comments` | 3.5 | |
| `content_likes` | 3.4 | C3 统一命名 |
| `content_reports` | 3.7 | C3 统一命名 |
| `triage_tasks` | 4.1 | 4.2 接后置挂载点 |
| `vet_accounts` | 5.1 | |
| `consult_sessions` | 5.3 | 5.4/5.6/5.7 ALTER；**F11 加 `release_count`(退单计数 ≤2) 并入 5.3 CREATE；接单原子写用 `status` 条件更新（`WHERE status='WAITING'` 判影响行数）无需 version 列** |
| `consult_ratings` | 5.6 | |
| `health_events` | 2.5 | |
| `notifications` | 6.1 | 6.7 增 type 枚举 PET_BIRTHDAY/COMPANION_ANNIVERSARY/MILESTONE_NODE（F2/F5）|
| `pet_profiles.pet_type`（加列）| 2.2 | F6：加列非建表，创建后不可改 |
| `pet_milestones` / `milestone_completions` | 里程碑 mini-epic（F2）| 归 profile 域；排期 1.0.x/1.1.0 待定，**非 Epic 6**。本轮 FR-42 断档补齐（in-page picker 打卡 / L 级达成推送 / 已过生日补录）已并入 PRD FR-42 规格，随 mini-epic 实现，**本轮不落代码** |
| `notifications` 去重标记（生日/纪念日/节点已推）| 6.7（F5）| 落 notifications 附加列或独立小表，dev 落实 |

## 跨 story 共享设施归属（扫描确认链路连贯）

- **Admin slice**：3.1 首建 → 3.7（举报队列）/5.1（兽医 CRUD）/5.6（评分查看）/5.7（封禁）复用。
- **`shared/media`（StsService/SignedUrlService/AliyunOssClient）**：2.1 建；`ImToOssArchiver` 2.1 占位 → 2.5 实现 → 5.x 用。
- **`SafetyRuleLayer`**：4.1 预留挂载点 → 4.2 实现。
- **`NotificationService`**：6.1 建 → 6.2/6.3/6.4 用。
- **会话状态机**：5.3 入口(WAITING/CANCELLED) → 5.5 接单(IN_PROGRESS) → 5.6 收尾(PENDING_CLOSE/CLOSED) → 5.7 中断(INTERRUPTED) → 5.8 视图收口。
- **`AccountDeletionJob`**：7.3 建（消费各模块 `deleteByUserId`/`anonymizeByUserId`）。
