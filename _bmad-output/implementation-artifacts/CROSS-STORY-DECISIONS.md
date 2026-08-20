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
| **C6** | 契约（C4/C5 落地） | **兽医工作台 DTO：无数据源字段「后端不下发 + 前端兜底」**（2026-06-19，consumer-driven 裁决落后端，守 C4「mock 漂了改 mock、禁客户端兜底转换抹平」）。① **`petSex` 后端不返回**——`pet_profiles` 无性别列、建档不收集性别；`VetInboxItem`/`VetSessionView`/（历史）`VetHistoryItem` 均不含该字段，前端工作台身份行去性别段、mock 同步去 `petSex`。② **`unread`/`lastMessage` 后端不返回**——属腾讯 IM 侧状态、V1 不接 IM 回调入库；`/vet/consult-sessions/in-progress` 仅回 `{sessionId,source,petName}`，客户端读 IM SDK 取未读/最近消息，空值优雅降级，mock 兼任 IM 离线占位。③ 同步**新建** `GET /vet/consult-sessions/in-progress`、`/history` 两端点，并对 `VetInboxItem`/`VetSessionView` **富化宠物身份**（跨模块只读端口 JOIN `pet_profiles` + 机主昵称，注销匿名化后兜底 null） | 后端 consult `dto`(VetInboxItem/VetSessionView/新 VetActiveItem/VetHistoryItem)+`VetConsultService`+`VetConsultController`+repo；profile `PetProfileQueryService`/新 `PetIdentityView`；前端 `vet_inbox_item.dart`/`vet_workbench_lists.dart`+工作台3屏+`mock_backend.dart`+`mock_contract_test.dart`；`docs/api-reference.md` §7。**已合并 main：PR #14（squash `8843f91`）**，含 code-review 修复：写路径(accept/end/release)返回不富化基础视图防「富化失败翻 500→幽灵接单」、列表端点批量富化除 N+1、`ConsultSession.terminalAt()` 终态时间单一口径 |
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
| **F17** | 数据/契约（**前端占位待后端**） | `pet_profiles` **拟加列 `sex varchar null`**（`MALE`/`FEMALE`，UPPER_SNAKE；可空——存量与未填允许 null）。来源：2026-06-18 UI 保真比对——`pet-edit` 原型含 **KELAMIN（Jantan/Betina）** 字段，但后端 `PetProfileResponse`/客户端 `PetProfile` 模型与创建页均无此字段。**当前前端编辑页已落占位选择器（选了不随 PATCH 提交、不持久化）**。后端待办：加列（Flyway 顺延 E2）+ DTO（`PetProfileResponse`/`*Request` 加 `sex`）+ **创建页(2-2) 与编辑页(2-8) 双端接通** + mock 镜像四处同步（C4/C5）。性别属 PII，**日志脱敏护栏适用**。与 F6（`pet_type` 同表加列）同范式 | 前端 `pet_profile_edit_page.dart` 占位已落；后端 + 2-2/2-8 待接 |
| **F18** | 数据生命周期（**✅ 已实现 2026-07-06**） | **宠物档案删除**：`DELETE /api/v1/pet-profiles/me`（走 `/me` 主体，C1；账号注销 7-3 另论）。来源：2026-06-18 UI 保真——`pet-edit` 原型含「🗑 Hapus Profil」危险区。**【2026-07-06 已实现（bug 20260702-237，L2 验收通过）】** 后端 `ProfileApiController.deleteMyProfile` → `ProfileService.deleteMyProfile` **复用 `ProfileDeletionService`**（与注销 7-3 同一套级联）：物理删 `health_events`/`pet_milestones`/`milestone_completions`/`milestone_shares`，名片 `card_token` 随档案行消失自然失效，OSS 个人图（头像/OG/健康图）提交后 best-effort 清理。**删除语义定为「立即物理删」**（与 7-3 一致，无软删冷静期——契合 V1 姿态）。**UGC（`content_posts` 成长日历条目）定为「保留」**（与注销 7-3「匿名化保留 UGC」一致，原「保留 vs 删除」待定项就此拍板）。**`petStatus` 不改**：删后用户仍 HAS_PET 但无档案 → 前端落空档案态可重建/切换（闭合被困死问题）。前端编辑页恢复红色删除按钮 + 二次确认。**红色态/合规护栏不变**。 | ✅ 已实现（2026-07-06）：后端 `DELETE /me` + 级联 + 单测；前端删除按钮/弹窗/删档跳空态；L2 验收通过 |
| **F20** | 数据生命周期（**⚠️ 有意偏离 7-3 匿名化总精神，业务负责人拍板承担**，2026-07-10） | **运营后台展示已注销用户**（运营诉求：原来注销后后台信息空白、状态仍「正常」，无法识别谁注销）。**DB 改动（迁移 `V47__add_user_deletion_display.sql`）**：`users` 表新增两列 `deleted_email VARCHAR(320)` / `deleted_display_name VARCHAR(255)`；`User.anonymizeForDeletion` 首次注销时把 `email`/`displayName` **快照**进这两列（幂等，重跑不覆盖），**原 `email`/`display_name` 仍按 7-3 置空**。**合规定性**：这两列保留了已注销用户的 email+用户名 PII，属对「注销剥离全部 user PII」总精神的**有意偏离**（非回退 D1/D2/triage 删除等白纸黑字条款）；风险由业务负责人（hanwei）确认承担。**隔离保证**：新列**仅 `AdminUserService`（后台列表/详情）读**，无任何 API 直接序列化 User 实体 → **不泄漏 C 端/兽医**（公开作者名 `toAuthorView` 与 vet 富化仍读置空的原列、走 `deletedAt` 匿名分支）。**重注册不受影响**（live `email` 仍 null、无唯一约束、注册按 `googleSub` 墓碑）。后台已注销账号**仅展示、不可处置**（停用/激活/删除入口隐藏 + `deleteUser` 后端护栏拒绝重复删除）。**已知限制**：后台按邮箱搜索匹配 live 列 → 搜不到注销用户（只能列表浏览/按 ID）；注销用户混排入列表并计入分页。 | ✅ 已实现（2026-07-10）：迁移 V47 + `User`(快照字段/幂等) + `AdminUserRow`/`AdminUserDetailView`(deleted) + `AdminUserService` + `users.html`/`user-detail.html`(已注销态+隐藏处置) + i18n `admin.users.status.deleted` + `UserAnonymizeTest`；usermgmt/auth L0 79 项通过 |
| **F21** | 数据生命周期（**⚠️ 有意偏离 D2/7.3「OSS 个人图物理删除」，业务负责人拍板承担**，2026-08-19） | **OSS 对象任何情况不再物理删除 + KTP 卡删档可见性分流**。背景：KOL KTP 卡快照头像丢失事故——`id_cards` 快照只存 URL 不拥有对象，用户删档重建（F18 复用注销级联）把仍被卡引用的头像对象物理删掉，快照成死链且不可恢复（桶未开版本控制，存量 9 卡 4 用户永久损失）。**决策①（hanwei 拍板）：OSS 对象任何情况保留不删**——`MediaDeletionService` 改只记账 no-op（API 形状保留供 7.3/F18 编排继续调用），`AliyunOssClient` 删除原语整体移除；**DB 行删除/匿名化不受影响，仅对象存储保留**。合规定性：注销后头像等个人图对象仍存于公开桶可经原 URL 访问，属对 D2「存档私密桶副本随个人图删除」及 7.3 个人图删除的**有意偏离**，风险由业务负责人确认承担；新增任何删除逻辑须先回本条重新拍板。**决策②：删档时卡按付费态分流**（迁移 `V108__add_id_cards_profile_deleted_at.sql`）：`id_cards` 新增 `profile_deleted_at` 打标列（`ProfileDeletionService` 删档时批量打标，幂等只打未打标行）；可见性 = `hd_unlocked OR profile_deleted_at IS NULL`——**付费卡恒可见**（哪怕档案已删/已换宠物，展示快照信息）；**未付费卡（含等待付款/过期等一切非到账态）隐藏**（列表不返回、详情 404 不可预览）；到账回调（`completeCardByIntent` 按 findById 不走过滤）翻转 `hd_unlocked` 后**自动重新可见**（防「删档时支付在途」时间差丢卡）。存量回填：卡建立时点早于现存档案或用户已无档案 → 视为档案已删打标。前端三卡面照片框补 errorBuilder 回落占位（死链/网络失败不再静默空白）。 | ✅ 已实现（2026-08-19）：V108 + `IdCard.profileDeletedAt` + `IdCardRepository.markProfileDeleted` + `ProfileDeletionService` 打标 + `IdCardService` 可见性过滤 + `MediaDeletionService` no-op + `AliyunOssClient` 删除原语移除 + ktp/passport/student 卡照片 errorBuilder + `IdCardServiceTest` 可见性 3 例 |
| **F19** | 数据/契约（**前端占位待后端**） | **兽医可用状态拟从二元升为三态**：当前后端兽医在线态为 `bool`（`PUT /vet/online-status` · `readOnlineStatus()→bool` · `setOnline(bool)`），仅 online/offline。来源：2026-06-21 UI 保真——`vet-status-popup`（V-st）原型含 **三选项 Online / Sibuk(忙碌) / Offline**。**当前前端已落 V-st 状态切换抽屉 + 三态 `VetAvailability{online,busy,offline}`（`vetAvailabilityProvider`）**，但持久化时按二元映射：**Online→接单(true)、Sibuk/Offline→不接单(false)**——**「Sibuk」为纯前端占位态**：选中后后端落 false（与 Offline 同），药丸前端显示 Busy，但冷启动 / 他处改在线态后回落为 Offline，无法独立持久。后端待办：在线态升三态枚举（`AVAILABILITY varchar` UPPER_SNAKE：`ONLINE`/`BUSY`/`OFFLINE`，替换/兼容现 bool）+ DTO + 端点契约 + mock 镜像同步（C4/C5）+ 决定「Sibuk」对抢单队列可见性的语义（用户侧是否显示「忙碌」）。**护栏不变**：不引新中间件；IM 上/下线仍按「是否接单」二分跟随（Sibuk 视作下线/不接单）。需产品先拍板是否要 Busy 三态再实现 | 前端 `vet_top_bar.dart` / `vet_status_sheet.dart` / `vet_online_status.dart` 占位已落；后端三态 + 队列可见性语义待拍板 |

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
| `user_hide_relations` | **1.1（V1.1.4）** | 隐藏关系单表双来源（BLOCK/REPORT）。**唯一键三元 `(holder_id, target_id, source)`** —— 幂等只在同源之间成立；举报写 REPORT 行**不得触碰 BLOCK 行任何字段**（黑名单排序取 `BLOCK.created_at`）。Epic 1 全部 + Epic 2 举报即隐藏 复用 |
| `account_reports` | **2.1（V1.1.4）** | 账号举报工单。**一行 = 一个被举报账号，`target_user_id` 唯一** —— 12 个人举报同一个人也只有一条工单。状态 `PENDING/RESOLVED/DISMISSED`（**展示层第三档叫「无需处置」而非「已驳回」**，C-103；数据层值不改）。已处置后再被举报 → **同一条翻回 PENDING、不新建**，`first_reported_at` **不刷新**。Epic 3 统一队列消费 |
| `account_report_entries` | **2.1（V1.1.4）** | 账号举报明细，**只追加不覆盖、无任何唯一约束**（刻意 —— 与 `content_reports` 的 `(reporter_id, post_id)` 唯一 + service 幂等吞掉正好相反）。每一次举报的类型与「其他」补充说明都独立留存；`detail` 是用户自由文本，**禁止进日志**。索引 `(report_id, reporter_id)` 供 Epic 3 优先级公式一次聚合出「人数 / 次数 / 高频人数」 |
| `account_disposals` | **3.1（V1.1.4）** | 账号级处置留痕（WARNING / SUSPEND）。**Story 3.1 建表并读**（工单列表的「历史处置次数」），**写入由 Story 3.2 接入** —— 上线初期表为空、次数显示 0 属逻辑完整状态。在此之前账号级处置**没有任何结构化留痕**（只有 `admin_audit_logs` 里一行中文 detail），这正是本表的立项理由 |

