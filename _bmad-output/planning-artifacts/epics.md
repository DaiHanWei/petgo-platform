---
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - _bmad-output/planning-artifacts/PRD.md
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/planning-artifacts/UX_DESIGN.md
  - _bmad-output/planning-artifacts/UX_EXPERIENCE.md
  - _bmad-output/planning-artifacts/PetGo_V1_mockups.html
project_name: 'PetGo V1.0'
user_name: 'Dai'
date: '2026-06-01'
---

# PetGo V1.0 - Epic Breakdown

## Overview

本文档将 PRD（46 个 FR / 8 模块）、UX 设计规范（DESIGN + EXPERIENCE）与架构决策文档分解为可实现的 Epic 与 Story。范围严格限定 V1.0：双核心功能（专业问诊 + 成长档案）+ 支撑模块（登录引导、Feed、互动、推送、兽医端、个人中心、运营后台）。所有 V2 功能（攒局/支付/视频/搜索/多宠/付费会员等）明确排除。

## Requirements Inventory

### Functional Requirements

> 来源：PRD §4。字母变体（0A–0H / 4A·4B / 22A–E）按独立可测需求列出，故计 46 条；架构文档的"39 FR"为合并字母变体后的计数，二者一致。已移至 V2 的 FR-6~10、FR-13 不在 V1 范围。

**模块 A — 登录与引导（auth / onboarding）**
- FR-0A: 未登录用户可直接浏览首页内容流，无强制登录拦截；问诊/档案/「+」入口可见但点击触发登录引导，未登录不可提交数据。
- FR-0B: 未登录浏览至首页第3页时，底部弹起半屏软性登录推荐浮层（主 CTA「Google 登录」显著、关闭按钮弱化）；每 session 最多触发一次。
- FR-0C: 未登录点击核心功能（AI 问诊/兽医咨询/发布/创建档案）触发登录引导弹窗（强度高于 FR-0B），登录成功后自动回到触发点继续操作。
- FR-0D: Google OAuth 登录/注册（V1 唯一方式）；首次授权自动建号；获取 Google ID/Email/Display Name/头像；按钮下方展示《服务条款》《隐私政策》文字链接（Text Link 模式，无需勾选）。
- FR-0E: 新用户首次授权后进入昵称确认页，默认读取 Google Display Name 可改，≤20 字。
- FR-0F: 新用户必选宠物状态（A 有宠物 / B 计划养 / C 爱好者），影响首页内容硬过滤方向，后续可在设置中修改。
- FR-0G: 选状态 A 的用户进入档案创建引导（复用 FR-11 表单），底部提供「跳过，稍后创建」。
- FR-0H: 仅「状态 A 且未完成档案」用户首页顶部显示档案提示条（含「立即创建」CTA + 关闭）；前 3 次重启显示，第 3 次关闭后或完成上传后永不再显示。

**模块 B — 专业问诊（triage / consult）**
- FR-1: AI 图文分诊——上传 ≤3 张图（JPG/PNG/HEIC，单张 ≤10MB）+ 文字症状描述，提交后 ≤15s 同屏返回三项（危险等级绿/黄/红 + 观察建议 + 居家用药参考）+ 免责声明；含超时（>15s）与服务异常重试（复用上次提交内容，不需重传）。
- FR-2: 黄色区间条件倒计时协议——观察建议必须含具体指标 + 时间窗口 + 升级触发条件；不得出现终结性表述；时间窗口/触发条件视觉可区分展示。
- FR-3: 红色半屏强提醒——半屏红色遮罩 + 强文案「⚠️ 请立即带宠物就医」，5s 内不可关闭；含「是否打开地图导航」确认；不展示兽医咨询入口、零变现引流。
- FR-4A: AI 问诊入口——问诊模块内与兽医咨询平级，V1 免费无支付。
- FR-4B: 兽医咨询入口——与合作机构派驻兽医实时图文沟通；同时仅 1 个进行中咨询；在线态概率性展示（非实时人数）；无人接单 1min 超时（继续等待/改用 AI，原请求保留 + 接单后推送）；主动取消需二次确认；AI 升级时完整传递评级/描述/图片；离线态软引导回 AI。
- FR-5: 兽医接单辅助——按症状关键词自动匹配历史判断摘要（冷启动为空时仅展示 AI 参考回复）+ 用户侧自动免责提示；历史库依真实问诊数据自动累积。

**模块 C — 成长档案（profile）**
- FR-11: 宠物档案创建——头像/名字/品种/生日/一句话介绍（≤30 字）；V1 单账号单宠物；创建后自动生成对外名片链接（FR-14）。
- FR-12: 统一「+」发布入口——编辑页内标签选内容类型（日常分享/专业知识科普/成长日历快乐时刻）；成长日历需绑定宠物（B/C 灰置不可选）；文字 ≤1000 字符实时计数；图片 ≤9 张（单张 ≤10MB），V1 不支持视频；默认全平台公开；含上传失败仅重传失败件、无持久草稿。
- FR-14: 宠物名片对外分享页——H5 仅展示「快乐时刻」最近 5 条照片流 + 宠物信息头部；加载 ≤3s；底部 App 下载引导；Open Graph 标签支持 WhatsApp/Line/Instagram 链接预览。
- FR-15: 档案页动效分享 FAB——右下角大号 FAB 持续显示，首访触发动效后转静态；点击调用系统分享传入名片链接；B/C 用户无档案时 FAB 不显示。
- FR-16: 问诊记录存档（用户确认）——问诊结束弹「是否存入 [宠物名] 档案？」一次；存入后以健康事件形式进时间线（含日期/症状/AI 评级/处理建议摘要）。

**模块 D — 首页 Feed（content / feed）**
- FR-17: 首页 Feed 内容模型——全平台公开内容按时间倒序；按宠物状态硬过滤（A/C 三类全显、B 不显成长日历、未登录全显）；无关注关系、无算法推荐；状态修改即时刷新；Feed 卡片含作者/预览 2 行/首图（点赞评论数不在卡片）；无限滚动 20/批 + 下拉刷新。
- FR-18: 冷启动内容与 Feed 空状态——运营提前发布种子内容（与用户内容混排不标记）；Feed 为空显示引导发布空状态。

**模块 E — 导航（navigation）**
- FR-19: 底部 Tab Bar 导航——5 位（首页/成长档案/[+]/问诊/我的），中间 [+] 凸起；仅首页未登录可访问，其余触发 FR-0C；一级页常显、二级页隐藏；角标规则（问诊红点 + 铃铛未读总数并存）；问诊进行中切 Tab 不断连；兽医封禁中断会话的用户侧处理。

**模块 F — 个人中心与档案管理（me / profile）**
- FR-20: 「我的」页面结构——用户信息（头像/昵称 ≤20 字编辑）+ 宠物状态修改 + 我的发布（三类时间线）+ 退出登录 + 账号注销（PDP 强制，双重确认，匿名化保留内容+级联删除个人数据）+ 帮助反馈。
- FR-21: 成长档案 Tab 内宠物状态快捷编辑入口——与 FR-20 功能一致、效果同步，复用 FR-0F 状态选择界面。
- FR-27: 语言设置——id/en 双语；首次启动跟随设备语言（其他语言回退英语）；「我的→语言设置」手动切换即时生效；全部 UI/系统/错误文案双套；UGC 不受限制。
- FR-37: 成长档案 Tab 内部布局——宠物信息卡 + 分享 FAB + 垂直时间线（快乐时刻 + 健康事件倒序）；未创建/B·C 用户各自空状态引导。
- FR-39: 宠物档案编辑——可改头像/名字/品种/生日/介绍；两处入口复用同一编辑页；即时生效并同步名片 H5；不限编辑次数。

**模块 G — 推送通知与权限（notify）**
- FR-22A: 兽医回复推送——用户不在对话页时推送「问诊有新回复」，点击直达会话；正在查看不重复推送。
- FR-22B: 内容互动推送——被点赞/评论时推送，点击跳详情页；V1 逐条不合并。
- FR-22C: App 版本更新提醒（App 内提示，不需推送权限）——冷启动检测；推荐更新可「稍后」、强制更新不可跳过，跳商店。
- FR-22D: 应用权限申请与拒绝处理——相机/相册按操作触发时申请、拒绝引导去设置；推送权限在完成第一次问诊后才申请，拒绝后不再主动弹起。
- FR-22E: 兽医端新请求推送——用户提交兽医咨询时向所有在线兽医推送，点击跳工作台「待接单」。

**模块 H — 内容互动与详情（content interaction）**
- FR-23: 内容点赞——三类内容可点赞（开关可取消），即时更新计数；需登录触发 FR-0C；触发 FR-22B（自赞不通知）。
- FR-24: 内容评论（两级）——一级评论（≤200 字，时间正序）+ 二级回复（≤200 字，不再嵌套）；删除权限（作者删自己评论 / 内容主删任意评论，删一级级联删二级）；触发 FR-22B；需登录。
- FR-25: 内容举报——卡片「···」菜单举报（单选类型）；进运营人工审核队列，无自动下架；V1 不做评论/用户举报。
- FR-26: 其他用户迷你主页预览卡——点他人头像/昵称底部弹卡（头像/昵称/发布数 +「主页筹备中」文案）；无关注/查看主页按钮；措辞不得出现技术性表达。
- FR-28: 内容详情页结构——返回栏（含举报）+ 作者信息（触发 FR-26）+ 正文 + 图片左右滑（角标 x/y）+ 互动栏 + 评论区（首载 10 条一级、二级默认 3 条）+ 底部固定评论框；返回 Feed 保持滚动位置。
- FR-36: 内容删除（无编辑）——作者可删，二次确认；从 Feed 与时间线同步移除，点赞评论一并删除；V1 无编辑。

**模块 I — 兽医端与评分（vet）**
- FR-29: 兽医账号登录入口——登录页 Google 按钮下方「兽医登录」小字链接，跳账密登录页；无忘记密码（运营重置）；登录后跳工作台，不可访问用户侧。
- FR-30: 兽医工作台（独立 Tab Bar）——待接单/进行中/历史记录/我的（含在线离线切换）；接单流程；对话支持文字/图片/视频（腾讯 IM，视频 ≤60s）+ FR-5 辅助工具。
- FR-31: 问诊会话结束机制（评分确认门）——兽医点结束→待关闭态；用户 30min 内可继续发消息/评分立即关闭/超时自动关闭记「未评分」；关闭触发 FR-16 + FR-33。
- FR-32: 兽医在线状态管理——「我的」Tab 手动切在线/离线，即时影响用户侧 FR-4B 可用性显示与接单队列。
- FR-33: 问诊结束后用户评分——1-5 星必填 + ≤100 字选填，作为会话正式关闭确认门；评分仅运营可见；超时未评下次进问诊页补弹一次。

**模块 J — 通知中心与问诊历史（notify / history）**
- FR-34: 全局通知中心（铃铛）——首页顶部铃铛 + 未读红色角标，列表倒序展示四类通知，点击跳目标并标记已读。
- FR-35: 问诊 Tab 结构（含历史）——功能入口区（AI/兽医平级）+ 进行中会话卡 + 我的问诊历史列表（AI 评级/兽医评分/是否存档标记）；历史独立于 FR-16 存档保留。
- FR-38: 推送通知深链接规则——点击推送直达对应页（兽医回复→会话 / 问诊结束→评分 / 被赞→详情 / 被评→详情定位评论区）；冷启动/后台/前台三态均直达。

### NonFunctional Requirements

> 来源：PRD §7/附录 C + architecture §Requirements Overview / 非协商地基。PRD 未显式编号 NFR，下列为从约束与架构提炼的可测非功能需求。

