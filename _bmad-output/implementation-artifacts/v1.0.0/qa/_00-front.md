# TailTopia V1.0 · 全量人工测试流程文档

> **范围**：覆盖 TailTopia 双产物全部 8 个 Epic、54 个 Story、35 个页面（宠物主 App + 兽医工作台 + 运营后台）的全部功能、全部状态。
> **用途**：QA / 验收照此逐条执行；研发自测对照；上线前回归基线。
> **生成依据**：`_bmad-output/implementation-artifacts/`（54 份 story 的 AC）+ `CROSS-STORY-DECISIONS.md`（权威决策，遇冲突以其为准）+ 前端页面源码 + 后端契约。
> **版本**：对齐当前 `main` 实现态（2026-06）。共 **597 条测试用例**。
> **语言约定**：测试步骤用中文；引用的 UI 文案保留 App 实际渲染的 **en / id**（**App 绝不渲染中文** —— 见 §i18n 专项）。

---

## 1. 如何使用本文档

1. 先读本前置篇，准备好 **§3 环境** 与 **§4 账号矩阵**——大量用例的前置依赖这两张表。
2. 主体按 Epic 分章（Epic 1→8）。每条用例标了 **层级 L0/L1/L2**，按你当前能跑的环境筛选执行。
3. 执行顺序建议：先跑 **§冒烟清单（横切篇）** 确认主链路通，再按 Epic 逐章深测。
4. **⚠️安全** 标记的用例是**阻塞发布项**——任一失败，不予上线。
5. 跨页 / 跨 Epic 的端到端验收走 **横切篇 §E2E 主链路**。
6. 每条用例执行后记录：通过 / 失败（附现象+截图）/ 阻塞（环境不具备）。

---

## 2. 验证分层（L0 / L1 / L2）

| 层级 | 含义 | 需要的环境 | 谁来跑 |
|---|---|---|---|
| **L0 静态** | 不需 DB、不需凭证即可验 | `flutter analyze` / `flutter test` / `mvn -B compile\|package`；纯单测、契约序列化金标 test | 云端 CI 可全自动 |
| **L1 集成** | 需真实 DB/缓存跑通 | Docker daemon + PostgreSQL + Redis；`mvn spring-boot:run` 且 `/actuator/health=UP`；异步状态机、Flyway 迁移、事件订阅、@Scheduled 扫描 | 本地 / 具备 Docker 的 CI |
| **L2 端到端** | 需真实第三方凭证 / 真机 / 视觉 | Google OAuth、Gemini、腾讯 IM、OSS、FCM/APNs 推送；iOS/Android 真机或模拟器；视觉/动效/无障碍走查 | **必须本地 / 真机**（云端 headless 不可） |

> 云端 headless 只能跑到 **L0 绿灯**；**L1/L2 默认留本地**。每章末「遗留/盲区」列出了仅 L2 可覆盖的项。

---

## 3. 环境矩阵

执行前按需准备。同一用例若标注多 locale / 多平台，需在对应组合各跑一遍。

| 维度 | 取值 | 说明 |
|---|---|---|
| **平台** | iOS（真机/模拟器）、Android（真机/模拟器） | portrait-only；V1 仅浅色 |
| **语言 locale** | `en`、`id`（印尼语） | 双语逐屏验；切「跟随系统」需测系统语言切换 |
| **后端模式** | Mock（`PETGO_MOCK=true`，debug 默认）、真后端（`--dart-define=PETGO_MOCK=false`） | Mock 离线全流程；契约/联调以**真后端**为准（决策 C4） |
| **后端栈** | Spring Boot 4 / Java 21 / PostgreSQL + Redis + Flyway | L1 需 Docker pg+redis；`ddl-auto=validate` |
| **第三方凭证（L2）** | Google OAuth client、Gemini API key、腾讯 IM（SDKAppID+SecretKey）、OSS 双桶+STS、FCM/APNs | env 注入，绝不入库；缺凭证则相关 L2 用例阻塞 |
| **运营后台** | `/admin/**`（role=ADMIN，Thymeleaf） | Epic 3 起；兽医开户/举报审核/种子内容 |

---

## 4. 账号 & 状态矩阵

多数门控 / 分支用例依赖账号态。预置以下账号/数据：