## 跨 story 共享设施归属（扫描确认链路连贯）

- **Admin slice**：3.1 首建 → 3.7（举报队列）/5.1（兽医 CRUD）/5.6（评分查看）/5.7（封禁）复用。
- **`shared/media`（StsService/SignedUrlService/AliyunOssClient）**：2.1 建；`ImToOssArchiver` 2.1 占位 → 2.5 实现 → 5.x 用。
- **`SafetyRuleLayer`**：4.1 预留挂载点 → 4.2 实现。
- **`NotificationService`**：6.1 建 → 6.2/6.3/6.4 用。
- **会话状态机**：5.3 入口(WAITING/CANCELLED) → 5.5 接单(IN_PROGRESS) → 5.6 收尾(PENDING_CLOSE/CLOSED) → 5.7 中断(INTERRUPTED) → 5.8 视图收口。
- **`AccountDeletionJob`**：7.3 建（消费各模块 `deleteByUserId`/`anonymizeByUserId`）。
- **社区关系模块 `com.tailtopia.social` + `social.read` 只读端口**：**1.1（V1.1.4）建** → Epic 1 全部 / Epic 2 举报即隐藏 复用。
  - 端口 `UserHideRelationReader` 提供**两种**查询：`isHidden`（不区分来源，供 Feed / 评论 R1·R2 / 通知抑制 / 搜索列表 / 运营干预位**五处**）与 `isBlocked`（**只认 BLOCK**，**专供主页访问校验**）。
  - ⚠️ **`content` / `notify` / `auth` 三侧只依赖该端口接口，禁止引用 `social.repository`**（AD-8）。端口接口放**提供方**，有意偏离 `ViolationCountReader` 把接口放消费方的先例（那条只有一个消费方，本端口有三个）。
  - ⚠️ **安全规则层，只升不降不可绕过**：**凡新增「向用户展示他人内容」的位置（列表 / 详情 / 推荐位），一律默认套用该端口，不做逐场景例外**。漏一处等于拉黑白拉。本版本上线时平台尚无运营干预位与用户搜索，规则不触发但已声明；V1.1.6 的 FR-68 顶置坑位实现时须接上。
  - ⚠️ **无缓存**：每次查库走唯一索引，禁止为其引 Redis 或本地缓存（AD-18）。