- NFR-1（性能·分诊）: AI 图文分诊提交后 ≤15s 返回结果；超时进重试路径。
- NFR-2（性能·H5）: 宠物名片 H5 页加载 ≤3s（Thymeleaf 直出 + CDN + 预渲染 OG 静态图）。
- NFR-3（性能·Feed）: Feed 游标分页 20 条/批，无限滚动距底 ≤3~5 条预加载 + 下拉刷新。
- NFR-4（跨地域延迟）: 印尼↔德国动态 API ~320–360ms；以 BFF 式首屏聚合 + 静态走边缘 + 实时走 IM 消化，预留亚太只读副本演进位。
- NFR-5（实时性）: 兽医咨询切 Tab 后台保持连接、消息连续无中断（腾讯 IM 保证）。
- NFR-6（安全攸关·不可协商）: AI 假阴性由确定性安全规则层兜底——高危症状清单命中强制升红，置于模型返回后做后置校验，只升不降，独立于 Gemini；清单覆盖最高频致死 15–20 急症，由兽医顾问维护。
- NFR-7（隐私·媒体）: 私密图（分诊/健康历史）仅短 TTL 签名 URL；公开图走公开桶 + CDN；上传链路剥离 EXIF GPS；H5 名片用不可枚举 token + noindex 防枚举。
- NFR-8（隐私·合规地基）: 账号与数据删除级联（各表 + OSS 图片，设计之初纳入）+ 注销内容匿名化保留；PDP 基础（收集前同意 + 目的告知 + 真实隐私政策 + 数据主体权利可达）；跨境出境记为挂账风险。
- NFR-9（信任·医疗边界）: 红色等级页零兽医/变现引流入口；所有 AI/兽医输出附前置免责声明（小号字不干扰主流程）并可留同意/告知记录。
- NFR-10（可靠性·降级）: AI 超时/异常由 DB 状态机驱动重试（≤3）+ 软引导兽医；上传失败仅重传失败件，文字 session 内存保留，无持久草稿；分诊提交带幂等键去重。
- NFR-11（国际化）: 全部 UI 文案/系统提示/错误信息提供 id + en 两套；跟随设备语言回退英语，即时切换。
- NFR-12（安全·鉴权）: JWT 角色门控（user/vet/admin claim）+ 游客态；兽医密码 BCrypt；Cloudflare 隐藏德国源站 IP + 仅放行回源；写端点 Redis 令牌桶限流 + Idempotency-Key。
- NFR-13（无障碍）: 文本/图标对比度 WCAG AA（≥4.5:1，disclaimer ≥3:1）；触摸目标 ≥44×44pt；三色态用 icon+text+color 不依赖颜色单一；红色 alert 以 alertdialog/assertive 播报；动态字体支持。
- NFR-14（平台）: iOS + Android 原生（Flutter 单代码库）；portrait-only；V1 仅浅色模式，深色延至 V2；不做 Web 端（H5 名片为唯一 Web 触点）。

### Additional Requirements

> 来源：architecture.md（Starter / 数据 / 基建 / 模式 / Gap 处置）。这些技术与基建要求直接塑形 Story 拆分与序列，尤其影响 Epic 1（脚手架与地基）。

**脚手架（架构指定为「实现阶段第一个 Story」）**
- 移动端：`flutter create --org com.petgo --project-name petgo petgo_app` + Riverpod + go_router + dio + intl/flutter_localizations（id/en，Day-1）+ flutter_lints。
- 后端：`spring init`（Maven / Spring Boot 4.0.x / Java 21 LTS）依赖 web,validation,data-jpa,postgresql,data-redis,oauth2-resource-server,security,thymeleaf,actuator,flyway,lombok。
- 基建骨架：Docker Compose（app + PostgreSQL + Redis，德国单机 45.90.122.44）+ Cloudflare 前置接入 + GitHub Actions CI 骨架。

**数据与存储**
- PostgreSQL 单实例 + Flyway 版本化迁移（`V<序号>__<描述>.sql`）+ JSONB 存 Gemini 原始响应；每日 pg_dump 备份至 OSS 私密桶。
- Redis 收窄：仅 auth 限流 + 幂等键/防重锁 + 兽医在线态 + 问诊队列态 + 未读角标计数（不做通用缓存、不当队列、不上 Cluster）。
- 媒体三层：阿里 OSS 雅加达公开桶（Feed/档案/名片图 + 阿里 CDN）/ 私密桶（分诊/健康图 + 签名 URL）/ 腾讯 IM 托管（聊天媒体）；客户端 STS 临时凭证直传；问诊存档时 IM 图复制一份到私密桶（档案只引用应用自有 URL）。

**异步与通信**
- 异步统一 DB 状态机（PENDING/PROCESSING/DONE/FAILED + retry_count + 启动重扫）+ Spring `@Async` + 进程内领域事件（`ApplicationEventPublisher` + `@Async @EventListener`）；**禁止引入 MQ/新中间件**。
- 会话状态机：`WAITING → IN_PROGRESS → PENDING_CLOSE → CLOSED / INTERRUPTED`，超时/评分门为迁移触发器，全在 Java 侧编排腾讯 IM。
- API：REST `/api/v1`、ProblemDetail（RFC 9457）统一错误信封、游标分页 `{items,nextCursor,hasMore}`、springdoc-openapi 3.1、`Idempotency-Key`/`Accept-Language` 头；分诊异步契约 `POST /triage→202+triageId`，客户端短轮询或收 IM 推送。

**外部依赖**
- Google Gemini Developer API（gemini-2.5-flash，签名 URL 直拉私密图）/ 腾讯云 IM（实时聊天 + 离线推送）/ Google OAuth / 系统地图深链。接口层抽象 `GeminiClient` 便于未来迁 Vertex。

**安全规则层（不可协商地基）**
- `triage/service/SafetyRuleLayer` + `resources/safety/high_risk_symptoms.yml`（兽医顾问维护）；后置强制升红，只升不降，绝不被绕过。

**运营/Admin slice（Gap G-1 补齐）**
- 新增 `com.petgo.admin/`：`role=ADMIN` 受保护端点 + 极简 Thymeleaf 后台，复用各模块 service，承载：兽医账号 CRUD（FR-29）、举报工单处理/人工下架（FR-25）、种子内容发帖（FR-18）、评分查看（FR-33）、封禁兽医（FR-19）。不做独立系统。

**H5 名片与部署**
- H5 名片：Thymeleaf `GET /p/{cardToken}` 服务端直出 + OG/Twitter meta + noindex；生成卡片即预渲染 OG 静态图存公开桶。
- 部署：德国单机 Docker Compose + GitHub Actions（后端 jar+镜像→部署；Flutter V1 手动上架）；Spring profiles dev/prod + env 注入凭证（不入库）。
- 可观测：Actuator 健康/指标 + 轻量在线监测（UptimeRobot 类）+ logback JSON 结构化日志；严禁记录 PII/健康数据/令牌/签名 URL。

**实现序列（架构建议，作为 Epic 排序参考）**
1) 脚手架 + 基建 → 2) auth（其余前置）→ 3) 媒体基建 → 4) triage（含安全规则层）→ 5) consult → 6) content + profile → 7) notify + moderation → 8) me/i18n；admin slice 穿插。

**其他 Gap 处置**
- G-2（FR-5 历史匹配）：V1 先实现 AI 参考回复，历史关键词匹配作为 consult 内查询后置增强。
- G-3（Gemini 数据出境）：与 PDP 跨境暂缓一致挂账；接口层已抽象便于迁 Vertex。
- G-4（EXIF/GPS）：媒体上传链路（`shared/media`）剥离 EXIF GPS，防 H5 公开页定位泄漏。

### UX Design Requirements

> 来源：UX_DESIGN.md（视觉系统）+ UX_EXPERIENCE.md（交互行为/状态/流程）。每条 UX-DR 均可独立生成带可测验收标准的 Story。

- UX-DR1: 设计 token 系统落地——将 colors（base/text/zone accent: 焦糖 #C8874A 成长区·莫兰迪蓝 #7BA7BC 问诊区 / triage 语义三色）、typography scale（display→micro + badge/button/disclaimer）、rounded（xs→full + phone）、spacing（xxs→section + 布局量）、elevation（card/nav/modal/fab/overlay）全量 token 化至 `core/theme`；页面底色恒为 #FAF8F5，不因 Tab 切换整屏变色。
- UX-DR2: Bottom Tab Bar 组件（FR-19）——5 位、中间凸起「＋」（44px 圆 + 3px 白描边 + 约 1/3 高度突出/上移约 20px）、上沿分割线在「＋」位向下内凹弧形缺口（CircularNotchedRectangle，凹深约 18px/宽约 60px）、active 34×34 区域色填充圆 + 白图标、inactive 18px 图标 + 9px 标签、「＋」颜色随 active Tab 区域色切换、切换 120ms 淡出淡入。
- UX-DR3: 全局发布 FAB（FR-15/FR-12）——悬浮 Tab Bar 上方右下（margin-right 16px / margin-bottom = nav+12px）、CSS pulse `scale 1.0→1.04` 2s（仅 idle≥30s 激活）、点击 Publish Compose 从底部滑入、仅全屏 modal 时隐藏。
- UX-DR4: Masonry 瀑布流卡片（FR-17）——2 列等宽 + 8px 列间距 + 16px 屏边距、图片区 80–200px 不裁切仅上圆角 14px、文字区 body-small 标题最多 2 行 + caption meta（时间 + 区域色心形数）、无限滚动（距底 ≤3 卡加载）、标准下拉刷新 + 区域色 indicator。
- UX-DR5: 首页 Tab Row（FR-17 内容分类）——横向 scroll 4 tab（全部/日常分享/成长日历/科普）、active 区域色 2px 下划线 + 文字色、切换内容区 cross-fade（不 slide，避免与底导航冲突）、成长日历 tab 仅显示有档案帖 + 空状态文案。
- UX-DR6: Triage Result Card 三态视觉（FR-1/2/3）——绿/黄：白底 rounded.md + 左 3px 区域色边框 + 对应 triage badge；黄色观察协议块用 accent-consult 浅底 #EEF4F7 rounded.sm；红色走半屏 overlay（UX-DR7）。
- UX-DR7: 红色半屏 Alert（FR-3）——① 锁定期 0–5s：半屏自底滑起、红底 #C97A7A、⚠️ 大白图标 + display 文案 + 副文、双按钮禁用并显倒计时；② 解锁后：主「去导航」触发系统确认 dialog、次「稍后处理」触发应用内「确认已了解风险？」二次确认；③ 关闭后结果页保留、不展示兽医 CTA；屏幕阅读器以 alertdialog/ASSERTIVE 播报，红底配 ⚠️ + 大字不依赖颜色。
- UX-DR8: 空状态模式——居中 emoji 插画区 + headline + subtext +（可选）CTA；至少覆盖首页 Feed「快来晒出你的毛孩子！🐾／发布第一条内容」、问诊历史「还没有问诊记录／开始问诊」、成长档案「还没有记录，快来留下第一个瞬间／立即记录」。
- UX-DR9: 加载态模式——Feed 骨架屏（灰 shimmer 同瀑布布局）、AI 分析中全屏居中动画 + 莫兰迪蓝 spinner + 文案、图片上传区域内进度条（accent-consult）。
- UX-DR10: 错误态模式——网络错误顶部 inline banner + 重试（5s 自消）、上传失败底部 toast（3s 自消）、兽医忙卡片态（「当前兽医较忙，预计等待 X 分钟」+ 继续等待/改用 AI）。
- UX-DR11: 导航转场原语——底导航 Tab 切换左右 slide 250ms ease-in-out（按相对位置定方向）、drill-down push（iOS 右→左 / Android 左→右）、modal/bottom sheet 自底上滑 300ms spring + 拖拽/点背景关闭、首页 Tab Row cross-fade。
- UX-DR12: 手势原语——下拉刷新（feed）、长按帖子 context menu（举报/分享）、滑动关闭 sheet/modal、瀑布图点击全屏 lightbox。
- UX-DR13: 微交互——FAB pulse、triage result 卡片 fade-in + badge bounce（0.8→1.1→1.0 300ms）、名片分享 FAB 首访 ripple 后续静态、active tab circle scale 0.7→1.0 spring 150ms、iOS haptics（FAB 点击 / 结果揭示）。
- UX-DR14: Voice & Tone 双语 microcopy 库——按场景沉淀 id+en 双套文案（空状态/分析中/分享引导/红色预警/免责声明/稍后处理确认等）；规则：每条最多 1 emoji、问诊结果页克制不用感叹号、红色预警简短无歧义。
- UX-DR15: 无障碍地基——落实 NFR-13 全部条款（AA 对比度、≥44pt 触摸目标、三色态非颜色单一依赖、disclaimer 3:1、动态字体 ≤3 级、红色 alert assertive 播报）作为可验收的设计/实现门槛。
- UX-DR16: Publish Compose bottom sheet（FR-12）——单页全屏自底 sheet：内容类型 Segment（全部/日常/成长日历/科普）→ 图片上传区 → 文字输入区（实时计数）→ 发布按钮；无独立页面跳转；B/C 用户成长日历项灰置 + 悬浮提示。
- UX-DR17: 平台适配——iOS（`.sheet` 呈现 Publish Compose、`UIImpactFeedbackGenerator` haptics、home indicator 安全区）+ Android（`BottomSheetDialogFragment`、edge-to-edge、MaterialRipple、FAB 抑制默认 ripple 用自定义 pulse）；portrait-only；dark mode 延至 V2。
- UX-DR18: 全局页面状态矩阵——**每个可导航页面 MUST 声明其在 6 个标准态下的展示**：① 加载中（骨架屏/spinner，承 UX-DR9）；② 空（emoji 空状态，承 UX-DR8）；③ 网络错误（顶部 banner + 重试，承 UX-DR10）；④ **资源不存在 / 已删除**（404 / 已下架 / 关联账号已注销 → 专门的"内容已不存在"占位页 + 返回，**统一文案不暴露资源是否曾存在以防枚举**）；⑤ **无权限**（403 越权 → 友好提示 + 返回；登录类拦截走 FR-0C，二者不混用）；⑥ **失效**（H5 名片 token / 推送深链目标失效或会话已关闭中断 → 友好失效页 + 下载/返回引导）。后端契约已由 ProblemDetail（404/403/409 + 本地化映射）提供，本规范规定其**页面级 UX 呈现**；陈旧入口（删内容 FR-36 / 运营下架 FR-25 / 注销 FR-20·7.3 + 推送深链 FR-38）落到已消失资源时一律走 ④/⑥，不得崩溃或白屏。