| 代号 | 账号/状态 | 用途 |
|---|---|---|
| **G** | 游客（未登录） | 只读浏览、受控入口登录门控、第 3 页软登录浮层 |
| **N** | 新用户（登录后未完成引导） | 引导流：昵称→宠物状态→建档→庆祝页 |
| **A** | 状态 A `HAS_PET`（有宠） | 建档、成长档案、里程碑、问诊、名片 —— 主力账号 |
| **A0** | 状态 A 但**无档案** | 成长档案空态、建档引导提示条 |
| **B** | 状态 B `PLANNING`（计划养） | 非有宠态视图、状态硬过滤、改状态入口 |
| **C** | 状态 C `ENTHUSIAST`（爱好者） | 同上，区分文案/可见内容 |
| **O** | 老用户（已有数据） | 登录后直接进 App（非引导） |
| **V** | 兽医（role=VET） | 工作台、抢单、IM 会话、评分门 |
| **V-ban** | 被封兽医 | 封禁后进行中会话中断处理 |
| **ADM** | 运营 ADMIN | 后台开户、举报审核、内容下架、种子内容 |

> 单账号单宠物约束（决策 F6）：A 账号已建档后不可再建第二只；`pet_type`(CAT/DOG/OTHER) 创建后不可改。

---

## 5. 缺陷分级

| 级别 | 定义 | 处置 |
|---|---|---|
| **S0 阻断** | 主链路不可用 / 数据丢失 / **安全攸关失效**（红态变现、注销未级联、安全规则被绕过、PII 泄漏） | 立即停发，热修 |
| **S1 严重** | 核心功能失败、无 workaround、崩溃 | 上线前必修 |
| **S2 一般** | 次要功能异常、有 workaround、文案/状态错 | 排期修 |
| **S3 轻微** | 视觉瑕疵、非阻塞体验问题 | 择期 |

---

## 6. 页面 × 路由总览（35 页）

> 以 `petgo_app/lib/core/router/app_router.dart` 为准；详细用例见对应 Epic 章。

| Epic | 页面 | 路由 | 端 |
|---|---|---|---|
| 1 | LoginPage | `/login` | 主 |
| 1 | DevLoginGuidePage | `/dev/login-guide` | 主 |
| 1 | NicknamePage | `/onboarding/nickname` | 主 |
| 1 | PetStatusPage | `/onboarding/pet-status` | 主 |
| 1 | MintOnboardingPage | `/onboarding` | 主 |
| 1 | ProfileOnboardingPage | `/onboarding/profile` | 主 |
| 1 | HomePage（Tab） | `/home` | 主 |
| 2 | PetProfileCreatePage | `/profile/create` | 主 |
| 2 | ProfileCreatedCelebrationPage | `/profile/created` | 主 |
| 2 | PetProfileEditPage | `/profile/edit` | 主 |
| 2 | GrowthArchivePage（Tab） | `/profile` | 主 |
| 2 | DayDetailPage | `/profile/day` | 主 |
| 2 | PetCardPage | `/card/preview` | 主 |
| 2 | PublishComposePage / PublishLandingPage | `/publish` | 主 |
| 3 | ContentDetailPage | `/content/:id` | 主 |
| 3 | （Feed/点赞/评论/举报/迷你主页 —— HomePage 内 + 浮层） | `/home` | 主 |
| 3 | 运营后台 | `/admin/**` | 后台 |
| 4 | TriagePage（Tab） | `/triage` | 主 |
| 4 | TriageUploadPage | `/triage/upload` | 主 |
| 4 | TriageResultView / TriageRedResult | （结果态，TriagePage 内） | 主 |
| 4 | DevTriagePage | `/dev/triage` | 主 |
| 5 | ConsultEntryPage | `/consult` | 主 |
| 5 | ConsultWaitingPage | `/consult/waiting/:id` | 主 |
| 5 | ConsultConversationPage | `/consult/conversation/:id` | 主 |
| 5 | VetLoginPage | `/vet/login` | 兽医 |
| 5 | VetWorkbenchShell | `/vet/workbench` | 兽医 |
| 5 | VetInbox / RequestDetail / Conversation / Active / History / Me | `/vet/**` | 兽医 |
| 6 | NotificationCenterPage | `/notifications` | 主 |
| 6 | （铃铛/推送权限引导/版本更新弹窗 —— 浮层） | 全局 | 主 |
| 7 | MePage（Tab） | `/me` | 主 |
| 7 | SettingsPage | `/me/settings` | 主 |
| 7 | LanguageSettingsPage | `/me/language` | 主 |
| 8 | MilestoneListPage | `/profile/milestones` | 主 |
| 8 | （三级庆祝动效/打卡弹窗 —— 浮层） | GrowthArchive 内 | 主 |
| — | GathPage | `/gath` | 主 |

> 底部 Tab（en locale）：**Home / Growth / [+] / Consult / Me**；中间「+」为统一发布入口。

---