## 2026-07-27 追加决策（bug 20260727-364 拍板）

- **M-1 取消「一兽医一单」占用互斥**：兽医可并发接多单（付费计费流），**数量兽医自控、系统不设上限**。
  - 由来：占用互斥源于 V1.0 Story 5.5（2026-06-02，goBusy）、被 V1.1 story 3-3 明文继承；单兽医供给下退化为「B 盲等 + 兽医盲忙」（bug 364），2026-07-27 用户拍板取消。
  - 落地：`acceptRequest` 去 `isBusy` 409 守卫；**计费流全程不再触碰 `vet:busy`**（接单不置 BUSY，超时/取消/现金故障不再 goAvailable）；`vetQueue` 池恒可见、`awaitingPay`(单条) → `awaitingPays`(列表)（**API 契约变更**，App `VetQueue` 模型同步）。
  - 不动：V1.0 免费直连流（`ConsultAcceptService`）与 `ConsultCloseService.goAvailable`（对计费流是 no-op，兼容遗留）；在线态显式模型（vet-presence-explicit-only）不变；广播本就发全部在线兽医，无需改。
  - FR-53B 前端判成交改为「待支付 token 集合差 + 进行中会话数增量」。

## 2026-08-16 追加决策（V1.1.4 Story 1.3：评论数与互动量口径）