### FR Coverage Map

> 46/46 FR 全覆盖。每条 FR 映射到唯一主责 Epic（共享/横切处加注）。

- FR-0A: Epic 1 — 游客首页只读浏览（Feed 内容在 Epic 3 落地）
- FR-0B: Epic 1 — 滚动触发软性登录推荐浮层
- FR-0C: Epic 1 — 核心功能触发登录引导弹窗（贯穿各 Epic 复用）
- FR-0D: Epic 1 — Google OAuth 登录/注册 + 条款隐私链接
- FR-0E: Epic 1 — 新用户昵称确认
- FR-0F: Epic 1 — 宠物状态选择（A/B/C 硬过滤方向）
- FR-0G: Epic 1 — 状态 A 档案创建引导（复用 FR-11 表单）
- FR-0H: Epic 1 — 首页宠物档案提示条（3 次重启逻辑）
- FR-1: Epic 4 — AI 图文分诊（≤15s 三项输出）
- FR-2: Epic 4 — 黄色区间条件倒计时协议
- FR-3: Epic 4 — 红色半屏强提醒 + 导航
- FR-4A: Epic 4 — AI 问诊入口
- FR-4B: Epic 5 — 兽医咨询入口（排队/超时/升级/离线态）
- FR-5: Epic 5 — 兽医接单辅助（AI 参考回复 + 历史摘要；AI 参考回复部分在 Epic 4 产出）
- FR-11: Epic 2 — 宠物档案创建（单账号单宠物）
- FR-12: Epic 2 — 统一「+」发布入口（三类内容共享写入路径）
- FR-14: Epic 2 — 宠物名片对外 H5 分享页
- FR-15: Epic 2 — 档案页动效分享 FAB
- FR-16: Epic 2 — 问诊记录存档（存档 UI/承接；触发点在 Epic 4/5）
- FR-17: Epic 3 — 首页 Feed 内容模型（硬过滤/无限滚动）
- FR-18: Epic 3 — 冷启动种子内容 + Feed 空状态（含 Admin 发帖）
- FR-19: Epic 1 — 底部 Tab Bar 导航（外壳 + 门控；各 Tab 目的地由对应 Epic 填充）
- FR-20: Epic 7 — 「我的」页面结构（含注销）
- FR-21: Epic 2 — 成长档案 Tab 内宠物状态快捷编辑入口
- FR-22A: Epic 6 — 兽医回复推送
- FR-22B: Epic 6 — 内容互动推送（点赞/评论）
- FR-22C: Epic 6 — App 版本更新提醒（App 内）
- FR-22D: Epic 6 — 应用权限申请与拒绝处理
- FR-22E: Epic 6 — 兽医端新请求推送
- FR-23: Epic 3 — 内容点赞
- FR-24: Epic 3 — 内容评论（两级结构）
- FR-25: Epic 3 — 内容举报（含 Admin 人工审核队列）
- FR-26: Epic 3 — 其他用户迷你主页预览卡
- FR-27: Epic 7 — 语言设置（id/en；脚手架在 Epic 1，逐屏文案各 Epic 贡献）
- FR-28: Epic 3 — 内容详情页结构
- FR-29: Epic 5 — 兽医账号登录入口（含 Admin 账号 CRUD）
- FR-30: Epic 5 — 兽医工作台（独立 Tab Bar）
- FR-31: Epic 5 — 问诊会话结束机制（评分确认门）
- FR-32: Epic 5 — 兽医在线状态管理
- FR-33: Epic 5 — 问诊结束后用户评分（含 Admin 评分查看）
- FR-34: Epic 6 — 全局通知中心（铃铛）
- FR-35: Epic 5 — 问诊 Tab 结构（含历史记录）
- FR-36: Epic 3 — 内容删除（无编辑）
- FR-37: Epic 2 — 成长档案 Tab 内部页面布局
- FR-38: Epic 6 — 推送通知深链接规则
- FR-39: Epic 2 — 宠物档案编辑

**横切 Admin（G-1）落点**：Admin shell + `role=ADMIN` 门控（Epic 3 首建）· 种子内容发帖 FR-18（Epic 3）· 举报审核 FR-25（Epic 3）· 兽医账号 CRUD FR-29（Epic 5）· 评分查看 FR-33（Epic 5）· 封禁兽医（Epic 5）。

## Epic List

### Epic 1: 应用地基与账户接入
用户能装上 App、以游客身份浏览、用 Google 登录、完成新手引导（昵称 + 宠物状态三选一），未登录触发软/强登录引导；开发侧获得可运行的双产物脚手架、Docker Compose + Cloudflare + CI 骨架、设计 token 系统、JWT 角色门控、底部 Tab Bar 外壳与 i18n 脚手架。这是其余一切的前置地基，自身交付"有账户、能进门"的完整价值。
**FRs covered:** FR-0A, FR-0B, FR-0C, FR-0D, FR-0E, FR-0F, FR-0G, FR-0H, FR-19
**支撑 NFR/UX/Additional:** NFR-11(i18n 脚手架), NFR-12(鉴权/限流/错误规范), NFR-13, NFR-14; UX-DR1(设计 token), UX-DR2(Tab Bar 凸起+弧形缺口), UX-DR11(转场), UX-DR17(平台适配); 脚手架两条 init 命令 + Docker Compose + Cloudflare + GitHub Actions CI。

### Epic 2: 成长档案与宠物名片分享
有宠用户创建宠物档案、通过统一「+」入口记录快乐时刻（并发布日常分享/专业科普）、查看成长时间线、生成并分享对外 H5 宠物名片，闭合产品的病毒拉新飞轮。建立媒体三层基建作为后续上传场景的共享地基。
**FRs covered:** FR-11, FR-12, FR-14, FR-15, FR-16, FR-21, FR-37, FR-39
**支撑 NFR/UX/Additional:** NFR-2(H5 ≤3s), NFR-7(媒体隐私/EXIF 剥离), NFR-10(上传降级); UX-DR3(分享 FAB), UX-DR13(微交互), UX-DR16(Publish Compose); 媒体三层(OSS 双桶/STS 直传/签名 URL)、H5 Thymeleaf 名片 + OG 预渲染静态图。

### Epic 3: 社区 Feed 与内容互动
用户（含游客只读）浏览全平台 Feed（宠物状态硬过滤、无限滚动 20/批、下拉刷新）、进内容详情页、点赞与两级评论、删除自己内容、举报、查看他人迷你主页卡；运营可发布种子内容并人工处理举报队列。消费 Epic 2 的发布能力，交付社区发现与互动层。
**FRs covered:** FR-17, FR-18, FR-23, FR-24, FR-25, FR-26, FR-28, FR-36
**支撑 NFR/UX/Additional:** NFR-3(Feed 游标分页), NFR-8(注销内容匿名化在 Feed 体现); UX-DR4(瀑布流卡片), UX-DR5(首页 Tab Row), UX-DR8(空状态), UX-DR9(加载态), UX-DR10(错误态), UX-DR12(手势); Admin shell + role=ADMIN（首建）+ 种子内容发帖 + 举报人工审核队列。

### Epic 4: AI 智能分诊
用户上传图文获得绿/黄/红三级分诊（≤15s，含观察建议与居家用药参考）、黄色条件倒计时协议、红色半屏强提醒并可一键导航就医、结果可存入宠物档案；确定性安全规则层独立兜底 AI 假阴性。交付产品第一根生命安全支柱。
**FRs covered:** FR-1, FR-2, FR-3, FR-4A
**支撑 NFR/UX/Additional:** NFR-1(分诊 ≤15s), NFR-6(确定性安全规则层·不可协商), NFR-9(红色页零变现/免责前置), NFR-10(超时重试 DB 状态机); UX-DR6(三态结果卡), UX-DR7(红色半屏 Alert); Gemini Developer API 接入、DB 状态机 + @Async、SafetyRuleLayer + high_risk_symptoms.yml、私密桶签名 URL 直拉图。

### Epic 5: 在线兽医咨询与兽医工作台
用户发起兽医图文/视频实时咨询（1min 无人接单超时、AI 升级上下文传递、30min 评分确认门、兽医封禁中断处理），兽医通过独立账号登录专属工作台接单、对话、结束、查看历史与评分；运营可创建兽医账号、查看评分、封禁。交付第二根问诊支柱与其供给侧。
**FRs covered:** FR-4B, FR-5, FR-29, FR-30, FR-31, FR-32, FR-33, FR-35
**支撑 NFR/UX/Additional:** NFR-5(切 Tab 不断连), NFR-9(免责前置), NFR-12(兽医 BCrypt/角色隔离); UX-DR10(兽医忙卡片态); 会话状态机(WAITING→IN_PROGRESS→PENDING_CLOSE→CLOSED/INTERRUPTED)、腾讯 IM 编排、IM→OSS 存档桥接、兽医账号 Admin CRUD + 评分查看 + 封禁。

### Epic 6: 推送通知、深链与通知中心
用户收到兽医回复、被点赞/被评论、App 版本更新等通知，点击推送（冷启动/后台/前台三态）直达对应内容页；首页铃铛通知中心汇聚所有未读并按时间倒序展示；推送权限在完成第一次问诊后才申请。
**FRs covered:** FR-22A, FR-22B, FR-22C, FR-22D, FR-22E, FR-34, FR-38
**支撑 NFR/UX/Additional:** NFR-12(权限/时机); 复用腾讯 IM 离线推送(底层 APNs/FCM)、go_router 深链接路由表、未读角标 Redis 计数。

### Epic 7: 个人中心、双语与账号注销
用户管理个人信息（头像/昵称）、查看我的发布、在 id/en 间即时切换语言、退出登录，并可按印尼 PDP 要求注销账号——级联删除个人数据与 OSS 图片、内容匿名化保留。收口全局双语文案与无障碍验收门槛。
**FRs covered:** FR-20, FR-27
**支撑 NFR/UX/Additional:** NFR-8(注销级联删除 + PDP 基础), NFR-11(双语逐屏收口), NFR-13(无障碍验收); UX-DR14(双语 microcopy 库), UX-DR15(无障碍地基); 注销级联删除作业（各表 + OSS 图片删除路径）。

## Epic 1: 应用地基与账户接入

用户能装上 App、以游客身份浏览、用 Google 登录、完成新手引导（昵称 + 宠物状态三选一），未登录触发软/强登录引导；开发侧获得可运行的双产物脚手架、Docker Compose + Cloudflare + CI 骨架、设计 token 系统、JWT 角色门控、底部 Tab Bar 外壳与 i18n 脚手架。覆盖 FR-0A~0H、FR-19。

### Story 1.1: 双产物脚手架与本地可运行骨架

As a 开发者,
I want 一套可运行的 Flutter 移动端 + Spring Boot 后端脚手架与本地编排,
So that 团队能在统一约定（命名/分层/错误规范）上"改一行就跑起来"，为后续所有功能提供地基。

**Acceptance Criteria:**

**Given** 干净的开发环境
**When** 执行架构文档指定的两条 init 命令（`flutter create --org com.petgo` + Riverpod/go_router/dio/intl/flutter_lints；`spring init` Maven/SB4.0.x/Java21 含 web,validation,data-jpa,postgresql,data-redis,oauth2-resource-server,security,thymeleaf,actuator,flyway,lombok）
**Then** 生成的两套工程均能本地启动：`flutter run` 出空白首页、后端 `mvn spring-boot:run` 启动成功
**And** 后端按 8 模块 + shared 的 package-by-feature→layer 目录骨架建立，前端按 core/shared/features 骨架建立

**Given** 后端工程
**When** 启动 `docker-compose up`（app + PostgreSQL + Redis，德国单机配置占位）
**Then** Flyway 执行基线迁移成功、`GET /actuator/health` 返回 UP、Redis 连接就绪
**And** `@RestControllerAdvice` 全局异常处理统一输出 RFC 9457 ProblemDetail，springdoc 暴露 `/v3/api-docs`（OpenAPI 3.1）

**Given** 代码仓库
**When** 推送触发 GitHub Actions CI 骨架
**Then** 后端 `mvn package` 出 jar、前端 `flutter build` 通过，CI 绿灯（部署步骤可占位）

### Story 1.2: 设计 token 系统、Tab Bar 外壳与 i18n 脚手架

As a 用户,
I want 一个视觉统一、可在 5 个 Tab 间切换的 App 外壳,
So that 我打开 App 就能感受到温暖活泼的品牌质感并在主要区域间导航。

**Acceptance Criteria:**

**Given** 前端工程
**When** 实现 `core/theme` 设计 token（colors 含 base/text/zone accent 焦糖#C8874A·莫兰迪蓝#7BA7BC/triage 三色、typography scale、rounded、spacing、elevation）
**Then** 全局底色恒为 #FAF8F5，不因 Tab 切换整屏变色（UX-DR1）
**And** 所有后续组件均引用 token，不出现硬编码色值/字号

**Given** App 主框架
**When** 渲染底部 Tab Bar（FR-19、UX-DR2）
**Then** 显示 5 位（首页/成长档案/[+]/问诊/我的），中间「＋」凸起（44px 圆 + 3px 白描边 + 约 1/3 高度上移），上沿分割线在「＋」位向下内凹成弧形缺口（CircularNotchedRectangle）
**And** active Tab 显示 34×34 区域色填充圆 + 白图标，「＋」颜色随 active Tab 区域色切换，切换有 120ms 淡出淡入动效

**Given** i18n 脚手架
**When** 配置 flutter_localizations + intl + l10n.yaml（app_en.arb / app_id.arb，generate:true）
**Then** App 首次启动跟随设备语言（id→印尼语 / en/其他→英语回退），文案经由 .arb 取用，无写死字符串（NFR-11 脚手架）

### Story 1.3: Google 登录与 JWT 签发

As a 新/老用户,
I want 用 Google 账号一键登录,
So that 我无需记密码即可获得身份并使用核心功能。

**Acceptance Criteria:**

**Given** 用户在登录入口
**When** 点击「Google 登录」并完成系统账号选择器授权
**Then** 后端校验 Google ID Token 通过后，首次授权自动创建账户（写 `users` 表，本故事创建该表），获取 Google ID/Email/Display Name/头像
**And** 后端签发自有 JWT（access 短时 + refresh 轮换，`role=user`），客户端存入 `flutter_secure_storage`

**Given** Google 登录按钮区域
**When** 页面渲染
**Then** 按钮下方展示「登录即表示同意《服务条款》和《隐私政策》」，两份均为可点击链接跳转 H5（FR-0D，Text Link 模式，无需勾选）

**Given** 已登录用户
**When** access token 过期触发 401
**Then** dio 拦截器用 refresh 静默续期一次并重放原请求；续期失败则落游客态（游客态门控见 Story 1.5）（NFR-12）

**Given** 授权成功后的用户分流
**When** 判定新老用户
**Then** 老用户直接进入 App（不进入昵称/状态引导）；新用户进入注册引导流程（Story 1.6 昵称 + 宠物状态），引导完成后回到触发点

### Story 1.4: 登录引导浮层与登录后回跳

As a 未登录访客,
I want 在恰当时机看到清晰的登录引导并在登录后回到原操作,
So that 我不会因登录中断而迷失，转化更顺滑。

**Acceptance Criteria:**

**Given** 前端需要一套可被任意触发源调用的登录引导组件
**When** 实现软性登录推荐浮层（FR-0B）与强登录引导弹窗（FR-0C）
**Then** 两者均为自包含组件：软浮层=底部半屏、主 CTA「Google 登录」（调用 Story 1.3 登录）显著、关闭按钮小号浅色、每 session 最多触发一次；强弹窗=视觉强度更高、含说明「登录后继续使用该功能」+ Google 登录按钮 + 关闭
**And** 组件本身不自带触发条件，由调用方注入触发——受控入口门控触发见 Story 1.5；软浮层「首页浏览至第 3 页」滚动触发在 Epic 3 Feed 提供滚动深度后接线（此处先建组件 + 每 session 一次的去重逻辑）

**Given** 用户在登录引导（软浮层或强弹窗）中完成登录
**When** 登录成功
**Then** 自动回到触发点继续原操作（如继续进入问诊/发布/创建档案）；新用户先经 Story 1.6 引导再回到触发点

### Story 1.5: 游客只读进入与受控入口登录门控

As a 未登录访客,
I want 不登录就能进入 App 浏览首页,
So that 我在被要求注册前先体验内容、降低使用门槛。

**Acceptance Criteria:**

**Given** 未登录用户打开 App
**When** 落在首页 Tab
**Then** 首页内容区对游客可见、可滚动（FR-0A；Feed 实际内容由 Epic 3 填充，此处为可滚动容器 + 空状态占位）
**And** 未登录状态下任何写操作请求被后端拒绝（401/ProblemDetail）

**Given** 未登录用户
**When** 点击成长档案 / [+] / 问诊 / 我的 任一受控 Tab，或点击问诊/发布/创建档案等受控入口
**Then** 系统检测到未登录态并拦截，调用 Story 1.4 的强登录引导弹窗，不进入目标功能（FR-0C 触发侧、FR-19 门控）
**And** 客户端 dio 拦截器对受控 API 的 401 统一进入 Story 1.4 登录引导流；登录成功后回到触发点（复用 Story 1.4 回跳）

### Story 1.6: 新用户引导——昵称确认与宠物状态选择

As a 新用户,
I want 登录后确认昵称并选择我的宠物状态,
So that App 能据此定制我的首页内容方向。

**Acceptance Criteria:**

**Given** 新用户首次 Google 授权成功（由 Story 1.3 分流至此）
**When** 进入昵称确认页
**Then** 昵称默认读取 Google Display Name 且可修改，限制 ≤20 字（超出禁止继续），确认后进入宠物状态选择（FR-0E）

**Given** 昵称确认完成
**When** 进入宠物状态选择页
**Then** 必选一项（A 我有宠物 / B 暂无但计划养 / C 宠物爱好者），不可跳过；选择写入用户 `pet_status`（FR-0F）
**And** 选 A → 进入档案创建引导（Story 1.7）；选 B/C → 直接进入首页且不显示档案提示条

**Given** 已完成引导的用户
**When** 后续在「我的」或「成长档案」修改宠物状态
**Then** 状态更新成功，首页内容方向据新状态即时调整（与 FR-17 联动）

### Story 1.7: 状态 A 档案创建引导与首页提示条

As a 选择"我有宠物"的新用户,
I want 被引导去创建宠物档案，且即使跳过也能被适时提醒,
So that 我能顺畅建立宠物档案这一核心留存资产。

**Acceptance Criteria:**

**Given** 新用户在状态选择中选了 A
**When** 完成选择
**Then** 进入档案创建引导页（入口 + 「跳过，稍后创建」），创建表单本体复用 FR-11（Epic 2 实现，此处负责引导路由与跳过逻辑）（FR-0G）
**And** 完成创建 → 进入首页且不显示提示条；跳过 → 进入首页并进入提示条逻辑

**Given** 状态 A 且尚未完成档案上传的用户
**When** 进入首页
**Then** 首页顶部显示档案提示条（含「立即创建」CTA + 关闭 X）（FR-0H）
**And** 关闭后当次 session 不再显示、下次重启继续显示；前 3 次重启均显示，第 3 次关闭后或档案完成上传后永不再显示

**Given** 状态 B 或 C 的用户
**When** 进入首页
**Then** 不触发任何档案提示条

## Epic 2: 成长档案与宠物名片分享

有宠用户创建宠物档案、通过统一「+」入口记录快乐时刻（并发布日常/科普）、查看成长时间线、生成并分享对外 H5 宠物名片，闭合病毒拉新飞轮；建立媒体三层基建作为后续上传场景的共享地基。覆盖 FR-11、12、14、15、16、21、37、39。

### Story 2.1: 媒体基建——OSS 双桶、STS 直传、签名 URL 与 EXIF 剥离

As a 用户,
I want 上传的图片被安全地存储与分发,
So that 我的公开内容能快速加载、私密健康图不被泄露、定位信息不外泄。

**Acceptance Criteria:**

**Given** 客户端需上传图片
**When** 向后端请求 STS 临时凭证并直传阿里 OSS 雅加达
**Then** 公开图进公开桶（阿里 CDN 分发）、私密图进私密桶（仅短 TTL 签名 URL 访问），STS scope 受限（NFR-7）
**And** 客户端上传前压缩至 ≤10MB

**Given** 任意上传图片
**When** 进入媒体处理链路
**Then** 剥离 EXIF GPS 等定位元数据后再落桶（G-4，防 H5 公开页定位泄漏）