**任何后续依赖「评论数」或「互动量」的功能，先读完这条再动手。** 隐藏关系有两条过滤，语义完全不同：

- **R1（按查看者）**：查看者自己隐藏了这个人（主动拉黑或举报，**不分来源**）→ 那条评论**只是他不看**，对平台真实存在、对其他所有人公开可见。
- **R2（影子评论，按内容作者）**：**内容作者**隐藏了这个人 → 那条评论对**所有人**都不存在，**只有写它的人自己看得见**（无感知机制）。

口径分两层，**别混为一谈**：

| 层 | 代表方法 | R1 | R2 |
|---|---|---|---|
| **面向查看者**（「他这一屏能看到几条」） | `CommentRepository.countVisibleForViewer` | **套** | **套** |
| **平台口径 / 互动量统计**（「这是不是一次真实互动」） | `CommentRepository.countByRealAuthor` 及后续任何热度 / 排序 / 选品指标 | **不套**（照常计入） | **不套用即错——必须排除**：影子评论及其回复串**不得计入任何互动量**，否则骚扰账号的评论会变成「热度」 |

- 面向查看者的数字**必须与实际渲染出来的条数一致**。少套 R1 就会出现「标题写着评论 (5)，往下数只有 4 条」的穿帮——那正是 AD-13 列为首要防范的现象。
- **评论作者本人视角下数字比他人多 1**，是影子机制的固有特性（A-A21），**可接受，不要去「修」**。
- ⚠️ **AD-13 的「评论数」那一条把「同步套用 R1 + R2」与「R1 隐藏的照常计入」写在同一句里，字面互斥**。Story 1.3 实现时按该条自己列的 Prevents 首项裁定为上表的两层分工。**若产品另有判断，要改的是 `countVisibleForViewer` 里 R1 那两行 WHERE**（外加 `CommentHideFilterIntegrationTest.ac5_r1HiddenCommentAlsoKeepsCountAndRenderedRowsInSync`）。
- **回复串随父隐藏**：父被任一条过滤挡住，整串对该视角一并不展示（不出现「回复了某条看不见的评论」的孤儿回复）。`replyCount` 取 `findRepliesForParents` 的返回条数，过滤写进 WHERE 后**天然就是过滤后的数**。