**Given** 私密桶图片
**When** 业务侧（如 Gemini 拉图、健康历史展示）需访问
**Then** 仅发放短 TTL 签名 URL，日志与对象存储 URL 不落 PII/健康数据

**Given** 相机/相册权限
**When** 用户首次点击上传
**Then** 按场景触发权限申请，拒绝时弹引导「需要相册权限才能上传，请前往设置开启」+「去设置」深链（FR-22D 相机/相册部分）

### Story 2.2: 宠物档案创建（单账号单宠物）

As a 有宠用户,
I want 为我的宠物创建一张专属档案,
So that 我拥有承载宠物记忆的家，并获得可对外分享的名片。

**Acceptance Criteria:**

**Given** 状态 A 用户（含 Story 1.7 引导入口或档案 Tab 入口）
**When** 填写头像（相册/拍照）、名字、品种、生日、一句话介绍（≤30 字）并提交
**Then** 创建宠物档案成功（写 `pet_profiles` 表，本故事创建该表），V1 限制单账号单宠物（已存在档案时不允许再建）（FR-11）
**And** 创建后自动生成不可枚举的对外名片 token（供 FR-14 使用），不暴露顺序 id

**Given** 已有档案的用户
**When** 再次进入创建入口
**Then** 直接进入该宠物档案，不出现重复创建表单

### Story 2.3: 统一「+」发布入口与内容发布

As a 用户,
I want 通过单一「+」入口发布日常分享/专业科普/成长日历快乐时刻,
So that 我能便捷地记录与分享，无需在多个入口间纠结。

**Acceptance Criteria:**

**Given** 登录用户点击「＋」
**When** 打开 Publish Compose 全屏 bottom sheet（UX-DR16）
**Then** 同页内含内容类型 Segment（全部/日常分享/成长日历/科普）→ 图片上传区 → 文字输入区 → 发布按钮，类型选择不超过一步、无独立页面跳转（FR-12）
**And** 文字上限 1000 字符实时计数（如「剩余 800 字符」），超出禁止发布；图片每条 ≤9 张（单张 ≤10MB），V1 不支持视频

**Given** 选择「成长日历快乐时刻」
**When** 状态 A 用户发布
**Then** 需绑定宠物，发布后该条进入宠物档案时间线（写 `content_posts` 表，本故事创建该表，含 type/danger 等弹性字段）
**And** 状态 B/C 用户的「成长日历」选项灰置不可选，悬浮提示「需要先创建宠物档案」+ 跳转创建入口

**Given** 发布上传过程中网络中断/服务器错误
**When** 用户点击「重试」
**Then** 仅重传失败文件，已填文字在当前 session 内存保留；用户退出编辑页则内容清空（V1 无持久草稿）（NFR-10）

**Given** 任意发布
**When** 提交成功
**Then** 内容默认全平台公开，发布确认页提示「发布后内容将对所有用户公开展示」，请求带 Idempotency-Key 去重

### Story 2.4: 成长档案 Tab 时间线与状态快捷编辑

As a 有宠用户,
I want 在成长档案 Tab 看到宠物信息与按时间倒序的记录时间线,
So that 我能一览宠物的成长足迹与健康事件。

**Acceptance Criteria:**

**Given** 状态 A 且已创建档案的用户
**When** 进入成长档案 Tab
**Then** 从上至下显示：宠物基本信息卡（头像/名字/品种/年龄/介绍 + 状态快捷编辑入口）、分享名片 FAB 占位、垂直时间线（快乐时刻 + 健康事件，时间倒序）（FR-37）
**And** 快乐时刻条目样式=日期+照片+文字；健康事件条目样式=日期+「🏥 问诊记录」标签+AI 评级+症状摘要（健康事件数据由 Story 2.5 承接）

**Given** 状态 A 但跳过创建的用户
**When** 进入成长档案 Tab
**Then** 显示空状态「还没有宠物档案，立即创建」+ 创建按钮（UX-DR8）

**Given** 状态 B 或 C 用户
**When** 进入成长档案 Tab
**Then** 显示「成长档案为有宠用户专属，更换宠物状态即可开启」+ 修改状态入口

**Given** 成长档案 Tab 内的状态快捷编辑入口
**When** 用户修改宠物状态
**Then** 复用 FR-0F 状态选择界面，修改后与「我的」一致同步、首页 Feed 即时按新状态刷新（FR-21）

### Story 2.5: 问诊记录存档承接与健康事件时间线

As a 用户,
I want 把问诊结果存入宠物档案并在时间线看到健康事件,
So that 宠物的健康历史得以沉淀为我不愿离开的资产。

**Acceptance Criteria:**

**Given** 一次问诊结束（触发点来自 Epic 4 AI 分诊 / Epic 5 兽医咨询）
**When** 系统弹出「是否将本次咨询存入 [宠物名] 的档案？」
**Then** 弹窗仅出现一次，用户选「存入」或「跳过」后不再重复询问（FR-16）

**Given** 用户选择「存入」
**When** 存档执行
**Then** 以健康事件形式写入成长时间线，内容含日期、症状描述、AI 评级、处理建议摘要
**And** 若涉及兽医聊天图，将所需图从腾讯 IM 复制一份到 OSS 私密桶，档案只引用应用自有 URL（不引用会过期的 IM URL）

### Story 2.6: 宠物名片 H5 对外分享页

As a 炫娃型宠物主,
I want 一个精美的对外宠物名片 H5,
So that 不用 App 的朋友也能看到我的宠物并被引导下载。

**Acceptance Criteria:**

**Given** 已创建档案的宠物
**When** 访问 `GET /p/{cardToken}`（Thymeleaf 服务端直出）
**Then** 顶部展示宠物头像/名字/品种/一句话介绍，下方为最近 5 条「成长日历快乐时刻」照片流（倒序），不含日常/科普内容（FR-14）
**And** 页面填充 Open Graph/Twitter meta（支持 WhatsApp/Line/Instagram 链接预览），含 noindex，底部展示 App 下载引导

**Given** 名片页加载
**When** 用户/访客打开链接
**Then** 加载时间 ≤3 秒（预览大图为生成卡片时预渲染的 OG 静态图，缓存于公开桶 + CDN）（NFR-2）

**Given** 档案信息或快乐时刻更新
**When** 名片被再次访问
**Then** 内容同步最新（FR-39 编辑联动）

**Given** 名片 token 无效、对应宠物已删除或关联账号已注销
**When** 访问 `/p/{cardToken}`
**Then** 返回统一友好失效页（不暴露 token 是否曾存在以防枚举）+ App 下载引导，HTTP 404 + noindex（UX-DR18 ④⑥）

### Story 2.7: 档案页动效分享 FAB

As a 宠物主,
I want 档案页有一个醒目的分享按钮,
So that 我能随时把宠物名片分享出去，驱动拉新飞轮。

**Acceptance Criteria:**

**Given** 状态 A 已创建档案用户在成长档案 Tab
**When** 页面渲染
**Then** 右下角显示大号动效 FAB，首次进入触发 scale pulse + ring ripple 动效、此后保持静态（FR-15、UX-DR13），FAB 持续显示不因滚动消失

**Given** 用户点击分享 FAB
**When** 触发分享
**Then** 调用系统分享菜单，默认传入该宠物名片链接（WhatsApp/Instagram/复制链接）

**Given** 状态 B 或 C（无宠物档案）用户
**When** 进入成长档案 Tab
**Then** FAB 不显示（无档案即无名片可分享）

### Story 2.8: 宠物档案编辑

As a 宠物主,
I want 随时修改宠物档案信息,
So that 档案与名片始终保持准确最新。

**Acceptance Criteria:**

**Given** 已创建档案用户
**When** 从成长档案 Tab 信息卡右上角「编辑」或「我的」Tab「编辑宠物档案」入口进入
**Then** 两处入口复用同一编辑页，可改头像/名字（≤20字）/品种/生日/一句话介绍（≤30字）（FR-39）
**And** 修改即时生效，宠物名片 H5（FR-14）同步更新，V1 不限编辑次数

## Epic 3: 社区 Feed 与内容互动

用户（含游客只读）浏览全平台 Feed、进详情页、点赞与两级评论、删除自己内容、举报、查看他人迷你主页卡；运营可发布种子内容并人工处理举报队列。覆盖 FR-17、18、23、24、25、26、28、36。

### Story 3.1: 运营后台地基与种子内容发布

As a 运营人员,
I want 一个受保护的后台用于平台上线前发布种子内容,
So that 用户首次打开时 Feed 不为空、有内容氛围。

**Acceptance Criteria:**

**Given** 后端
**When** 建立 `admin` slice（`role=ADMIN` 门控 + 极简 Thymeleaf 后台）
**Then** 仅 ADMIN 账号可访问后台端点，复用各模块 service，不引入独立系统（G-1 落点）
**And** ADMIN 后台地基在此首建，后续举报审核/兽医 CRUD/评分查看挂同一 slice

**Given** 运营在后台
**When** 发布种子内容（三类内容类型）
**Then** 种子内容进入全平台内容池，与用户内容在 Feed 中混排、不做特殊标记区分（FR-18）

### Story 3.2: 首页 Feed 内容流与宠物状态硬过滤

As a 用户,
I want 在首页看到按时间倒序、与我宠物状态匹配的内容瀑布流,
So that 我能高效发现感兴趣的宠物内容。

**Acceptance Criteria:**

**Given** 已登录用户
**When** 加载首页 Feed
**Then** 展示全平台公开内容按发布时间倒序，按宠物状态硬过滤（A/C 三类全显、B 不显成长日历快乐时刻、未登录全显）（FR-17）
**And** 用户修改宠物状态后 Feed 即时按新状态刷新；排序为纯时间倒序、无算法推荐、无关注关系

**Given** Feed 列表
**When** 用户滚动浏览
**Then** 2 列不等高瀑布流（8px 列间距、图片 80–200px 不裁切仅上圆角）（UX-DR4），游标分页每批 20 条、距底 ≤3~5 条自动加载下一批、支持下拉刷新（NFR-3）
**And** Feed 卡片含作者头像+昵称、正文前 2 行、首图（无图则纯文字卡），点赞/评论数不在卡片展示；加载时显示骨架屏（UX-DR9）

**Given** 首页内容分类 Tab Row
**When** 用户切换分类（全部/日常分享/成长日历/科普）
**Then** active 区域色 2px 下划线、内容区 cross-fade 切换（UX-DR5）；成长日历分类仅显示有宠物档案的帖子

**Given** Feed 无内容
**When** 列表为空
**Then** 显示空状态「快来晒出你的毛孩子！🐾」+「发布第一条内容」CTA（FR-18、UX-DR8）

### Story 3.3: 内容详情页

As a 用户,
I want 点击 Feed 卡片查看内容全文与评论,
So that 我能完整阅读并参与互动。

**Acceptance Criteria:**

**Given** 用户在 Feed 点击内容卡片
**When** 全屏跳转至详情页
**Then** 从上至下展示：顶部导航栏（返回 + 「···」菜单含举报入口）、作者信息（点击触发 FR-26）、正文、图片（多张左右滑动，角标 x/y）、互动栏（点赞按钮+数 / 评论数）、评论区、底部固定评论输入框（FR-28）
**And** V1 详情页不含社区视频展示

**Given** 用户在详情页点击返回
**When** 回到 Feed
**Then** 保持 Feed 原滚动位置（不回顶）

**Given** 评论区
**When** 加载评论
**Then** 首次加载 10 条一级评论，超出显示「查看更多评论」每次加载 10 条；二级评论默认展示前 3 条，超过显示「查看全部 X 条回复」
**And** 未登录用户点击评论输入框触发 FR-0C 登录引导

**Given** 用户从陈旧 Feed 卡、推送深链进入，或内容已被作者删除（FR-36）/ 运营下架（FR-25）
**When** 后端返回 404（不存在/已删）
**Then** 展示"这条内容已不存在"占位页 + 返回 Feed 按钮，不崩溃不白屏（UX-DR18 ④）
**And** 越权访问（403）展示友好无权限提示 + 返回（UX-DR18 ⑤）

### Story 3.4: 内容点赞

As a 用户,
I want 对喜欢的内容点赞,
So that 我能表达喜爱并让作者收到正反馈。

**Acceptance Criteria:**

**Given** 登录用户在 Feed 或详情页
**When** 点击点赞（开关态，可取消）
**Then** 三类内容均支持点赞，即时更新点赞数（写点赞关系表，本故事创建该表）（FR-23）
**And** 点赞/取消即时反映，未登录点击触发 FR-0C

**Given** 他人对用户内容点赞
**When** 点赞成功
**Then** 产生一条互动事件供推送消费（推送实现于 Epic 6 FR-22B；用户对自己内容点赞不产生通知）

### Story 3.5: 内容两级评论

As a 用户,
I want 对内容发表评论并回复他人评论,
So that 我能与社区展开讨论。

**Acceptance Criteria:**

**Given** 登录用户在详情页
**When** 发表一级评论（≤200 字）
**Then** 即时显示、按时间正序排列（写 `comments` 表，本故事创建该表）（FR-24）
**And** 发表二级评论（回复一级，≤200 字）显示在该一级评论下方，二级下不再嵌套（最多两级）

**Given** 评论删除权限
**When** 用户删除评论
**Then** 用户可删自己任意级别评论、内容作者可删其内容下任意评论；删一级评论时其下二级一并删除
**And** 评论需登录、未登录触发 FR-0C；评论产生互动事件供 FR-22B 推送消费

### Story 3.6: 内容删除（无编辑）

As a 内容作者,
I want 删除我发布的内容,
So that 我能撤回不想保留的帖子。

**Acceptance Criteria:**

**Given** 内容作者在详情页
**When** 通过「···」菜单点击「删除」（仅作者可见）
**Then** 弹确认「删除后内容将永久移除，无法恢复」，确认后内容从 Feed 与成长档案时间线同步移除（FR-36）
**And** 该内容的点赞与评论数据一并删除；V1 不提供内容编辑（采用软删 `deleted_at`）

### Story 3.7: 内容举报与运营人工审核队列

As a 用户与运营,
I want 举报不当内容并由运营人工处理,
So that 平台内容保持健康可信。

**Acceptance Criteria:**

**Given** 用户在内容卡片/详情页「···」菜单
**When** 发起举报并选择类型（违法违规/虚假信息/不当内容/骚扰/其他，单选）
**Then** 提交后显示「已收到你的举报，我们会尽快处理」，举报进入运营审核队列（写举报工单表，本故事创建该表）（FR-25）
**And** V1 不做自动下架、不做评论/用户举报

**Given** 运营在 Admin 后台举报队列
**When** 处理举报工单
**Then** 可查看举报详情并人工下架内容（复用 content service 软删），所有处理决定由人工做出（G-1 落点）

### Story 3.8: 他人迷你主页预览卡

As a 用户,
I want 点击他人头像看到一张迷你主页卡,
So that 我能感知对方身份，同时平台为未来主页留出升级位。

**Acceptance Criteria:**

**Given** 用户在 Feed/详情页点击他人头像或昵称
**When** 触发预览
**Then** 从底部弹出迷你主页卡（bottom sheet，不跳转新页），含头像+昵称、已发布内容数（「TA 发布了 X 条内容」）、文案「TA 的专属主页正在精心筹备中，更多精彩即将呈现 ✨」、关闭按钮（FR-26）
**And** 不展示「关注」「查看主页」按钮，文案不得出现「功能开发中/敬请期待/暂不支持」等技术性表达

**Given** 已注销用户的内容作者
**When** 用户点击其「已注销用户」头像
**Then** 不触发迷你预览卡

## Epic 4: AI 智能分诊

用户上传图文获得绿/黄/红三级分诊（≤15s）、黄色条件倒计时协议、红色半屏强提醒并可一键导航就医、结果可存入档案；确定性安全规则层独立兜底 AI 假阴性。覆盖 FR-1、2、3、4A。

### Story 4.1: 分诊异步基建与 Gemini 接入

As a 用户,
I want 提交症状后系统可靠地完成 AI 分析,
So that 我能在 15 秒内拿到分诊结果，即使偶发异常也能重试。

**Acceptance Criteria:**

**Given** 客户端已 STS 直传分诊图至私密桶
**When** 调用 `POST /api/v1/triage`
**Then** 返回 202 + `triageId`，创建 `triage_tasks` 记录（本故事创建该表，status=PENDING + retry_count，danger_level 待定，JSONB 存原始响应）（FR-1 后端）
**And** `@Async` 监听领域事件，置 PROCESSING → 调 Gemini Developer API（gemini-2.5-flash，签名 URL 直拉私密图）→ 解析绿/黄/红 → 写库 status=DONE

**Given** 客户端
**When** 短轮询 `GET /api/v1/triage/{id}`
**Then** 在结果就绪前返回处理中、就绪后返回结构化结果；提交后 ≤15s 返回（NFR-1）

**Given** Gemini 超时或异常
**When** 任务失败
**Then** DB 状态机驱动重试 ≤3 次、应用启动时重扫未完成任务；最终失败置 FAILED 供前端降级（NFR-10）

### Story 4.2: 确定性安全规则层（高危强制升红）

As a 平台与用户,
I want 一个独立于 AI 模型的安全兜底层,
So that 即使模型假阴性，高危症状也必被升级为红色，避免延误就医。

**Acceptance Criteria:**

**Given** 分诊任务的症状文字/解析结果
**When** Gemini 返回结果后进入后置校验
**Then** `SafetyRuleLayer` 依据 `resources/safety/high_risk_symptoms.yml` 清单（覆盖最高频致死 15–20 急症：误食巧克力/葡萄/木糖醇/百合、呼吸困难、呕吐带血、犬胃扭转、猫尿道阻塞等）命中则强制升红（NFR-6）
**And** 规则层只许往上兜、永不往下降，独立于 Gemini，绝不被业务代码绕过

**Given** 命中高危规则但模型给出绿/黄
**When** 后置校验执行
**Then** 最终 danger_level 被覆盖为 RED 并落库，下游一律按红色态处理

### Story 4.3: AI 问诊入口与图文上传

As a 焦虑的宠物主,
I want 从问诊模块进入 AI 分诊并上传照片与症状描述,
So that 我能快速发起一次免费的初步分诊。

**Acceptance Criteria:**

**Given** 登录用户进入问诊 Tab
**When** 查看问诊首页
**Then** 展示双入口卡（「AI 智能分诊」+「在线兽医」平级，均标注「免费」；兽医卡在 Epic 5 接入前为占位）（FR-4A）

**Given** 用户点击 AI 智能分诊
**When** 进入上传页
**Then** 可上传 ≤3 张图（JPG/PNG/HEIC，单张 ≤10MB）+ 文字症状描述，V1 仅图文无视频（FR-1 媒体规格）
**And** 点击上传按场景触发相机/相册权限（复用 Story 2.1 权限模式）

**Given** 用户提交分诊
**When** 等待结果
**Then** 显示全屏居中 Morandi blue spinner + 「正在为你的毛孩子分析，稍等一下～」（UX-DR9）
**And** 超时（>15s）显示「分析时间较长，请稍后重试」+「重新提交」；服务异常显示「AI 服务暂时不可用」+「重新提交」+ 软引导「或直接联系兽医」；重新提交复用上次内容不需重传（FR-1）

### Story 4.4: 分诊结果三态展示（绿/黄含条件倒计时协议）

As a 用户,
I want 清晰看到分诊危险等级、观察建议与用药参考,
So that 我能据此决定是否就医并执行居家处理。

**Acceptance Criteria:**

**Given** 分诊返回绿色
**When** 展示结果页
**Then** 绿色图标 + 「暂无紧急风险」+ 居家观察建议 + 软性引导「想更放心？可以咨询兽医确认」+ 底部「将本次结果存入档案」引导（FR-1 绿色定义）
**And** 结果页底部展示小号次要色免责声明，不干扰主内容（NFR-9）

**Given** 分诊返回黄色
**When** 展示结果页
**Then** 同屏呈现危险等级 + 观察建议 + 居家用药参考；观察建议必须含具体观察指标、时间窗口、升级触发条件，以独立卡片/加粗视觉区分（accent-consult 浅底协议块）（FR-2、UX-DR6）
**And** 黄色页不得出现「不严重/可以放心」等终结性表述

**Given** 任意非红结果页
**When** 用户点击「存入档案」
**Then** 触发 FR-16 存档弹窗（承接逻辑见 Story 2.5）

### Story 4.5: 红色半屏强提醒

As a 宠物处于紧急状况的用户,
I want 在红色评级时收到不可忽视的强提醒并能一键导航就医,
So that 我能立刻带宠物就医、不被任何干扰延误。

**Acceptance Criteria:**

**Given** 分诊结果为红色（含安全规则层强制升红）
**When** 结果呈现
**Then** 半屏红色遮罩自底滑起（红底 #C97A7A + 大白 ⚠️ + display 文案「请立即带 [宠物名] 就医」+ 副文），双按钮在前 5 秒禁用并显示倒计时，遮罩不可在 5s 内关闭（FR-3、UX-DR7）
**And** 红色态不展示兽医咨询入口、零变现引流（NFR-9）

**Given** 5 秒锁定结束
**When** 按钮激活
**Then** 主按钮「去导航」触发系统确认「是否打开地图前往附近宠物医院？」，确认后调用系统地图传入搜索词「宠物医院/Klinik Hewan」；次按钮「稍后处理」触发应用内二次确认「确认已了解风险？」
**And** 关闭遮罩后结果页保留、仍不展示兽医 CTA

**Given** 无障碍场景
**When** 红色半屏出现
**Then** 以 alertdialog / AccessibilityLiveRegion.ASSERTIVE 播报，红底配 ⚠️ 图标 + 大字不依赖颜色单一（NFR-13）

## Epic 5: 在线兽医咨询与兽医工作台

用户发起兽医图文/视频实时咨询，兽医通过独立账号登录专属工作台接单、对话、结束、查看历史与评分；运营可创建兽医账号、查看评分、封禁。覆盖 FR-4B、5、29、30、31、32、33、35。

### Story 5.1: 兽医账号与登录（含 Admin 开户）

As a 运营与兽医,
I want 运营能为兽医开户、兽医能独立登录,
So that 兽医资源以机构合作方式安全接入平台。

**Acceptance Criteria:**

**Given** 运营在 Admin 后台
**When** 创建兽医账号
**Then** 生成账号/密码（BCrypt 存储），发放凭证（写 `vet_accounts` 表，本故事创建该表）（FR-29、G-1）

**Given** 兽医在登录页
**When** 点击 Google 按钮下方「兽医登录」小字链接并用账号密码登录
**Then** 与用户侧 Google 流程隔离，登录成功签发 JWT `role=vet`，跳转兽医工作台；无「忘记密码」（由运营重置）（FR-29、NFR-12）
**And** 兽医账号不可访问用户侧首页/成长档案等功能，反之亦然（角色门控）

### Story 5.2: 兽医工作台框架与在线状态管理

As a 兽医,
I want 一个专属工作台并能切换在线/离线,
So that 我能管理我的接单可用性与会话。

**Acceptance Criteria:**

**Given** 兽医登录后
**When** 进入工作台
**Then** 展示与用户侧完全不同的 Tab Bar（待接单/进行中/历史记录/我的）（FR-30 框架）