## 2026-08-16 追加决策（V1.1.4 Story 2.1：账号举报与内容举报是两套东西）

**动账号举报之前先读这条。** 代码里两套举报并存，命名相近、语义相反，照抄是最大的风险：

| | 内容举报 `content_reports`（既有，Story 3.7） | 账号举报 `account_reports` + `_entries`（V1.1.4 Story 2.1） |
|---|---|---|
| 粒度 | 一条内容一个工单 | **一个被举报账号一个工单** |
| 重复举报 | 唯一键 `(reporter_id, post_id)` + service **两处裸 `return` 幂等吞掉，什么都不写** | **每次都追加一行明细**，类型与补充说明逐次留存 |
| 理由枚举 | `ReportReason`：ILLEGAL / MISINFO / INAPPROPRIATE / HARASSMENT / OTHER | `AccountReportReason`：SPAM / IMPERSONATION / HARASSMENT / VIOLATING_CONTENT / OTHER（**只有两项对得上，不复用**） |
| 自动预处置 | 有两条：`ILLEGAL` 单次触发 / 举报人数 ≥ 10（`P0_REPORT_COUNT_THRESHOLD`，硬编码在 Java、运营改不了）→ 内容挂起 | **零自动预处置**（AD-17），无论多少人举报都不动他的内容 |
| 频率限制 | 靠唯一键天然只有一次 | **不设冷却**（A-A23，主动决定）：可无限次举报。service 只有一道 **5 秒**去重窗口，防的是双击穿透与网络重试，**不是限制用户意图** |
| 副作用 | 无隐藏关系 | **举报即隐藏**：同一事务写一条 `source=REPORT` 的 `user_hide_relations` 行，任一失败整体回滚 |

- **「已举报」标记由 `source=REPORT` 行是否存在派生**（`UserHideRelationReader.isReported`），**不是前端会话态** —— 用户重装 App 也得看得到，否则会重复举报、污染运营看到的「12 人 / 27 次」。
- ⚠️ **`MiniProfileResponse.reported` 必须是装箱 `Boolean` 且游客时为 null**：全局 Jackson `NON_NULL` 会把 null 键整个省略，游客响应体的 key 集合因此一字未变（Story 1.1 AC6 的硬要求）。写成 primitive `boolean` 会永远出现在 JSON 里，当场破坏游客契约。
- ⚠️ **举报隐藏永不删除、无任何解除入口**，且不进黑名单页（黑名单只收 `BLOCK`）。**唯一例外是主页访问仍可进入** —— FR-58 闭环（「已举报」状态与重复举报入口）全靠它。


## 2026-08-16 追加决策（V1.1.4 Story 3.1：统一工单队列的三个口径）

**1）三个业务类别落在四张表上**（架构 AD-7 原写的 `manual_review_queue` 认定不成立，已按产品意图订正）：

| 类别 | 源表 | 工单粒度 |
|---|---|---|
| 内容举报 | `content_reports`（既有） | **按帖聚合**：12 个人举报同一条帖是**一条**工单 |
| 用户举报 | `account_reports`（V1.1.4 新增） | 一个被举报账号一条 |
| 账号标识字段 | `name_moderation_records` + `avatar_reviews`（两张既有表） | 一条送审记录一条 |

**读时联合，不建索引表、不双写、存量零回填、不加缓存**（AD-7）。各类工单的状态**仍由各自源表权威持有** —— 一旦双写，「工单在哪张表里是真的」立刻变成需要对账的问题。

**2）优先级公式（改之前先看那四条测试）**

```
分 = 举报人数 + 高频举报人数
举报人数     = 举报过该对象的不同账号数（去重）
高频举报人数 = 其中对该对象累计举报 ≥5 次（含 5）的账号数
```
- **单个举报人的贡献上限恒为 2 分**（1 基础 + 1 高频）。这是设计目的不是巧合：**众怒要排在纠缠前面** —— 一个人刷 100 次只有 2 分，十个人各报一次是 10 分。
- 账号标识字段那一类**没有举报人**，分数按各表的 `priority` 映射：**`HIGH → 10` / `NORMAL → 2`**（C-102）。⚠️ **`NORMAL` 不得映射成 0** —— 它是那两张表的 DEFAULT 值、绝大多数记录都是它，映射成 0 会让这类工单集体永远沉底。
- 分数**实时算、不落库快照**；排序 = 分倒序 + **同分按最早一次举报时间升序**（先报的先处理）。
- **展示必须拆开**：举报人数 / 举报次数 / 高频人数 / 总分四个数并列。只看次数会把「1 个人报了 27 次」当成众怒；只看人数会丢掉「同一人反复纠缠」；只给总分等于让运营对着黑盒排队。

**3）账号标识字段两表的三处不同构（联合查询里已固化，改动前先读注释）**

| | `name_moderation_records` | `avatar_reviews` |
|---|---|---|
| 待处理锚点 | `MANUAL_PENDING` | `MANUAL_PENDING`（**两表唯一的共同状态**） |
| 违规终态 → 已处理 | `RESOLVED_VIOLATION` | `RESOLVED` + `verdict='VIOLATION'` |
| 通过终态 → 无需处置 | `RESOLVED_PASS` | `RESOLVED` + 其余 verdict |
| 时间锚点 | `submitted_at` | `created_at` |

其余状态（`SCORING`/`QUEUED`/`AUTO_PASSED`/`SUPERSEDED`/`FAILED_TO_QUEUE`）**一律不进运营队列**。
⚠️ **PII 红线**：`submitted_value`（送审名称原文）与 `avatar_url` **可以在工单里展示**（那正是运营要看的证据），**严禁写入任何日志**。

**4）第三档状态叫「无需处置」不叫「已驳回」**（C-103）：这一档里混着「审核通过、本来就没问题」的记录。⚠️ **改的只是展示层文案** —— 数据层的 `DISMISSED` 值、以及举报侧的「驳回」动作按钮**都不改**（对举报而言「驳回」是准确的）。


## 2026-08-16 追加决策（V1.1.4 Story 3.4：FR-51 举报回告文案有意变更）

**旧**：「举报已处理 / 感谢你的举报，我们已完成审核。」
**新**：「举报已处理 / **你的举报已处理，感谢你帮助维护社区环境**」（PRD §6 定稿）

- ⚠️ **这不是修 bug，是产品定稿的文案变更。** 日后拿线上文案与更早的文档比对时，别以为是谁改错了又改回去。
- ⚠️ **影响面不止本版本**：这条通知是**内容举报与账号举报共用的同一条**，所以**已经上线的内容举报回告也跟着变了 —— 有意为之**。
- 仍**不透露具体处置结果**（继承 FR-51 既有口径）；`deepLinkType` / `targetRef` / 通知 type 一概未动，**无 Flyway 迁移**。
- ⚠️ **同一句话落在三处，改一处就会前后不一致**：

| # | 位置 | 谁在用 |
|---|---|---|
| ① | `ModerationNotifyListener` 的 `send(title, body)` | 落库的通知行（数据记录） |
| ② | `messages_{zh_CN,en,id}.properties` 的 `notify.REPORT_REVIEWED.*` | **离线推送**（按收件人语言渲染） |
| ③ | **App 的 ARB `notifyBodyReportReviewed`** | **站内通知中心 —— 用户真正看到的那句** |

漏改任何一处的表现是「**站内看到新文案、推送收到旧文案**」，用户会觉得平台在自说自话。三处都有测试守着（后端 `ReportReviewedCopyTest`，前端通知中心用例）。
- 印尼语措辞随 OQ-A4 同批送运营确认，**不阻塞发版**。