**Given** 兽医在「我的」Tab
**When** 切换在线/离线
**Then** 状态即时生效（Redis 在线态）：在线可接新请求并出现在可接单队列、离线不接新请求且用户侧问诊入口显示「当前暂无兽医在线」（FR-32，联动 FR-4B）

### Story 5.3: 兽医咨询发起、排队与超时

As a 用户,
I want 发起兽医咨询并在无人接单时有清晰的等待与退路,
So that 我不会在等待中陷入焦虑或卡死。

**Acceptance Criteria:**

**Given** 登录用户进入兽医咨询入口
**When** 提交咨询请求
**Then** 进入等待界面「正在为你匹配兽医…」，请求进入待接单队列（写 `consult_sessions` 表，本故事创建该表，状态 WAITING）（FR-4B）
**And** 在线态以概率性展示（如「工作日 8:00–23:00 通常有兽医在线」），不展示实时在线人数；同时仅允许 1 个进行中咨询，已有进行中时入口显示「查看进行中的咨询 →」直接跳转

**Given** 等待超过 1 分钟仍无人接单
**When** 触发超时
**Then** 展示「继续等待」（重置计时，原请求保留，后续有兽医接单则推送提醒）与「先用 AI 分诊」（跳 FR-4A，原请求保留队列）两个选项（FR-4B）

**Given** 用户在等待期间
**When** 点击「取消」
**Then** 弹「确认取消本次匹配？」，确认后请求从待接单队列删除并返回问诊模块主页

**Given** 进入入口时检测到无兽医在线
**When** 展示离线态
**Then** 显示「当前暂无兽医在线」+ 预期恢复时段 + 软引导「先用 AI 分诊？」跳 FR-4A，不强制跳转（FR-4B 离线态）

### Story 5.4: AI 升级至兽医的上下文传递

As a 从 AI 分诊升级的用户,
I want 我的 AI 评级、症状描述与图片自动带给兽医,
So that 我无需重新描述或上传，沟通更高效。

**Acceptance Criteria:**

**Given** 用户从 AI 分诊结果页（非红色态）点击「咨询兽医」升级
**When** 发起兽医咨询
**Then** 完整传递 AI 危险评级（绿/黄）、症状文字描述、全部上传图片给接单兽医（FR-4B 数据传递）
**And** 兽医在「待接单」可预览上述信息、接单后对话界面顶部展示完整 AI 上下文，用户无需重新描述/上传

### Story 5.5: 腾讯 IM 会话与图文对话界面

As a 用户与兽医,
I want 实时图文/视频对话并附专业辅助,
So that 我能获得专业指引、兽医能高效判断。

**Acceptance Criteria:**

**Given** 兽医在「待接单」点击「接受」
**When** 会话创建
**Then** 后端经腾讯 IM 建会话、状态迁移 WAITING→IN_PROGRESS、自动移入「进行中」，用户侧收到「兽医已接受你的问诊，点击开始对话」（FR-30 接单流程）

**Given** 进行中会话
**When** 双方对话
**Then** 支持文字/图片/视频（腾讯 IM 传输，视频 ≤60 秒）；用户切其他 Tab 时会话后台保持连接不中断、切回恢复消息连续（NFR-5）

**Given** 兽医接单后
**When** 查看辅助工具
**Then** 展示 FR-5 辅助：按症状关键词自动匹配历史判断摘要（冷启动数据库为空时仅展示 AI 参考回复）、用户侧自动展示免责提示「本次建议为参考，最终决策权在您」（FR-5、NFR-9）

### Story 5.6: 会话结束机制与用户评分门

As a 用户,
I want 兽医结束会话后我有保护窗口并通过评分确认关闭,
So that 服务质量得到保障，我不被过早切断对话。

**Acceptance Criteria:**

**Given** 兽医点击「结束会话」并二次确认
**When** 触发结束
**Then** 会话进入 PENDING_CLOSE「待关闭」状态（非立即关闭），用户侧收到「兽医已完成本次问诊，请为本次服务评分」（FR-31）

**Given** 30 分钟保护窗口内
**When** 用户行为分支
**Then** 用户继续发消息→兽医收推送可继续回复；用户评分（1-5 星必填 + ≤100 字选填）→会话立即 CLOSED 并触发 FR-16 存档；超时未评分→自动 CLOSED 记「未评分」（FR-31、FR-33）

**Given** 用户超时未评分
**When** 下次进入问诊页面
**Then** 补弹评分弹窗一次；仍未评分则该次不再要求、记「未评分」（FR-33）

**Given** 运营在 Admin 后台
**When** 查看兽医评分
**Then** 可见各兽医历史评分与平均分（V1 评分不对普通用户公开）（FR-33、G-1）

### Story 5.7: 兽医封禁与进行中会话中断处理

As a 运营,
I want 封禁违规兽医并妥善处理其进行中的会话,
So that 平台能及时止损且用户不被悬空。

**Acceptance Criteria:**

**Given** 运营在 Admin 后台封禁某兽医账号
**When** 封禁生效
**Then** 该账号立即无法登录（FR-19、G-1）

**Given** 封禁时该兽医有进行中会话
**When** 会话被中断
**Then** 会话迁移 INTERRUPTED，用户侧收到系统消息「兽医已临时下线，本次问诊已中断，请重新发起咨询」，中断会话进入用户问诊历史标记「已中断」，用户可立即重新发起

### Story 5.8: 问诊 Tab 结构与问诊历史整合

As a 用户,
I want 问诊 Tab 同时承载功能入口与我的问诊历史,
So that 我能一处发起问诊并回看所有历史记录。

**Acceptance Criteria:**

**Given** 用户进入问诊 Tab
**When** 查看页面
**Then** 从上至下：功能入口区（AI 问诊 + 兽医咨询平级）、进行中会话卡（若有，点击进入对话）、我的问诊历史列表（FR-35）

**Given** 问诊历史列表
**When** 展示条目
**Then** 含日期 + 类型（AI/兽医）、AI 问诊显示评级+症状摘要、兽医问诊显示兽医昵称+会话摘要+用户评分、是否已存档标记
**And** 无论是否存入档案（FR-16），问诊历史均保留于此（存档是额外操作而非唯一留存路径）

**Given** 用户从历史列表或推送深链进入一个已关闭/已中断的会话
**When** 打开会话
**Then** 以只读形式展示该会话历史与其终态标签（已结束 / 未评分 / 已中断），不可继续发消息（UX-DR18 ④ 的会话变体）

## Epic 6: 推送通知、深链与通知中心

用户收到兽医回复/被赞被评/版本更新提醒，点击推送直达对应页；首页铃铛通知中心汇聚未读；推送权限在完成第一次问诊后才申请。覆盖 FR-22A~E、34、38。

### Story 6.1: 推送基建与深链路由表

As a 用户,
I want 点击任意推送都能直达对应内容,
So that 我不必从首页层层点回，体验连贯。

**Acceptance Criteria:**

**Given** 后端需推送
**When** 通过腾讯 IM 离线推送能力（底层 APNs/FCM）下发
**Then** 复用 IM 推送，不引入独立 TPNS（架构决策）

**Given** go_router 深链路由表
**When** 用户点击推送
**Then** 按 FR-38 映射直达目标页（兽医回复→问诊会话 / 问诊结束→评分 / 被赞→详情页 / 被评→详情页定位评论区）
**And** App 未启动冷启动后直达目标页（不经首页）、后台切前台直达、前台时以 Banner 展示点击直达

**Given** 推送深链目标资源已删除/会话已关闭或中断
**When** 用户点击推送落地
**Then** 直达对应页并展示其 not-found / 已关闭 / 已中断态（UX-DR18 ④⑥），不停留首页也不白屏

### Story 6.2: 兽医回复与兽医端新请求推送

As a 用户与兽医,
I want 兽医回复时提醒用户、新请求时提醒在线兽医,
So that 双方都能及时响应问诊。

**Acceptance Criteria:**

**Given** 兽医在会话中发送回复
**When** 用户未在当前对话页查看
**Then** 推送「你的宠物问诊有新回复，点击查看」，点击直达对应会话；用户正在查看该对话时不重复推送（FR-22A）

**Given** 用户提交兽医咨询请求
**When** 请求进入待接单队列
**Then** 向所有在线（FR-32）兽医推送「有新的问诊请求，点击查看」，离线兽医不接收，点击跳工作台「待接单」（FR-22E）

### Story 6.3: 内容互动推送

As a 内容作者,
I want 我的内容被点赞或评论时收到提醒,
So that 我能及时感知社区互动。

**Acceptance Criteria:**

**Given** 他人对用户内容点赞/评论（来自 Epic 3 互动事件）
**When** 触发推送
**Then** 被点赞推「有人赞了你的 [内容标题/宠物名]」、被评论推「有人评论了你的内容，点击查看」，点击跳对应详情页（被评论定位评论区）（FR-22B）
**And** V1 逐条推送不合并；用户对自己内容互动不推送

### Story 6.4: 推送权限申请时机与拒绝处理

As a 用户,
I want App 在恰当时机请求推送权限而非一上来就要,
So that 我更愿意授权，且拒绝后不被反复打扰。

**Acceptance Criteria:**

**Given** 用户完成第一次问诊
**When** 触发推送权限申请
**Then** 弹「开启通知，兽医回复时第一时间提醒你」（不在 App 首次启动时请求）（FR-22D）
**And** 用户拒绝后不再主动弹起系统权限弹窗，可在「我的」页面引导手动前往设置开启

**Given** 相机/相册权限被拒（Story 2.1 已建基础模式）
**When** 用户再次触发上传
**Then** 统一弹引导「需要相册权限才能上传，请前往设置开启」+「去设置」深链至系统权限设置页（FR-22D）

### Story 6.5: App 版本更新提醒

As a 用户,
I want App 有新版本时提醒我更新,
So that 我能用上最新功能、重大问题能被强制修复。

**Acceptance Criteria:**

**Given** App 冷启动
**When** 检测到有新版本
**Then** 推荐更新弹「发现新版本，建议更新」可「稍后」（下次启动继续提示）；强制更新弹窗不可跳过、必须前往商店更新后才能继续（FR-22C）
**And** 跳转目标 iOS→App Store / Android→Google Play；此提醒为 App 内提示、不使用系统推送、无需推送权限

### Story 6.6: 全局通知中心（铃铛）

As a 用户,
I want 一个铃铛入口汇聚我的所有通知,
So that 我能集中查看与管理未读消息。

**Acceptance Criteria:**

**Given** 首页顶部导航栏
**When** 渲染
**Then** 右侧显示铃铛图标，有未读时显示红色数字角标（与问诊 Tab 红点并存不互斥）（FR-34、FR-19 角标规则）

**Given** 用户点击铃铛
**When** 进入通知列表
**Then** 按时间倒序展示所有通知（兽医已回复/问诊已结束/被点赞/被评论），点击条目跳对应目标并标记为已读（FR-34、FR-38）

## Epic 7: 个人中心、双语与账号注销

用户管理个人信息、查看我的发布、切换 id/en 语言、退出登录，并可按印尼 PDP 要求注销账号——级联删除个人数据与 OSS 图片、内容匿名化保留；收口全局双语文案与无障碍验收门槛。覆盖 FR-20、27。

### Story 7.1: 「我的」页面与用户信息编辑

As a 用户,
I want 一个集中管理我的信息与发布的个人中心,
So that 我能编辑资料、回看我发过的内容、找到设置入口。

**Acceptance Criteria:**

**Given** 登录用户进入「我的」Tab
**When** 查看页面
**Then** 展示用户信息（头像 + 昵称 + 编辑按钮）、宠物状态（展示 A/B/C + 修改入口）、我的发布、账号设置（退出登录）、账号注销入口、帮助与反馈（FR-20）

**Given** 用户编辑资料
**When** 修改头像（可替换 Google 默认头像）或昵称（≤20 字）
**Then** 即时生效

**Given** 我的发布区块
**When** 展示
**Then** 以时间线倒序展示用户全部已发布内容（三类混合），单条目可进入详情页或删除（FR-36）

**Given** 状态 A 用户
**When** 查看「我的」
**Then** 显示「编辑宠物档案」入口（复用 FR-39 编辑页）；宠物状态修改与成长档案 Tab 同步（FR-21）

### Story 7.2: 语言设置与双语文案收口

As a 印尼/英语用户,
I want 在 id 与 en 间切换并看到完整本地化文案,
So that 我能用母语顺畅使用 App。

**Acceptance Criteria:**

**Given** App 首次启动
**When** 读取设备语言
**Then** 设备印尼语→印尼语、英语→英语、其他→默认英语（FR-27）

**Given** 用户在「我的 → 语言设置」
**When** 手动切换语言
**Then** 即时生效无需重启；UGC 内容不受语言设置限制可用任意语言发布（FR-27）

**Given** 全 App 文案
**When** 收口双语
**Then** 所有 UI 文案/系统提示/错误信息均提供 id + en 两套（NFR-11），按场景沉淀 Voice & Tone microcopy 双语库（空状态/分析中/分享引导/红色预警/免责声明等），规则：每条最多 1 emoji、问诊结果页克制不用感叹号、红色预警简短无歧义（UX-DR14）

### Story 7.3: 退出登录与账号注销级联删除

As a 用户,
I want 安全退出，并能按法规要求彻底注销账号,
So that 我对自己的数据拥有可达的控制权（PDP 数据主体权利）。

**Acceptance Criteria:**

**Given** 用户点击退出登录
**When** 确认
**Then** 清除本地登录状态，App 停留在首页保持游客浏览态（FR-20、FR-0A）

**Given** 用户发起账号注销
**When** 双重确认（第一次弹窗说明「删除后数据不可恢复」、第二次要求输入「确认注销」或点击高危按钮）
**Then** 执行级联删除：用户宠物档案、问诊记录、账号数据全部删除，含各表 + OSS 图片删除路径（数据模型设计之初即纳入）（FR-20、NFR-8）

**Given** 注销后用户发布的帖子/评论
**When** 匿名化保留
**Then** 内容保留在 Feed 与他人评论区不删除，作者头像替换默认占位、昵称显示「已注销用户」，仅保留文字与图片不保留任何个人身份信息，点击「已注销用户」头像不触发 FR-26（NFR-8）

### Story 7.4: 全局无障碍验收

As a 有无障碍需求的用户,
I want App 满足基础无障碍标准,
So that 我能无障碍地使用关键功能，尤其是紧急的红色预警。

**Acceptance Criteria:**

**Given** 全 App 文本与图标
**When** 无障碍审计
**Then** 在 #FAF8F5 底色上文本/图标对比度满足 WCAG AA（≥4.5:1），disclaimer 即使 10px 也维持 ≥3:1（NFR-13、UX-DR15）
**And** 所有可交互元素触摸目标 ≥44×44pt，三色态用 icon+text+color 不依赖颜色单一

**Given** 红色半屏 Alert（FR-3）
**When** 屏幕阅读器场景
**Then** 以 alertdialog / AccessibilityLiveRegion.ASSERTIVE 播报

**Given** 系统字体放大
**When** 用户调整动态字体
**Then** body 及以下随系统字号缩放（≤3 级），标题封顶防布局破坏；V1 仅浅色模式、portrait-only（NFR-14）

## Epic 8: 宠物第一次里程碑系统（FR-42）

> **决策 F16（2026-06-09 产品拍板，反转 F2②）**：FR-42 里程碑本体**全量纳入 V1.0.0**。原始 incoming PRD §6.1 本就将里程碑系统列入 V1.0.0；本 Epic 实现完整 FR-42。

宠物档案创建后，系统按宠物类型（FR-11，`pet_type`）自动分配固定里程碑清单（猫 30 / 狗 30 / 其他 15）。用户在成长档案 Tab 经「已完成 X/N」进度条进入里程碑列表页，按 L/M/S 三级分区查看徽章（已完成彩色 / 未完成灰锁）。完成机制三类：系统自动（订阅既有领域事件）、用户打卡（已打卡关联内容 / 去发布成长日历）、系统推送+当天发布。完成按级触发 S/M/L 三级庆祝动效；L 级达成推送通知中心并生成可分享卡片。**护栏**：自动完成只用 `@TransactionalEventListener`/`@Async` 订阅既有事件 + 定时类走 6-7 `@Scheduled`，**禁 MQ/Quartz/缓存/新中间件**；`pet_type` 创建后不可改（F6）；清单为后端固定常量，V1 不做运营可编辑清单。新表归 profile 域（`pet_milestones` / `milestone_completions`），Flyway 当前到 V26，新迁移 V27 起（占位，E2 执行时顺延）。覆盖 FR-42。

### Story 8.1: 里程碑数据基建与清单分配

As a 有宠物档案的用户,
I want 系统按我的宠物类型自动分配一套里程碑清单,
So that 我能看到为我的猫 / 狗 / 其他宠物量身定制的成长目标与进度。

**Acceptance Criteria:**

**Given** 宠物档案已创建（`pet_type` = CAT/DOG/OTHER）
**When** 请求里程碑列表
**Then** 返回对应固定清单（猫 30 / 狗 30 / 其他 15），每项含 code、级别（S/M/L）、标题、触发方式（系统自动 / 用户打卡 / 系统推送+发布）、完成状态、完成日期；按 L/M/S 分区，每级给出 X/N 进度与总进度（FR-42）

**Given** 清单为后端固定常量（如 `MilestoneCatalog`，含三套清单与 C-Lx 组合依赖规则）
**When** 建档完成
**Then** 不预插完成行；`milestone_completions` 仅记已完成项；进度由「清单总数 vs 完成数」计算

**Given** 数据生命周期
**When** 注销账号
**Then** 里程碑完成数据随宠物级联删除（D1/D2 家族，归 profile 域，随档案删除）；新表外露标识用不可枚举 token，不外露自增 id

### Story 8.2: 里程碑列表页与徽章展示

As a 用户,
I want 一个分级清晰、徽章直观的里程碑列表页,
So that 我能一眼看到已解锁与待完成的成长里程碑。

**Acceptance Criteria:**

**Given** 成长档案 Tab 的「已完成 X/N」进度条
**When** 点击
**Then** 进入里程碑列表页（`milestone_list_page` 由壳改为真页）：顶部宠物头像+名字+总进度，内容区按 L/M/S 三级独立分区，每区标题显示该级完成进度（如「L 级 · 已完成 1/5」）（FR-42）

**Given** 每个里程碑条目
**When** 渲染
**Then** 已完成 → 彩色徽章 + 完成日期；未完成 → 灰色锁定轮廓（V1 徽章用 emoji/图标 + mint 描边占位）

**Given** 点击徽章
**When** 该项触发方式为「系统自动」或「系统推送+发布」
**Then** 弹出说明文案（触发条件），**不展示「已完成」确认按钮**；为「用户打卡」类则弹出含「已打卡 / 去发布」两选项的卡片（详见 8.4）

**Given** 加载失败
**When** 列表拉取出错
**Then** 走 F13 统一失败态（加载失败 + 重试入口）

### Story 8.3: 里程碑系统自动完成（领域事件订阅）

As a 用户,
I want 我做了对应的事系统就自动点亮里程碑,
So that 我无需手动操作即可解锁「系统自动」类成长节点。

**Acceptance Criteria:**

**Given** 既有领域行为发生（建档完成 C-S1 / 首张成长日历照片 C-S2 / 首次分享名片 C-S3 / 首次保存问诊结论 C-S4 / 首次发布日常分享 C-S5 / 首次被评论 C-S14 / 首次收到点赞 C-S15 等，按清单触发方式=系统自动的项）
**When** 对应领域事件发布
**Then** 经 `@TransactionalEventListener` + `@Async` 订阅 → 幂等标记完成（已完成不重复、不可撤销），触发对应级别庆祝（FR-42）；**禁新中间件**

**Given** 组合/计数类节点（陪伴满 30 天 M8、成长日历满 10 条 M10 / 满 30 条 L5、健康里程碑全完成 C-L4=C-M3+C-M4+C-M5）
**When** 满足条件
**Then** 自动解锁；陪伴天数等定时类经 6-7 `@Scheduled` 每日扫描判定，计数类在发布/完成时校验

**Given** 自动完成幂等
**When** 同一事件重复投递或并发
**Then** `milestone_completions` 唯一约束（pet + milestone_code）保证只记一次

### Story 8.4: 里程碑用户打卡（已打卡 / 去发布两路径）

As a 用户,
I want 对「第一次洗澡」这类需手动记录的里程碑打卡,
So that 我能把真实发生过的成长时刻补记或新发布并点亮里程碑。

**Acceptance Criteria:**

**Given** 「用户打卡」类未完成里程碑，点「已打卡」
**When** 弹出内容关联选择器
**Then** 仅展示该宠物已发布的**成长日历**内容（不含日常/科普）；选择一条 → 标记完成、记录完成时间；关闭未选 → 保持未完成无变更（FR-42）

**Given** 内容关联约束
**When** 选择器渲染
**Then** 每条成长日历内容仅可关联一个里程碑，已被关联的置灰不可选（后端唯一约束 + 前端置灰）

**Given** 点「去发布」
**When** 跳转统一发布入口「+」预选成长日历类型（仅跳转不预锁定）
**Then** 用户放弃/退出 → 保持未完成；发布成功（仍为成长日历类型）→ 自动完成并触发庆祝；若编辑页切换为其他类型并发布 → 不自动完成，需回里程碑页重操作

### Story 8.5: 里程碑三级庆祝动效

As a 用户,
I want 完成里程碑时收到与其分量匹配的庆祝,
So that 我的成长记录有仪式感与情绪回报。

**Acceptance Criteria:**

**Given** S 级里程碑完成
**When** 触发庆祝
**Then** 半屏庆祝弹层 1-2 秒，含徽章展示（FR-42）

**Given** M 级里程碑完成
**When** 触发庆祝
**Then** 全屏动效约 3 秒 + 徽章解锁画面

**Given** L 级里程碑完成
**When** 触发庆祝
**Then** Duolingo 开宝箱式交互动效 + 自动弹出里程碑分享卡片（衔接 8.6）；全部动效套 mint 设计 token

### Story 8.6: L 级里程碑达成推送与分享卡

As a 用户,
I want 重大里程碑达成时收到通知并能一键分享,
So that 我不会错过重大节点，并能向亲友炫耀宠物的成长。

**Acceptance Criteria:**

**Given** L 级里程碑完成（无论触发方式）
**When** 达成
**Then** 经 6-1 `NotificationService` 下发 `MILESTONE_NODE` 通知至通知中心（FR-34），已授权系统推送用户同时收到系统推送；点击跳成长档案 Tab → 里程碑列表页（FR-38）；连带 6-6 铃铛里程碑条改真数据（FR-42）

**Given** L 级完成后的分享卡
**When** 用户分享
**Then** 自动生成可分享卡片，一键分享至 WhatsApp / Instagram（复用 2-6 名片分享通道，不另起基建）

**Given** 「系统推送提醒」类 L 级节点（生日 C/D/G-L1、满 100 天 -L2、满 365 天 -L3）
**When** 节点当天
**Then** 复用 6-7 已实现的定时推送切片提醒用户当天发布；用户当天发布成长日历记录后自动完成（生日若已过开放手动补录打卡，走 8.4）
