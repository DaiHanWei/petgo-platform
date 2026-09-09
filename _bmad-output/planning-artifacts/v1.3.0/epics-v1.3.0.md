---
stepsCompleted: [1, 2, 3, 4]
status: 'complete'
completedAt: '2026-09-09'
docType: 'epics-delta'
project_name: 'TailTopia V1.3.0 后台'
date: '2026-09-09'
baseline: '_bmad-output/planning-artifacts/v1.0.0/epics.md'   # 冻结基线；本文件只写 V1.3.0 后台增量
inputDocuments:
  - _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-admin.md
  - _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-delta.md
  - _bmad-output/planning-artifacts/v1.3.0/后台重构逐页规格.md
  - _bmad-output/planning-artifacts/v1.3.0/ui-v1.3.0-admin.html
  - _bmad-output/planning-artifacts/v1.3.0/决策日志.md
  - _bmad-output/planning-artifacts/v1.3.0/validation-2026-09-09/review-adversarial-general.md
scope: 'V1.3.0 后台增量：AB-15A~AB-22A；brownfield；仅后台，App 端另一分支'
---

# TailTopia V1.3.0 后台 - Epic Breakdown（Delta）

## Overview

本文件是 V1.3.0 **后台**增量 epic/story 分解，承接 `PRD-v1.3.0-admin.md`（AB-15A～AB-22A）、`architecture-v1.3.0-delta.md`（AD-1～AD-12）、`后台重构逐页规格.md` + `ui-v1.3.0-admin.html`（49 页 / 8 泳道 / 五套模板）与 `决策日志.md`（D-1～D-35）。未提及处继承 V1.0 基线与各版 delta。App 端（FR-112 场所、FR-120 护照样式）由另一分支实现，本文件只写后台侧，跨分支契约见架构 delta X-1～X-4。

## Requirements Inventory

### Functional Requirements

> 编号规则：`FR-<AB 号>-<序>`，直接对应 PRD 各 AB 的分项，便于回溯。

**AB-15A 首页数据看板**
- FR-15A-1：后台首页 `/admin` 原位替换为数据看板，五张分组图表卡（用户增长 / 建档转化 / 内容生产 / 互动质量 / 付费），每卡一图，图下可附当日明细行但不得替代图表
- FR-15A-2：18 项指标按 PRD §1 ①-a 口径表实现，日 = WIB 自然日、截止该日 24:00，图上最后一根柱永远是昨天
- FR-15A-3：帖子类 9 项指标（#3 #4 #8~#14）出「真实用户 / 含种子」双口径，内容生产卡与互动质量卡内 tab 切换，默认真实用户，口径差异提示常驻卡底（D-29）
- FR-15A-4：付费卡内 tab 切换「现金到账 / 含 PawCoin 消费」，默认现金到账，两口径差异提示常驻
- FR-15A-5：时间范围近 7 天 / 近 30 天两档，默认 7 天，切换用 htmx 局部替换图表区不整页刷新
- FR-15A-6：缺数据日在图上显示为断点不画 0；卡加载中骨架、失败卡内重试
- FR-15A-7：每项指标带口径提示（hover / 图标展开），文案取自口径表
- FR-15A-8：底部「当前时点总量」紧凑摘要行（原首页四模块数字降级）+ 种子发布链接保留
- FR-15A-9：次日留存不承载，页内一行说明 +「去 PostHog 查看」文案（不做外链）
- FR-15A-10：任一指标任一天数值与口径表定义逐项相等（验收口径）；上线时 18 项全部回填到 2026-07-17（D-15）
- FR-15A-11：四卡全员可见；付费卡按与支付记录页同码的既有权限渲染，无权限不渲染该卡（D-17）；不做导出、不做自定义布局
- FR-15A-12：「总安装用户数」改名「累计注册用户数」；「新增建档用户数」按人去重（D-16/D-28）

**AB-16A 后台账号管理增强**
- FR-16A-1：账号列表行内新增「修改显示名」：权限 SUPER_ADMIN 或 `admin.create_account`；非空 ≤100 字；审计 `ACCOUNT_RENAMED` 记旧→新；成功横幅注明「对方重新登录后生效」
- FR-16A-2：行内新增「换绑 Lark 邮箱」：仅 SUPER_ADMIN；格式校验；与任何**启用中**账号邮箱不重复（已停用账号邮箱视为释放，D-21）；二次确认复述旧→新与后果；审计 `ACCOUNT_EMAIL_REBOUND` + 超管告警；bootstrap 超管不可换绑
- FR-16A-3：换绑 / 停用 / 改角色 / 改账号级权限后，该账号所有已登录会话在下一次请求即失效并跳登录页带提示（D-1，AD-1 版本号）
- FR-16A-4：self 护栏：不能停用自己、不能修改自己的岗位角色，拒绝时 `admin.err.*` 明示原因
- FR-16A-5：账号只停用永不删除；全部操作经 `AdminAuditService.record`；沿用 `admin-accounts.html` 扩展不新开页

**AB-17A 场所内容管理**
- FR-17A-1：「内容」组新增菜单「场所管理」`/admin/places`，模板 B：筛选（名称/地址搜索、类型、状态）+ 摘要条（上架数 / 今日新增 / 待处理举报数 / 累计打卡数）+ 表格（名称 / 类型 / 标签 / 文字地址 / 标记人 / 照片数 / 评论数 / 打卡数 / 推荐·不推荐 / 状态 / 创建时间，默认创建时间倒序，每页 20）+ 抽屉
- FR-17A-2：抽屉：基本信息（含坐标数字可编辑）、照片墙（上传者 + 逐张删除，可删至 0）、评论列表（态度 + 逐条删除，删除同步扣态度计数）、打卡计数、操作条
- FR-17A-3：处置：编辑（名称/类型/标签/描述/文字地址/坐标，标记人不可改）、下架 / 恢复（软删；直链落「场所不存在」；护照章与 Diary 打卡记录保留，恢复后自动复原）、合并（弹层搜索选保留场所，data-confirm 复述「保留 A、并入 B、不可撤销」；照片 / 评论 / 打卡归入保留方，本体置 MERGED 记合并指向，发布 `PlaceMergedEvent`）
- FR-17A-4：新建场所（冷启动预置）：右上按钮 → 抽屉表单，字段与用户端一致，坐标为运营从 Google Maps 复制**手填**两个数字（范围校验，雅加达都会区外黄条警告不拦截，D-33）；标记人从运营发布身份池选择；不做批量导入
- FR-17A-5：场所举报进 A1 统一复核工作台新页签「场所举报」（新建记录与队列，D-5）；页签内可执行下架场所 / 删照片 / 删评论 / 驳回举报，与本页处置同源
- FR-17A-6：新权限码 `place.manage`（读 + 全部处置 + 新建），不预授予任何预置角色；全部动作落审计 `PLACE_*`
- FR-17A-7：不做地图组件、不引地图库（D-32）

**AB-18A KTP 模块高清图解锁定价配置**
- FR-18A-1：运营配置页配置组更名重组为「KTP 模块高清图解锁定价」三行：KTP 卡高清下载（迁入）/ 护照·护照内页 / 护照·登机牌；样式名只读 + 价格输入；卡自带保存钮（未修改禁用 → 修改激活 → 高危确认复述新旧值 → 提交）
- FR-18A-2：价格校验正整数 ≥1 IDR，配 0 或负数拒绝并行内报错；**不做 0 元限免**（D-7）
- FR-18A-3：改价即时生效只影响新发起的解锁；三价独立不联动；护照两价默认值 = 当前 KTP 高清价并在行旁展示参考
- FR-18A-4：新样式上架 = 加列 + 迁移 + 小发版（D-3）；改价经审计；权限沿用既有定价 `config.edit` / `config.view`

**AB-19A 后台 IA 与操作体验重整**
- FR-19A-1：新导航 8 组按 PRD §5 ① 表与 UI 稿泳道：概览 1 / 待办中心 6 / 内容 8 / 用户 3 / 订单与资金 5 / 商城 17 / 兽医与问诊 4 / 配置与安全 5（D-8）；组可展开收起，当前组自动展开项高亮；无权限项不渲染、整组无权限不渲染
- FR-19A-2：待办中心组名挂未处理总数角标、各项挂各自计数，与页内页签计数同源，处置后即时刷新（`HX-Trigger`）
- FR-19A-3：G0 顶栏：语言切换（现状）+ 账号菜单（显示名 + 角色徽标，下拉退出登录）+ staging 环境黄色「STAG」角标
- FR-19A-4：49 页收敛为五套模板：A 处置工作台（6 页 + Toko 退货 / 异常订单）、B 管理列表 + 抽屉（≈20+ 页）、C 报表（5 页）、D 配置卡（4 页）、E 多步流（2 页），逐页内容以 `后台重构逐页规格.md` 各节为准
- FR-19A-5：模板 A：双栏 360px + ≥720px；页签行 + 筛选行；左栏两行式队列行、状态色点四语义、每页 20 行滚动加载；选择参数类操作常驻操作区未选全禁用；处置成功 toast 2s + 自动选中下一条；失败行内 err；**全组不做键盘 ↑/↓**（D-14）；窄屏 <1024 两屏切换；「待处理｜已处理」两态页签，已处理只读回看
- FR-19A-6：模板 B：筛选栏（下拉即选即查、文本搜索配显式查询钮）+ 摘要条 3~5 数字随筛选联动 + 表格 48px 行 + 右侧 480px 抽屉承载详情与操作；8 个独立详情页并入抽屉后直接删除路由，**不做旧地址跳转**（D-23）；列表 `?open=<id>` 页内深链自动开抽屉
- FR-19A-7：模板 C 只读视觉标识；模板 D 每卡自带保存钮（未修改禁用 → 已修改 → 高危确认）、组头不标红；模板 E 步骤条 + 步间校验 + 底部上一步/下一步吸底条
- FR-19A-8：功能级微调 11 条（PRD §5 ③）：警告加必填 reason 仅入审计；用户详情宠物档案增性别/生日；排期并入批量内容页签；标签打标/分配入口收进抽屉分配页签；待办中心两态；全站模糊搜索 + 导出分列；兽医在线状态/评分并入账号抽屉（C5/C6 退役）；算法参数页整改 + 变更记录抽屉；标签码自动生成 + 分配记录起止时间列；PawCoin 档位只显启用中 + 「查看已停用」折叠区可重新启用；兽医开户 / 真实账号纳入流程 UI 补全
- FR-19A-9：不增删任何操作端点；验收底账 = 从 `dev_1.3.0` 代码生成的《后台写操作清单》（D-27），逐操作核对不丢一项；页面分母 = UI 稿 49 页
- FR-19A-10：三语：全部新增/改动文案走 message key，zh/en/id 齐备才可合入；ID 文案占位适配（标签置上、按钮不截断、表头 nowrap 省略号、切 ID 全站无截断）
- FR-19A-11：商城组 17 页重构排在 v1.4.0 电商线合入 `dev_1.3.0` 之后（AD-12）；整包随 1.3.0 交付

**AB-20A 虚拟账号评论（暖贴）**
- FR-20A-1：评论管理页升级两页签：「评论巡查」= 现状；「帖子评论分布」= 帖子维度视图
- FR-20A-2：分布页签筛选：评论数区间（=0 / ≤3 / 自定义 ≤N）+ 发布时间段 + 物种 + 帖子状态（默认全部）+「排除虚拟账号内容」开关（默认开，判定 = 作者 ∈ 虚拟账号池）；列 = 摘要 / 作者 / 时间 / 评论数（0 红 / 低量黄）/ 其中虚拟评论数 / 「去评论」；默认评论数升序
- FR-20A-3：分布页签摘要条：帖子数 · 篇均评论数双口径（含虚拟 / 剔除虚拟）· 0 评论帖数与占比 · 虚拟评论占比（口径 = 当前筛选集、可见评论）
- FR-20A-4：「去评论」抽屉：帖子预览 + 已有评论（虚拟标识仅后台可见）+ 常驻评论区（身份下拉取启用中虚拟账号、物种匹配优先可切全部、每项带「今日已评 N 条」；文本 ≤200 字；未选身份或空内容禁用）
- FR-20A-5：发布走 `CommentService` 完整同链路含审核（D-4）：L1 命中同步拒绝行内报错；否则落审核中，机审通过公开并通知作者，拿不准进 A1 送审页签；提交反馈「已提交，审核通过后显示」；评论数不即时 +1；**本版只发一级评论**（D-35）
- FR-20A-6：连发：提交后抽屉不关、文本清空、可继续评或「下一帖」；防呆：同账号同帖 10 分钟内再评行内提示确认；同帖虚拟评论 ≥3 黄条提示不拦截；不加硬上限（D-20）
- FR-20A-7：待办中心新页「暖贴回复跟进」`/admin/warm-replies`（模板 A）：虚拟一级评论收到真实用户回复即入队，同一虚拟评论多条回复合并一项；左 = 队列（虚拟账号 / 帖子 / 回复摘要 / 时间），右 = 帖子卡 + 评论线程（新回复高亮）+ 常驻回复区，回复身份锁定为被回复的虚拟账号不可切换；动作 = 发布回复（同链路含审核）/ 标记已读；处理后出队自动下一条；页签待跟进 / 已跟进（近 30 天）
- FR-20A-8：出队：触发入队的回复被下架或作者自删 → 计数减一，归零删除待办（D-19）
- FR-20A-9：新端点 `POST /admin/comments/virtual`（幂等）+ 分布聚合查询 + 跟进队列查询 / 标记已读；新权限 `comment.virtual_post`（不预授予）；审计 `COMMENT_VIRTUAL_POST`（正文摘要 ≤50 字）；虚拟评论标识不落 App 任何接口字段
- FR-20A-10：预留 `comments.reply_to_comment_id` 可空列 + 回复接口可选参数，本版只落库不使用（X-2）

**AB-21A 角色配置**
- FR-21A-1：「安全」下新增子页「角色配置」`/admin/roles`，与后台账号并列：列表列 = 角色名称 / 类型（系统预置 / 自定义）/ 权限数 / 使用中账号数 / 操作
- FR-21A-2：系统预置角色 = 运营专员 / 发货专员 / 客服 / 财务 四个（D-13），可编辑权限、不可删除、名称不可改（走 message key 三语）；运营主管与超管不在本功能范围，硬编码不变
- FR-21A-3：自定义角色：名称运营自填单语、可改可删；删除前须无使用中账号（data-confirm 复述受影响账号数；DB FK RESTRICT 兜底）
- FR-21A-4：权限矩阵整页：约 35 个页面维度 × 「查看 / 编辑」两档 + 「其他操作权限」列如实列出，数据源 `AdminPermissions.java` 72 个常量；保存 = 高危确认复述增减项
- FR-21A-5：方案 A（D-9）：新建 `admin_roles` / `admin_role_permissions`，四个预置角色权限从枚举一次性迁入表；解析顺序 SUPER_ADMIN 全权 → OPS_MANAGER 读枚举 → CUSTOM 读账号级权限表（保留）→ 其余读角色表；变更对该角色下账号**重新登录后生效**，横幅注明（D-2）
- FR-21A-6：后台账号页「改角色」下拉从角色表读（预置 + 自定义）；账号级「编辑权限」面板保留并按新导航组重排；审计 `ROLE_CREATED/UPDATED/DELETED`
- FR-21A-7：代码现状不规整如实记录不擅自处理：评论管理无独立查看码；退款管理 / Toko 退货 / 开封判例共用 `refund.*`；复购效果走 `config.view` 或 `order.view`

**AB-22A PawCoin 充值档位新建**
- FR-22A-1：运营配置页 PawCoin 档位卡右上「＋新建档位」→ 抽屉表单（金额 IDR 正整数）→ PRG 回本页以启用态出现
- FR-22A-2：校验：金额与任何既有档位（含已停用）不重复；创建即启用，启用中总数 ≤4，达上限拒绝并行内报错「先停用一个档位」（advisory lock 防并发）
- FR-22A-3：`tier_key = "t" + amount_idr`，`sort_order` 按金额升序重排；新端点 `POST /admin/config/tiers`；审计 `TIER_CREATED`；权限 `config.edit`；不提供删除；App 端读档位接口不变

### NonFunctional Requirements

- NFR-1 数据一致性：看板任一指标任一天 = 口径表定义；WIB 切日显式 `AT TIME ZONE 'Asia/Jakarta'`（OQ-B3）；预聚合行不可变
- NFR-2 安全：写操作三件套 `@PreAuthorize` + 审计哈希链 + 三语 key；账号 / 角色 / 权限写操作必递增 `security_version`；不新开绕审核的评论写路径；审计 detail 不记评论全文 / 邮箱 / 坐标
- NFR-3 三语：新增 key 三包同批，CI 集合比对缺一即红；ID 占位适配验收
- NFR-4 无新中间件 / 无新 Maven 依赖 / 无 CDN 外链：Chart.js vendored 静态文件；不引地图库
- NFR-5 时区：全后台 WIB 展示（审计页亦然，D-22），入库 UTC；跑批 cron `zone=Asia/Jakarta`
- NFR-6 兼容：`/api/v1` 只动评论回复请求体一个可选字段，对现有 App 完全兼容；App 端读档位 / 定价接口不变
- NFR-7 性能（≤500 DAU 量级）：看板不实时算 LATERAL；模板 B 摘要条单条 `COUNT FILTER` 聚合；角标单条聚合查询；抽屉按需加载
- NFR-8 事务：事件监听 `AFTER_COMMIT` + `REQUIRES_NEW`；合并场所单事务；跑批单日一事务
- NFR-9 可测：每个新 Controller 至少四条 MockMvc（整页 200 / HX fragment / 422 / 403）；看板 `MetricQueryParityTest`
- NFR-10 Flyway：时间戳版本号、一支一件事、DDL 与数据迁移分文件、`out-of-order` 常开、`mvn clean package`

### Additional Requirements

**来自架构 delta（AD-1～AD-12），无 starter（brownfield 既有 admin 切片）：**
- AD-1 `admin_accounts.security_version` + `AdminSessionGuardFilter` 每请求比对 → **横切件，最先实现**
- AD-2/3 `ops_daily_metrics` 长表（scope ALL/REAL）+ 每指标一个 `*MetricQuery` + `DashboardMaterializer` 自愈跑批（cron WIB 01:00，补全 [2026-07-17, 昨天] 缺失日）；stag 专用手动触发端点
- AD-4 `admin_roles` / `admin_role_permissions` / `admin_accounts.role_id`；`seed_admin_roles` 数据迁移单独文件；`resolvePermissions` 解析顺序
- AD-5 `places` 五表（含 `place_reports` 三态）；`PlaceMergedEvent`；`TicketType.PLACE_REPORT`；坐标校验器
- AD-6 `warm_reply_followups` + 部分唯一索引；`WarmReplyEnqueueListener` 监听 `ContentCommentedEvent`；`VirtualCommentService` 经 `CommentService`；`comments.reply_to_comment_id` 预留
- AD-7 `pricing_config` 两列（默认 = 当前 KTP 价，CHECK ≥1）；`createTier` + advisory lock
- AD-8 `User.isSyntheticAccount()` + `SyntheticAccountSql.EXCLUDE_WHERE` 单一出口，对照单测
- AD-9 htmx 约定：`HX-Request` → fragment；成功 200 + oob；422/403 fragment 由 `admin-core.js` beforeSwap 放行；`HX-Trigger admin:badge-refresh`；抽屉 `GET …/{id}/drawer`；D/E 与整页表单维持 PRG
- AD-10 `AdminExportWriter`（POI xlsx / RFC 4180 csv / 表头随 locale）
- AD-11 五套 fragment 壳 + `nav.html`/`badges.html` + JS 拆分（core / workbench / drawer / charts）+ 图表内嵌 JSON + `admin.v130.*` key 前缀 + `scripts/ci/check-i18n-keys.sh`
- AD-12 商城组 17 页排在电商线合入之后
- `admin/shared/`：`HxRequest` 解析器、`AdminBusinessExceptionAdvice`、`AdminTime`、`AdminExportWriter`
- 8 支迁移清单（架构 §数据模型增量）；`application.yml` 加 `petgo.admin.dashboard.cron` / `backfill-from`
- 删除 8 个独立页模板与路由：content-detail / user-detail / consult-order-detail / ai-order-detail / vet-edit / vet-qualification / vets-online / ratings
- 跨分支契约 X-1～X-4 只做后台侧承诺

### UX Design Requirements

> 来源：`后台重构逐页规格.md` G0 + 模板 A/B/C/D/E 通用规格 + 逐页节；`ui-v1.3.0-admin.html` 泳道 0～10。

- UX-DR1 G0 侧导航：固定 220px 常驻不折叠；8 组可展开收起；当前组自动展开、当前项高亮；待办中心组名角标 + 各项计数；无权限项不渲染
- UX-DR2 G0 顶栏：语言切换（现状 `?lang=`）；账号菜单常驻「显示名 · 角色徽标」下拉退出登录（无需 data-confirm）；STAG 黄色角标仅 staging
- UX-DR3 全局件：toast 复用现状；不做面包屑；空态插画 +「队列已清空 🎉」；有查看权无处置权按钮禁用并注明所缺权限名
- UX-DR4 模板 A 壳：左 360px 队列（56px 两行式行：主标识 ≤18 字截断 + 状态色点 / 摘要 + 相对时间；选中 3px 主题色竖条）+ 右 ≥720px 详情 + 操作区；页签行计数；筛选行；每页 20 滚动加载；<1024 两屏切换；状态色点 🔵待处理 🟡处理中 🔴超时/高优 ⚪终态
- UX-DR5 模板 A 交互：参数类操作常驻操作区未选全禁用；确认弹层只留不可逆高危；处置成功 toast 2s + 自动下一条；失败行内 err；无键盘 ↑/↓；两态页签「待处理｜已处理」；已处理 = 结果徽标 + 操作人 + 时间 + 理由，可筛可搜
- UX-DR6 模板 B 壳：筛选栏（下拉 data-autosubmit；文本搜索显式「查询」钮）→ 摘要条 3~5 数字（抽屉内 2×2 弹性网格）→ 表格 48px 行、状态色点 + 徽标、列头排序仅已有排序参数列 → 右滑 480px 抽屉（内容 + 操作 + 底部固定操作条，ESC 关闭）；`?open=<id>` 自动开抽屉
- UX-DR7 模板 C：图表卡在上 + 维度/时间切换 + 明细表在下；统一只读视觉标识
- UX-DR8 模板 D：业务分组卡片 + 行内编辑 + 每卡自带保存钮（未修改禁用 → 修改激活标「已修改」→ 高危确认复述新旧值 → 提交）；组头不标红
- UX-DR9 模板 E：顶部步骤条（完成步可点回）+ 分步卡片 + 步间校验 + 底部吸底「← 上一步 / 下一步 →」+ 最后一步只读预览 + data-confirm
- UX-DR10 看板（泳道 8）：五卡一图；7/30 天切换器；付费卡与帖子类卡内 tab；断点黄标注、整卡空态区别于断点；卡内加载骨架 / 失败重试；口径提示 hover/图标
- UX-DR11 组件底座修正（09-04）：`.sel`/`.inp` 补 `display:inline-block;box-sizing:border-box`；`.sel` 箭头绝对定位；`.sum` 抽屉内 2×2
- UX-DR12 三语占位适配：表单标签置上；按钮 min-width + 自适应不截断；表头 / 徽标 nowrap + 省略号悬浮全文；导航与页签按 ID 最长文案校验
- UX-DR13 导出规则 12：CSV 正确转义、XLSX 一字段一列、表头随界面语言
- UX-DR14 逐页内容（A1/A2/A4/A5/A6/A9、B1～B24、C1～C4、D1～D3/D6、E1/E2）以 `后台重构逐页规格.md` 各节的摘要条指标、表格列、抽屉结构、操作表（端点 / 权限 / 确认 / 成功后行为）为准，story AC 逐页引用
- UX-DR15 泳道 9/10 组件与防呆规格、交互流转规格（规格图）作为模板壳实现的验收对照

### FR Coverage Map

| FR | Epic | 说明 |
|---|---|---|
| FR-15A-1～12 | Epic 3 | 数据看板全部 |
| FR-16A-1～5 | Epic 1 | 账号增强 |
| FR-17A-1～7 | Epic 5 | 场所管理（含 A1 场所举报页签） |
| FR-18A-1～4 | Epic 6 | KTP 模块定价 |
| FR-19A-1～3 | Epic 2 | 导航 / 角标 / 顶栏（G0） |
| FR-19A-4 | Epic 7 / 8 / 9 / 10 | 五套模板按页面组分属 |
| FR-19A-5 | Epic 2 | 模板 A 通用规格 + 待办中心 5 页 |
| FR-19A-6～7 | Epic 7 / 8 / 9 / 10（+ Epic 6 的模板 D） | 模板 B/C/D/E 按页面组分属 |
| FR-19A-8 | Epic 6 / 7 / 8 / 9 | 11 条微调按所在页面分属 |
| FR-19A-9 | Epic 2（底账生成）/ Epic 11（核对） | 写操作清单 |
| FR-19A-10 | Epic 2（机制）/ Epic 11（验收） | 三语 |
| FR-19A-11 | Epic 10 | 商城组排序约束 |
| FR-20A-1～10 | Epic 4 | 暖贴全部 |
| FR-21A-1～5、7 | Epic 1 | 角色配置 |
| FR-21A-6 | Epic 1（改角色下拉）/ Epic 11（权限面板重排验收） | |
| FR-22A-1～3 | Epic 6 | 档位新建 |

## Epic List

### Epic 1：账号与角色管理增强（安全底座）
超管能改名、换绑邮箱、配置角色权限，且账号信息一变对方立刻被踢重登；运营专员等岗位的权限运营可自助配置。
**FRs covered:** FR-16A-1～5、FR-21A-1～7
含 AD-1 版本号横切件（全版本第一个 story）、AD-4 角色表与迁移；功能在现有页面样式上落地，B24 / D6 两页套模板 B/D 归 Epic 6（避免向后依赖 Epic 2 的模板壳）。角色配置页仅 SUPER_ADMIN 可进。

### Epic 2：新后台框架与待办中心
运营登录后看到新导航、账号菜单、STAG 角标；早巡五个队列收进「待办中心」组，处置完自动跳下一条。
**FRs covered:** FR-19A-1～3、FR-19A-5、FR-19A-9（底账生成）、FR-19A-10
G0 全局壳 + 五套 fragment 壳 + JS 拆分 + htmx 约定（AD-9/11）在此落地；第一个 story 从 `dev_1.3.0` 代码生成《后台写操作清单》（D-27）。A1 场所举报页签归 Epic 5，A9 暖贴跟进归 Epic 4。

### Epic 3：内容运营数据看板
首页 18 项指标近 7/30 天趋势，帖子类可切「真实用户 / 含种子」，付费可切两口径，数字与口径表逐日相等。
**FRs covered:** FR-15A-1～12
AD-2/3/8；Chart.js 落位；付费卡权限。

### Epic 4：暖贴（虚拟账号评论与回复跟进）
运营找冷帖、用虚拟账号评论（完整审核），真实用户回了虚拟账号后有队列提醒跟进。
**FRs covered:** FR-20A-1～10
AD-6；评论页两页签 + 去评论抽屉 + A9 工作台；`reply_to_comment_id` 预留。

### Epic 5：场所内容管理
查看、编辑、下架、合并用户标记的场所；手填坐标预置冷启动场所；场所举报进统一复核队列。
**FRs covered:** FR-17A-1～7
AD-5；B6 页 + A1 场所举报页签；契约 X-1/X-3。

### Epic 6：运营配置增强（定价、档位、算法参数）
改护照两款样式解锁价、新建充值档位、折叠区重新启用档位；算法参数页整改；配置与安全组页面套模板 D，审计页改 WIB。
**FRs covered:** FR-18A-1～4、FR-22A-1～3、FR-19A-8（算法参数整改、档位只显启用中）
AD-7；D1 / D2 / D3 套模板 D；B23 审计页。

### Epic 7：内容组页面重构
内容管理、评论巡查、顶置、内容标签、排期（并入批量内容）、种子发布、批量工作台 8 页套模板 B/E；内容详情页并入抽屉。
**FRs covered:** FR-19A-4、FR-19A-6～8（排期并入、标签入口收拢、标签码自动生成、分配起止时间）
B1～B5、E1、E2。

### Epic 8：用户组与订单资金组页面重构
用户、用户标签、运营发布身份、兽医订单、AI 订单、支付记录、兽医月结、红色超额 8 页套模板 B；用户详情、订单详情并入抽屉。
**FRs covered:** FR-19A-4、FR-19A-6～8（用户详情增性别/生日、用户标签入口收拢）
B7～B14；支付记录模拟回调仅 stag 渲染。

### Epic 9：兽医与问诊组页面重构
兽医账号页吸收编辑、资质、在线状态、评分为抽屉页签；未成功请求、历史会话查询套模板 B。
**FRs covered:** FR-19A-4、FR-19A-6～8（在线状态/评分并入、开户与资质流程 UI 补全）
B20～B22；C5/C6 退役；删四个模板与路由。

### Epic 10：商城组页面重构（最后一批）
Toko 17 页套模板 A/B/C/D。
**FRs covered:** FR-19A-4、FR-19A-6～7、FR-19A-11
AD-12：排在 v1.4.0 电商线合入 `dev_1.3.0` 之后启动；商品表单页保留独立页套模板 D。

### Epic 11：全量核对与收尾
按《后台写操作清单》逐操作核对不丢一项；三语 CI 绿；ID 语言全站无截断；8 个独立页路由删除确认；权限面板按新导航组重排完成。
**FRs covered:** FR-19A-9、FR-19A-10（验收侧）、FR-21A-6（权限面板重排验收）

**依赖与顺序**：1 → 2 硬前置；3、4、5、6 相互独立可并行；7、8、9 依赖 2 的模板壳；10 额外依赖电商线合入；11 最后。
**Epic 号段**：admin 主题占 Epic 1～11；App 分支主题自 Epic 12 起。

## Epic 1：账号与角色管理增强（安全底座）

超管能改名、换绑邮箱、配置角色权限，且账号信息一变对方立刻被踢重登；运营专员等岗位的权限运营可自助配置。功能在现有页面样式上落地，套模板归 Epic 6。

### Story 1.1：账号变更版本号与自动踢重登（AD-1）

As a 超级管理员，
I want 我改了某个账号的邮箱、角色、权限或把它停用后，对方正在用的后台立刻失效，
So that 账号移交或收权不会留下几个小时的空窗。

**Acceptance Criteria:**

**Given** `admin_accounts` 无版本号列
**When** 执行迁移 `V<ts>__add_admin_accounts_security_version.sql`
**Then** 新增 `security_version INT NOT NULL DEFAULT 0`，存量全为 0 `[L1]`

**Given** 账号 A 已登录（会话内记录登录时的 `security_version`）
**When** 任一写操作使 A 的版本号 +1（本 story 接入停用、改角色、改账号级权限，并暴露 `bumpSecurityVersion(accountId)` 供换绑（1.3）与角色模板改权限（1.5）调用）
**Then** A 的下一次任何 `/admin/**` 请求被 `AdminSessionGuardFilter` 拦截：会话失效、跳 `/admin/login?relogin` `[L1]`
**And** 登录页显示三语提示「账号信息已变更，请重新登录」（key `admin.v130.login.relogin`） `[L1]`
**And** 版本号比对复用过滤器现有的每请求查库，不新增查询 `[L0]`

**Given** 账号 B 的版本号未变
**When** B 正常操作
**Then** 不受影响，无额外跳转 `[L1]`

**Given** 过滤器不重算权限（D-2）
**When** 只有角色模板权限变化而账号自身未变
**Then** 本 story 不处理（由 Story 1.5 触发 bump）`[L0]`

### Story 1.2：修改显示名与 self 护栏

As a 超级管理员或持 `admin.create_account` 的运营，
I want 在账号列表行内直接改某人的显示名，且系统不允许我停用自己或改自己的角色，
So that 人员改名不用重建账号，也不会误操作把自己锁在门外。

**Acceptance Criteria:**

**Given** 账号列表页（现有 `admin-accounts.html`）
**When** 点某行「改名」，行内展开输入框，提交 `POST /admin/accounts/{id}/rename`
**Then** 非空且 ≤100 字通过；否则 422 行内 err（`admin.err.account.displayNameRequired` / `displayNameTooLong`）`[L1]`
**And** 成功后 PRG 回本页，横幅「已改名，对方重新登录后顶栏显示新名」 `[L1]`
**And** 审计 `ACCOUNT_RENAMED`，summary 记「旧名 → 新名」，同事务 `[L1]`
**And** 不递增版本号（改名不影响权限，D-2）`[L0]`

**Given** 操作者是账号 X 本人
**When** 对 X 调用停用或改角色
**Then** 拒绝，`admin.err.account.selfDeactivate` / `selfRoleChange` 明示原因，不落任何变更 `[L1]`
**And** 列表页 X 自己那行的「停用」「改角色」按钮渲染为禁用并注明原因（UI 稿 7-7 防呆 F7-8）`[L2]`

**Given** 现有「最后一个在职超管不可停用/降级」护栏
**When** 本 story 合入
**Then** 该护栏行为不变，回归测试通过 `[L1]`

### Story 1.3：换绑 Lark 邮箱

As a 超级管理员，
I want 把一个账号绑定的 Lark 邮箱换成新邮箱，旧邮箱立刻失去访问权，
So that 人员邮箱变更或账号移交不用停旧建新、权限重配、审计断裂。

**Acceptance Criteria:**

**Given** 操作者为 SUPER_ADMIN
**When** 行内点「换绑」→ 输入新邮箱 → 二次确认弹层复述「旧邮箱 → 新邮箱，旧邮箱立即失去访问权限」→ 提交 `POST /admin/accounts/{id}/rebind-email`
**Then** 邮箱格式校验同创建；与任何 **status=ACTIVE** 账号邮箱重复则 422 `admin.err.account.emailExists`；已停用账号的邮箱可复用（D-21）`[L1]`
**And** 成功后 `lark_email` 更新、`security_version` +1（对方下次请求被踢，Story 1.1 机制）`[L1]`
**And** 旧邮箱的 Lark OAuth 登录立即命中不到白名单 `[L1]`
**And** 审计 `ACCOUNT_EMAIL_REBOUND`，summary 记「旧邮箱 → 新邮箱」，并触发 `AdminAlertService.alertSuperAdmins` `[L1]`

**Given** 目标是 bootstrap 预置超管（env 邮箱）
**When** 尝试换绑
**Then** 拒绝，`admin.err.account.bootstrapRebind`，按钮渲染为禁用 `[L1]`

**Given** 操作者不是 SUPER_ADMIN
**When** 访问换绑端点
**Then** 403，按钮不渲染 `[L1]`

### Story 1.4：角色-权限表与预置角色迁移（AD-4 方案 A）

As a 平台，
I want 运营专员、发货专员、客服、财务四个岗位的权限从代码搬进数据库，账号登录时按表解析，
So that 后续角色配置页能让运营自助改权限，而现有账号行为一丝不变。

**Acceptance Criteria:**

**Given** 迁移 `V<ts>__init_admin_roles.sql`
**When** 执行
**Then** 建 `admin_roles(id, code UNIQUE, name, name_key, role_type SYSTEM|CUSTOM, created_by, created_at, updated_at)`、`admin_role_permissions(role_id FK, permission_code, UNIQUE)`，`admin_accounts` 加 `role_id BIGINT NULL FK ON DELETE RESTRICT` `[L1]`

**Given** 数据迁移 `V<ts>__seed_admin_roles.sql`（独立文件）
**When** 执行
**Then** 四个 SYSTEM 角色行（code = OPERATIONS / FULFILLMENT / SUPPORT / FINANCE，name_key 三语）+ 权限码从 `AdminRole` 枚举当前列表逐条灌入 + 存量账号按 `role` 值回填 `role_id` `[L1]`
**And** 灌入的权限码集合与迁移前枚举完全相等（测试断言）`[L1]`

**Given** `AdminUserDetailsService.resolvePermissions`
**When** 各类账号登录
**Then** 解析顺序：SUPER_ADMIN → 空集隐式全权；OPS_MANAGER → 枚举；CUSTOM → `admin_account_permissions`；其余 → `role_id` 的 `admin_role_permissions` `[L1]`
**And** 四类账号迁移前后登录拿到的权限集合逐位相等（回归测试）`[L1]`
**And** `AdminRole` 枚举四个值的权限列表清空并注明「以表为准」，枚举值本身保留供 `role` 列兼容 `[L0]`

**Given** 新权限码 `place.manage`、`comment.virtual_post` 加入 `AdminPermissions`
**When** 迁移灌入
**Then** **不**授予任何预置角色（超管隐式拥有）`[L1]`

### Story 1.5：角色配置页（AB-21A）

As a 超级管理员，
I want 在「安全 → 角色配置」查看和编辑四个预置角色的权限、新建和删除自定义角色，
So that 岗位权限调整不再需要改代码发版。

**Acceptance Criteria:**

**Given** 页面 `/admin/roles`（仅 SUPER_ADMIN，导航与后台账号并列）
**When** 打开
**Then** 列表列：角色名称 / 类型（系统预置 / 自定义）/ 权限数 / 使用中账号数 / 操作 `[L1]`
**And** 预置角色名称走 message key 三语，只有「编辑权限」；自定义角色有「编辑」「删除」 `[L2]`

**Given** 点「编辑权限」
**When** 进入整页权限矩阵
**Then** 按约 35 个页面维度分组，每页面「查看 / 编辑」两档 + 「其他操作权限」列如实列出，数据源 `AdminPermissions` 72 个常量，一个不漏（测试断言矩阵覆盖全部常量）`[L1]`
**And** 页面维度目录抽为 `AdminPageCatalog`（页面 → 权限码映射），供 Epic 2 导航与权限面板复用 `[L0]`
**And** 保存前高危确认复述新增 / 移除的权限项与「影响该角色下 N 个账号，重新登录后生效」 `[L2]`
**And** 保存成功：写 `admin_role_permissions`、该角色下全部账号 `security_version` +1、审计 `ROLE_UPDATED`（记增减码）、横幅注明「重新登录后生效」 `[L1]`

**Given** 新建自定义角色
**When** 填名称（必填、单语、≤60）+ 勾权限 → 保存
**Then** `code` 自动 `role-<id>`，`role_type=CUSTOM`，审计 `ROLE_CREATED` `[L1]`

**Given** 删除自定义角色
**When** 该角色下仍有账号
**Then** 拒绝，`admin.err.role.inUse` 并给出账号数；无引用时 data-confirm 后删除，审计 `ROLE_DELETED` `[L1]`
**And** 预置角色无删除入口，直接调端点返回 403 `[L1]`

**Given** 代码现状不规整项（评论无独立查看码、`refund.*` 三页共用、复购效果码不一致）
**When** 渲染矩阵
**Then** 如实按现有码展示，矩阵旁注脚说明，不擅自拆码（FR-21A-7）`[L2]`

### Story 1.6：后台账号页接入角色表

As a 超级管理员，
I want 后台账号页的「改角色」下拉能选到预置和自定义角色，权限勾选面板按新导航组分组显示，
So that 账号与角色两页口径一致，配权限时不用对着一长串码猜。

**Acceptance Criteria:**

**Given** 账号列表「改角色」
**When** 展开下拉
**Then** 选项 = OPS_MANAGER（枚举）+ `admin_roles` 全部行（预置 + 自定义）+ CUSTOM；选中后写 `role` / `role_id`，行为仍是给单个账号赋一个角色 `[L1]`
**And** 成功横幅「对方重新登录后生效」；版本号 +1（Story 1.1）`[L1]`

**Given** 账号级「编辑权限」（CUSTOM）面板
**When** 打开
**Then** 勾选项按 `AdminPageCatalog` 的 8 个导航组分组展示，码集合仍是 72 个全集 `[L2]`
**And** 保存逻辑不变，成功后版本号 +1 `[L1]`

**Given** 现有创建 / 停用 / 重新激活流程
**When** 本 Epic 合入
**Then** 全部回归通过；超管上限 5、最后超管护栏不变 `[L1]`

## Epic 2：新后台框架与待办中心

运营登录后看到新导航、账号菜单、STAG 角标；早巡五个队列收进「待办中心」组，处置完自动跳下一条。G0 壳 + 五套模板壳 + htmx 约定在此落地，是后续所有页面的底座。

### Story 2.1：生成《后台写操作清单》验收底账（D-27）

As a 产品与 QA，
I want 一份从当前代码自动生成的后台全部写操作清单，
So that 重构前后逐操作核对「一个不丢」有唯一分母。

**Acceptance Criteria:**

**Given** `dev_1.3.0` 当前代码
**When** 运行 `scripts/ci/list-admin-write-ops.sh`（扫 `admin/**` 的 `@Post|Put|DeleteMapping`）
**Then** 产出 `implementation-artifacts/v1.3.0/后台写操作清单-<yyyyMMdd>.md`：路径 / HTTP 方法 / Controller 方法 / `@PreAuthorize` 权限码 / 所属页面（按 `AdminPageCatalog`），条数与 grep 一致（当前 128）`[L0]`
**And** 同时列出 79 个 GET 页面路由并标出本版将删除的 8 个独立详情页 `[L0]`
**And** 脚本可重复运行，Epic 11 用同一脚本对重构后代码再跑一次做 diff `[L0]`

### Story 2.2：G0 全局壳——新导航、顶栏账号菜单、STAG 角标、登录页

As a 运营，
I want 登录后看到按 8 组重排的侧导航、顶栏显示我是谁、staging 环境有醒目标识，
So that 找页面不用记旧分组，也不会把测试环境当生产误操作。

**Acceptance Criteria:**

**Given** `layout.html`
**When** 重构
**Then** 侧导航抽为 `fragments/nav.html`，数据源 `AdminPageCatalog`（Story 1.5 产出）：8 组 / 49 页按 UI 稿泳道归组，组可展开收起、当前组自动展开当前项高亮；无权限项不渲染，整组无权限不渲染 `[L1]`
**And** 顶栏：语言切换保留；账号菜单常驻「显示名 · 角色徽标」（三语角色名），下拉仅「退出登录」（`POST /admin/logout`，无 data-confirm）`[L2]`
**And** `stag` profile 顶栏渲染黄色「STAG」角标，生产不渲染（与 B12 模拟回调同一 `@StagOnly` 门控）`[L1]`
**And** 登录页 0-5 套新视觉，Lark 主入口 + 超管紧急账密折叠入口一个不少 `[L2]`
**And** `admin.js` 拆为 `admin-core.js`（现状能力 + `htmx:beforeSwap` 对 422/403 放行 + CSRF 注入），全部现有页面回归 `[L1]`
**And** 新增 `scripts/ci/check-i18n-keys.sh`，三包 key 集合不等即失败，接入 CI `[L0]`

### Story 2.3a：htmx 响应约定与 admin/shared 横切件（AD-9）

As a 后续每个页面的实现者，
I want 一套统一的 fragment 响应、错误呈现、WIB 格式化与导出工具，
So that 49 页的交互口径与导出格式一致。

**Acceptance Criteria:**

**Given** `admin/shared/web/`
**When** 落 `HxRequest` 参数解析器与 `AdminBusinessExceptionAdvice`
**Then** `HX-Request` 下 422 返操作区 fragment（行内 err）、403 返禁用态 fragment 注明所缺权限名；非 htmx 请求维持现状 PRG 与错误页 `[L1]`
**And** `admin-core.js` 增 `htmx:beforeSwap` 对 422/403 放行 swap；`HX-Trigger: admin:badge-refresh` 触发 `GET /admin/nav/badges`（`AdminNavController`，单条聚合查询）替换角标片段 `[L1]`
**And** `admin/shared/time/AdminTime`（WIB `yyyy-MM-dd HH:mm`）与 `admin/shared/export/AdminExportWriter`（POI xlsx / RFC 4180 csv / 表头随 locale / 前导 `=` 防注入）+ 单测 `[L0]`
**And** `admin/shared/AdminPageCatalog`（Story 1.5 引入）迁至此包，作为导航、权限矩阵、写操作清单的唯一页面目录 `[L0]`
**And** MockMvc 范式测试：任取一个既有 Controller 加 `HX-Request` 头验证四条（整页 200 / fragment / 422 / 403）`[L1]`

### Story 2.3b：五套模板壳、抽屉与工作台 JS（AD-11）

As a 后续每个页面的实现者，
I want 现成的 A/B/C/D/E 五套模板 fragment、抽屉与工作台 JS、CSS 底座，
So that 页面只填内容不重写壳。

**Acceptance Criteria:**

**Given** `templates/admin/fragments/`
**When** 落 `tpl-a-workbench` / `tpl-b-list` / `tpl-c-report` / `tpl-d-config-card` / `tpl-e-steps`
**Then** 槽位固定（A：tabs/filters/queue/detail/actions；B：filters/summary/table/drawer；C：range/cards/detail；D：cards[]；E：steps/body/footer），Thymeleaf 渲染单测各一 `[L0]`
**And** `admin-drawer.js`（`GET …/{id}/drawer` 开合、ESC 关闭、`?open=<id>` 自动开）与 `admin-workbench.js`（读 `data-next-id` 自动选中下一条、<1024 两屏切换、无键盘 ↑/↓）`[L2]`
**And** `admin.css` 补五壳样式与 UX-DR11 组件底座修正（`.sel/.inp` display、`.sel` 箭头、`.sum` 抽屉内 2×2）、UX-DR12 三语占位规则 `[L2]`
**And** 依赖 2.3a 的 fragment 响应约定；本 story 不改任何业务页 `[L0]`

### Story 2.4：A1 统一复核工作台

As a 运营，
I want 内容送审、内容举报、名称审核、头像审核在一个双栏工作台里按页签清队列，处置完自动到下一条，
So that 早巡不用在四个页面间来回点。

**Acceptance Criteria:**

**Given** `/admin/manual-review` 套模板 A
**When** 打开
**Then** 页签 = 内容送审 / 内容举报 / 名称审核（昵称 + 宠物名 subType）/ 头像审核（用户 + 宠物 subType），各带计数；筛选 = 违规类别 / 优先级 / 关键词（名称头像页签加 subType）；左栏行按逐页规格 A1（送审挂起 >24h 整行红）；默认优先级分降序同分时间升序 `[L1]`
**And** 右栏三卡按页签渲染：对象快照 / 上下文 / 操作区，操作表 8 行（端点 / 权限 / 确认 / 成功后）逐条与逐页规格 A1 一致，**端点与参数零变更**；拒绝原因七选 + 备注常驻处置区未选禁用 `[L1]`
**And** 处置走 htmx：200 + 右栏 fragment + oob 左栏行与页签计数 + `admin:badge-refresh`；成功 toast 2s 自动下一条；失败 422 行内 err `[L1]`
**And** 「人工复核总开关」移到右上齿轮弹层（仅 SUPER_ADMIN），注明当前态与影响 `[L2]`
**And** `reports.html` 与 `GET /admin/reports` 页面路由退役，`reports/**` 处置端点保留供页签调用 `[L1]`
**And** 边界：被审对象已删占位仍可处置举报；账号已注销单据只读 `[L1]`
**And** 页签结构可扩展，场所举报页签由 Story 5.4 追加 `[L0]`
**And** 依赖 2.3a / 2.3b 的约定与壳 `[L0]`

### Story 2.5：A2 被举报用户

As a 运营，
I want 在双栏工作台里对被举报账号做警告 / 限流 / 封号 / 驳回，警告必须写理由，支持批量，
So that 账号级处置有据可查且清队列更快。

**Acceptance Criteria:**

**Given** `/admin/tickets` 套模板 A
**When** 打开
**Then** 页签待处置 / 已处置；筛选举报类型 / 关键词；左栏行 = 昵称 + 状态点 / 类型标签 + 举报人数次数 + 优先级分 + 最早时间，行首勾选框；默认优先级分降序 `[L1]`
**And** 右栏：账号卡（含处置历史 disposalCount 可点开）/ 举报明细（高频举报人打标）/ 近期内容抽样 / 操作区 `[L2]`
**And** 处置区两段式：先单选方式（警告 / 限流 / 封号 / 驳回）再内联参数——**警告新增必填 reason，仅写入审计 `ACCOUNT_WARNED` detail，用户通知文案不变**（FR-19A-8 ①）；限流 = 粒度 × 期限 chips 常驻未选全禁用；封号 = 原因 + 期限 + data-confirm 复述昵称；驳回无参数 `[L1]`
**And** 封号需 `content.dispose_account` **且** `user.deactivate`，缺权限按钮禁用悬浮注明；被举报账号已注销只可驳回 `[L1]`
**And** 勾选 ≥2 浮出批量条（数量 + 批量封号 / 驳回 + 取消），封号权限不足不显示封号钮；批量 data-confirm 复述数量；完成后清勾选 `[L2]`
**And** `tickets/detail` 独立路由退役并入右栏；端点 `warn`（+reason）/ `suspend` / `dismiss` / `batch` / `throttles` / `throttles/lift` 其余参数不变 `[L1]`

### Story 2.6：A4 问诊异常工单

As a 运营，
I want 在双栏工作台里处理问诊异常工单，看会话、加备注、标记已处理，
So that 纠纷处置不用跳独立详情页。

**Acceptance Criteria:**

**Given** `/admin/anomalies` 套模板 A
**When** 打开
**Then** 页签待处理 / 已处理；左栏行 = 工单号 + 异常类型标签 + 状态点 / 用户 × 兽医 + 时间，默认时间倒序 `[L1]`
**And** 右栏：工单卡 / 会话卡（含「去取证」深链历史会话查询带会话号、新开标签）/ 内部备注时间线 + 底部追加框 / 操作区 `[L2]`
**And** 加备注（`anomalies/{id}/note`，停留本条）、标记已处理（`anomalies/{id}/resolve`，下一条），权限 `consult.view_anomalies`，无确认 `[L1]`
**And** RESOLVED 入已处理页签只读，备注仍可追加 `[L1]`
**And** `anomaly-detail.html` 与 `GET /admin/anomalies/{id}` 退役 `[L1]`

### Story 2.7：A5 客服工单

As a 客服运营，
I want 在双栏工作台里看工单正文与附件、关联订单、判定退款需求、结案，
So that 一条工单在一屏内处理完。

**Acceptance Criteria:**

**Given** `/admin/support-tickets` 套模板 A
**When** 打开
**Then** 页签待处理 / 待联系（需联系且未联系）/ 已结案；左栏行 = 标题 + 状态点 / 需联系标记 + CSAT + 时间 `[L1]`
**And** 右栏五区：正文 + 附件（大图）/ 联系卡 / 关联订单区（已关联 → 摘要卡可跳订单；未关联 → 订单号搜索 + 关联钮）/ 退款需求判定区（批准 → 生成退款单显示单号可跳 A6；驳回原因必填）/ 结案区 `[L2]`
**And** 未关联订单时判定区禁用；结案 data-confirm；结案后只读，CSAT 回填后展示 `[L1]`
**And** 端点 `link-order` / `refund-approve` / `refund-reject` / `resolve` 与权限 `support.handle` 不变；`support-ticket-detail.html` 与详情路由退役 `[L1]`

### Story 2.8：A6 退款管理三段流

As a 客服 / 主管 / 财务，
I want 退款单按「判定 → 审批 → 打款」三段在一个工作台里流转，我只看到我这一段的按钮，
So that 职责分离在界面上就成立。

**Acceptance Criteria:**

**Given** `/admin/refunds` 套模板 A
**When** 打开
**Then** 页签 = 待客服判定 / 待主管审批 / 待财务打款 / 已完结·已驳回；左栏行 = 单号 + 渠道标签 + 状态点 / 金额净额 + 阶段 + 时间，默认时间升序 `[L1]`
**And** 右栏：金额卡（收款账号脱敏规则保留；净额≠订单金额黄底提示差额来源）/ 来源卡（跳订单、跳 A5）/ 三段流程条（当前段高亮、完成段操作人时间备注、驳回段理由）/ 当前段操作区 `[L2]`
**And** **仅登录者权限匹配的段渲染操作按钮**：判定 `refund.submit`（驳回需原因）；审批 `refund.approve`（均需备注，驳回 data-confirm）；打款 `refund.payout`（data-confirm 复述金额 + 渠道，附凭证上传）；其余段只读 `[L1]`
**And** 既有职责分离护栏（同一 admin 不得连续两段、SUPER_ADMIN 不豁免）不变，回归通过 `[L1]`
**And** `refund-detail.html` 与 `GET /admin/refunds/{refundToken}` 退役 `[L1]`

### Story 2.9：待办中心组联调与两态收口

As a 运营，
I want 待办中心五页的计数、角标、空态、权限禁用态口径完全一致，
So that 组名上的数字就是我今天要处理的量，清空即真清空。

**Acceptance Criteria:**

**Given** 五页均已套模板 A
**When** 任一页处置成功
**Then** 侧导航组角标与各项计数、页内页签计数三处同源同值（单条聚合查询），即时刷新 `[L1]`
**And** 五页统一「待处理｜已处理」两态；已处理 = 结果徽标 + 操作人 + 时间 + 理由，可按结果 / 时间 / 关键词筛 `[L1]`
**And** 某页签清空时空态提示「其他队列还有 N 条」并给去向链接 `[L2]`
**And** 有查看权无处置权：按钮禁用 + 注明所缺权限名；无任一查看权：菜单项不渲染 `[L1]`
**And** 五页 MockMvc 四条测试齐全；切 ID 语言五页无截断 `[L1/L2]`

## Epic 3：内容运营数据看板

首页 18 项指标近 7/30 天趋势，帖子类可切「真实用户 / 含种子」，付费可切两口径，数字与口径表逐日相等。

### Story 3.1：虚拟 / 种子账号判定单一出口（AD-8）

As a 平台，
I want 「这个账号是不是运营的马甲或种子号」只有一个判定入口，Java 与 SQL 两侧结果一致，
So that 看板、暖评、暖贴跟进三处口径不会各算各的。

**Acceptance Criteria:**

**Given** `User` 实体
**When** 新增 `isSyntheticAccount()`
**Then** `role=ADMIN` 或 `googleSub` 以 `admin:` / `virtual:` / `seed-tailtopia-` 开头为 true；`googleSub` 为 null（Apple 登录）为 false `[L0]`
**And** `SyntheticAccountSql.EXCLUDE_WHERE` 常量片段与之等价（`COALESCE(google_sub,'')` null 安全），`SyntheticAccountParityTest` 用同一批样本断言两侧一致 `[L1]`
**And** 全库 grep 不再有散写的 `LIKE 'virtual:%'` 判定 `[L0]`

### Story 3.2：预聚合长表与 18 项指标查询（AD-2）

As a 运营，
I want 18 项指标每天各算一次存下来，帖子类 9 项同时存「真实用户」和「含种子」两个口径，
So that 看板取数快且历史数字不变。

**Acceptance Criteria:**

**Given** 迁移 `V<ts>__init_ops_daily_metrics.sql`
**When** 执行
**Then** `ops_daily_metrics(id, report_date, metric_key, scope ALL|REAL, value NUMERIC(18,4), computed_at, UNIQUE(report_date, metric_key, scope))` `[L1]`
**And** `DashboardMetric` 枚举 18 个 key（架构模式规则命名）；`admin/dashboard/metrics/` 每指标一个 `*MetricQuery.compute(LocalDate, Scope)`，切日 `(ts AT TIME ZONE 'Asia/Jakarta')::date` `[L0]`
**And** 口径逐项对应 PRD §1 ①-a：#5 新增建档用户数按人去重（D-28）；#3 #4 #8～#14 支持 REAL（作者与互动者均排除合成账号）与 ALL 两口径（D-29）；付费四项按 SQL 口径 6 `[L1]`
**And** `MetricQueryParityTest`：对 stag 脱敏快照的任意 3 天，18 项 ALL 口径与 `内容运营所需数据-20260831.sql` 直跑结果逐项相等（#5 除外，按 D-28 新口径另测）`[L1]`

### Story 3.3：自愈跑批与历史回填（AD-3）

As a 运营，
I want 看板每天自动补齐到昨天的数据，上线当天就能看到 7 月 17 日以来的完整趋势，
So that 不需要任何人手工回填或补跑。

**Acceptance Criteria:**

**Given** `DashboardMaterializer` `@Scheduled(cron="0 0 1 * * *", zone="Asia/Jakarta")`
**When** 运行
**Then** 计算 `[petgo.admin.dashboard.backfill-from=2026-07-17, 昨天(WIB)]` 内缺失日期，逐日单事务写入全部 18 项（含双口径共 27 行/日）；已存在行不重算 `[L1]`
**And** 首次运行完成全量回填（D-15）；模拟某日跑批失败后次日运行自动补齐（OQ-B2）`[L1]`
**And** stag 专用 `POST /admin/dashboard/materialize`（`@StagOnly`，SUPER_ADMIN）手动触发，生产不渲染不注册 `[L1]`
**And** 应用日志只记日期与耗时，不记指标值 `[L0]`

### Story 3.4：看板页五卡图表与范围切换（AB-15A 前端）

As a 运营，
I want 首页五张图表卡按日展示 18 项指标，可切 7/30 天、切口径，缺数据日显示断点，
So that 每天开工先看趋势。

**Acceptance Criteria:**

**Given** `/admin` 套模板 C
**When** 打开
**Then** 自上而下：范围切换器（近 7 天默认 / 近 30 天）→ 五卡（用户增长 / 建档转化 / 内容生产 / 互动质量 / 付费）→ 底部「当前时点总量」摘要行（原四模块数字）+ 种子发布链接 `[L2]`
**And** Chart.js 4.5.x vendored 只在本页引入；切换范围由 htmx 替换 `fragments/dashboard-charts.html`，fragment 内嵌 JSON（`labels` ISO 日期、`series[{key,scope,values|null}]`），`htmx:afterSwap` 重建图表 `[L1]`
**And** 图表形式按 PRD §1 ①：新增柱 / 累计折线（双轴或分图）；两个平均分折线；付费人数次数双序列柱 `[L2]`
**And** 内容生产卡、互动质量卡内 tab「真实用户 / 含种子」默认真实用户；付费卡内 tab「现金到账 / 含 PawCoin 消费」默认现金到账；口径差异提示常驻卡底 `[L2]`
**And** 缺数据日 = null + `spanGaps:false` 断点带黄标注，不画 0；整卡无数据显示空态区别于断点；加载骨架、失败卡内重试 `[L2]`
**And** 每指标口径提示（hover / 图标）文案取自口径表 message key；「累计注册用户数」命名（D-16）`[L2]`
**And** 次日留存一行说明 +「去 PostHog 查看」文案，不做外链 `[L2]`

### Story 3.5：看板权限与验收对照

As a 产品，
I want 付费卡只对有财务查看权的人显示，且看板任一天任一指标能和口径表对上，
So that 数据既不泄露也可信。

**Acceptance Criteria:**

**Given** 登录账号无 `payment.view`（支付记录页同码）
**When** 打开首页
**Then** 付费卡不渲染，其余四卡正常；有权限则渲染（D-17）`[L1]`
**And** 看板所有后台账号可进（与现首页一致，不新增 permission_code）`[L1]`
**And** stag 验收：任取 3 个日期 × 18 项，页面数值 = `ops_daily_metrics` = 口径表定义（Story 3.2 Parity）`[L1]`
**And** 不做导出、不做自定义布局；无 PII `[L0]`

## Epic 4：暖贴（虚拟账号评论与回复跟进）

运营找冷帖、用虚拟账号评论（完整审核），真实用户回了虚拟账号后有队列提醒跟进。

### Story 4.1：帖子评论分布页签

As a 运营，
I want 在评论管理页切到「帖子评论分布」，按评论数、时间、物种筛出冷帖，摘要条给我双口径的篇均评论数，
So that 找冷帖不用一个个点开帖子。

**Acceptance Criteria:**

**Given** `/admin/comments` 升级两页签（页顶常驻，htmx 局部切换）
**When** 切到「帖子评论分布」（`GET /admin/comments/distribution`）
**Then** 筛选：评论数区间（=0 / ≤3 / 自定义 ≤N）+ 发布时间段 + 物种 + 帖子状态（默认全部）+「排除虚拟账号内容」开关（默认开，判定 = 作者 `isSyntheticAccount()`）`[L1]`
**And** 列 = 摘要 / 作者 / 时间 / 评论数（0 红标、≤3 黄标）/ 其中虚拟评论数 / 「去评论」；默认评论数升序；每页 20 `[L1]`
**And** 摘要条：帖子数 · 篇均评论数（含虚拟 / 剔除虚拟）· 0 评论帖数与占比 · 虚拟评论占比；口径 = 当前筛选集、可见评论 `[L1]`
**And** 页签一「评论巡查」功能不变；权限：页签二需 `comment.virtual_post` 或 `content.view`（只看不评）`[L1]`

### Story 4.2：「去评论」抽屉与虚拟身份发布（走完整审核）

As a 运营，
I want 在抽屉里选一个虚拟账号给冷帖写评论，提交后进审核，通过才公开，
So that 暖评与真实用户评论走同一条规则，不会有后台特权评论。

**Acceptance Criteria:**

**Given** 点「去评论」
**When** 抽屉打开
**Then** 帖子预览（图 + 摘要 + 作者 + 物种 + 评论数）→ 已有评论列表（虚拟评论后台可见标识）→ 常驻评论区：身份下拉（启用中虚拟账号，物种匹配优先、可切全部，每项「今日已评 N 条」）+ 文本框（≤200 字）+ 发布钮（未选身份或空内容禁用）`[L2]`
**And** `POST /admin/comments/virtual`（postId + virtualAccountId + text + 幂等 token）→ `VirtualCommentService` 调 `CommentService` 一级评论同一入口，显式传虚拟账号 id，校验其 ∈ 虚拟池且启用；**只发一级评论**（D-35）`[L1]`
**And** L1 黑名单命中 → 422 行内 err；否则落 UNDER_REVIEW，机审通过转 VISIBLE 并通知作者，DEGRADED 进 A1 送审页签（D-4）`[L1]`
**And** 提交成功 toast「已提交，审核通过后显示」；抽屉不关、文本清空；已有评论列表该条显示「审核中」；列表评论数不即时 +1 `[L2]`
**And** 防呆：同账号同帖 10 分钟内再评行内提示确认（二次点击即发）；同帖虚拟评论 ≥3 黄条提示不拦截；不加硬上限（D-20）`[L1]`
**And** 权限 `comment.virtual_post`；审计 `COMMENT_VIRTUAL_POST`（postId / 虚拟账号 / 正文 ≤50 字摘要）；虚拟标识不落任何 `/api/v1` 字段 `[L1]`
**And** 迁移 `V<ts>__add_comments_reply_to_comment_id.sql` 可空列 + `CommentCreateRequest` 可选 `replyToCommentId`，本版只落库不使用（X-2）`[L1]`

### Story 4.3：暖贴回复跟进队列（入队 / 出队）

As a 平台，
I want 真实用户回复了虚拟账号的一级评论就自动生成一条待跟进项，回复被删则自动撤掉，
So that 运营不会漏掉「已读不回」。

**Acceptance Criteria:**

**Given** 迁移 `V<ts>__init_warm_reply_followups.sql`
**When** 执行
**Then** 表含 `virtual_comment_id, post_id, virtual_user_id, pending_reply_count, last_reply_id, last_reply_at, status PENDING|HANDLED, handled_by, handled_at, handled_action REPLIED|READ`，部分唯一索引 `(virtual_comment_id) WHERE status='PENDING'` `[L1]`
**And** `WarmReplyEnqueueListener` 监听 `ContentCommentedEvent`（AFTER_COMMIT + REQUIRES_NEW）：`parentAuthorId` 为合成账号且 `commenterId` 非合成 → 新建或累加 PENDING 项；该事件仅在回复转 VISIBLE 时发布（机审通过或人工 approve），审核中的回复不入队 `[L1]`
**And** 监听评论删除 / 下架事件：对应项 `pending_reply_count` 减一，归零删除（D-19）`[L1]`
**And** 二级虚拟评论被回复不入队（结构限制，D-35）`[L0]`

### Story 4.4：A9 暖贴回复跟进工作台

As a 运营，
I want 在待办中心看到待跟进队列，右栏直接以被回复的虚拟账号身份回复或标记已读，
So that 暖贴闭环不反噬。

**Acceptance Criteria:**

**Given** `/admin/warm-replies` 套模板 A，待办中心组第 6 项，角标 = 待跟进数
**When** 打开
**Then** 左栏 = 虚拟账号 / 帖子 / 回复摘要 / 时间；右栏 = 帖子卡 + 评论线程（新回复高亮）+ 常驻回复区，**身份锁定为被回复的虚拟账号不可切换** `[L2]`
**And** 发布回复 = 二级评论走 `CommentService` 同链路含审核（`comment.virtual_post`）；标记已读；处理后出队自动下一条 `[L1]`
**And** 页签待跟进 / 已跟进（近 30 天）；审计 `WARM_REPLY_READ`；计数并入待办中心角标同源查询 `[L1]`

## Epic 5：场所内容管理

查看、编辑、下架、合并用户标记的场所；手填坐标预置冷启动场所；场所举报进统一复核队列。

### Story 5.1：场所数据模型（AD-5）

As a 平台，
I want 场所及其照片、评论、打卡、举报五张表落库，状态与合并指向明确，
So that 后台与 App 分支对同一套表读写。

**Acceptance Criteria:**

**Given** 迁移 `V<ts>__init_places.sql`
**When** 执行
**Then** `places`（`public_token`、名称 / 类型 / 标签 JSONB / 描述 / `address_text` / `lat lng NUMERIC(9,6)` / `marked_by_user_id` / `status ACTIVE|DELISTED|MERGED` / `merged_into_id` / 五个计数列 / 时间戳 / `deleted_at`）+ `place_photos` + `place_comments`（attitude RECOMMEND|NOT_RECOMMEND）+ `place_checkins` + `place_reports`（status PENDING|DISMISSED|ACTIONED）+ 索引与 CHECK 全集 `[L1]`
**And** 实体、Repository、`PlaceStatus` 枚举；计数列由服务层同事务维护 `[L0]`
**And** 表结构写入 `db-schema-reference.md` 与 `CROSS-STORY-DECISIONS.md` 表归属（契约 X-3）`[L0]`

### Story 5.2：场所列表与详情抽屉（B6）

As a 运营，
I want 「内容 → 场所管理」列表与抽屉查看每个场所的信息、照片、评论、打卡，
So that 巡查场所内容有统一入口。

**Acceptance Criteria:**

**Given** `/admin/places` 套模板 B（导航内容组第 7 项）
**When** 打开
**Then** 筛选：名称 / 地址搜索（显式查询钮）、类型、状态；摘要条：上架数 · 今日新增 · 待处理举报数（跳 A1 场所页签）· 累计打卡数；表格列按 FR-17A-1，默认创建时间倒序，每页 20 `[L1]`
**And** 抽屉 `GET /admin/places/{id}/drawer`：基本信息（坐标数字）/ 照片墙（上传者标注）/ 评论列表（态度标注）/ 打卡计数 / 操作条 `[L2]`
**And** 权限 `place.manage`；无权限菜单不渲染 `[L1]`

### Story 5.3：场所处置——编辑、下架 / 恢复、删照片 / 评论、合并

As a 运营，
I want 修正错误信息、下架违规场所、删掉个别违规照片评论、把重复场所合并，
So that 用户端的场所数据可控。

**Acceptance Criteria:**

**Given** 抽屉操作条
**When** 编辑（`POST /admin/places/{id}/edit`）
**Then** 可改名称 / 类型 / 标签 / 描述 / 文字地址 / 坐标，标记人不可改；坐标校验纬度 [-90,90] 经度 [-180,180] ≤6 位小数，雅加达都会区外黄条警告不拦截；审计 `PLACE_EDITED` `[L1]`
**And** 下架 / 恢复（`delist` / `restore`，data-confirm）：软删 `status=DELISTED`；审计 `PLACE_DELISTED` / `PLACE_RESTORED`；护照章与打卡记录保留（App 侧跳转由契约 X-1）`[L1]`
**And** 删单张照片 / 单条评论（`photos/{pid}/remove` / `comments/{cid}/remove`）：照片允许删至 0；删评论同步扣对应态度计数；审计 `PLACE_PHOTO_REMOVED` / `PLACE_COMMENT_REMOVED` `[L1]`
**And** 合并（`merge`，弹层搜索选保留场所，data-confirm 复述「保留 A、并入 B、不可撤销」）：单事务——三张子表 `place_id` 改指保留方、被合并方 `status=MERGED` + `merged_into_id`、保留方计数重算、发布 `PlaceMergedEvent(keepId, mergedId)`；审计 `PLACE_MERGED` `[L1]`
**And** 全部走 htmx 抽屉内提交，失败 422 行内 err `[L1]`

### Story 5.4：新建场所（冷启动预置，手填坐标）与 A1 场所举报页签

As a 运营，
I want 后台直接录入雅加达核心场所，坐标从 Google Maps 复制手填；用户举报的场所进统一复核队列处置，
So that 冷启动有数据、违规场所有出口。

**Acceptance Criteria:**

**Given** 列表右上「新建场所」
**When** 抽屉表单提交（`POST /admin/places`）
**Then** 字段与用户端一致 + 经纬度两个数字输入（Story 5.3 同校验，D-33）+ 标记人从运营发布身份池选择；不做批量导入、不做地图（D-32）；审计 `PLACE_CREATED` `[L1]`
**And** A1 新增页签「场所举报」：`TicketType.PLACE_REPORT`，左栏行 = 场所名 + 举报类型 + 计数；右栏 = 场所快照 + 举报列表 + 操作区（下架场所 / 删照片 / 删评论 / 驳回举报，调用 Story 5.3 同一批服务方法）；处置后 `place_reports.status` 置 ACTIONED / DISMISSED `[L1]`
**And** 页签计数并入待办中心角标同源查询；权限 `place.manage` `[L1]`

## Epic 6：运营配置增强（定价、档位、算法参数）

改护照两款样式解锁价、新建充值档位、折叠区重新启用档位；算法参数页整改；配置与安全组页面套模板 B/D，审计页改 WIB。（D1 运费配置属商城组，见 Story 10.6）

### Story 6.1：KTP 模块高清图解锁定价三行（AB-18A）

As a 运营，
I want 在运营配置页一张卡里改 KTP 卡高清、护照内页、登机牌三个解锁价，
So that 护照样式上线后能自主调价。

**Acceptance Criteria:**

**Given** 迁移 `V<ts>__add_pricing_config_passport_prices.sql`
**When** 执行
**Then** `pricing_config` 加 `passport_page_unlock_price`、`passport_boarding_unlock_price` BIGINT NOT NULL，默认值 = 当前 `id_hd_download_price`，CHECK `>= 1` `[L1]`
**And** 配置组更名「KTP 模块高清图解锁定价」：三行样式名只读 + 价格输入，行旁展示 KTP 当前价参考；三价独立不联动 `[L2]`
**And** 校验正整数 ≥1，0 或负数 422 行内 err（D-7）；每卡保存钮未修改禁用 → 修改激活 → 高危确认复述新旧值 → 提交 `[L1]`
**And** 改价审计 `PRICING_UPDATED` 逐列 diff；即时生效只影响新发起解锁；权限沿用 `config.edit` `[L1]`
**And** 现有 `/consult/pricing` 同类下发接口增两字段（契约 X-4）`[L1]`

### Story 6.2：PawCoin 档位——只显启用中、查看已停用、新建档位（AB-22A）

As a 运营，
I want 档位卡默认只列启用中的最多 4 档，能展开看已停用并重新启用，还能新建档位，
So that 活动调档不用找工程。

**Acceptance Criteria:**

**Given** 运营配置页 PawCoin 卡
**When** 打开
**Then** 表只列 `enabled=true`（≤4 档）；下方「查看已停用（N）」原地展开灰底第二张表（`GET /admin/config/tiers/disabled` fragment），行内「启用」可点回；启用时启用中已达 4 → 422「先停用一个档位」 `[L1]`
**And** 右上「＋新建档位」→ 抽屉（金额 IDR 正整数）→ `POST /admin/config/tiers`：金额与任何既有档位（含已停用）不重复；创建即启用且 ≤4（`pg_advisory_xact_lock` 防并发）；`tier_key = "t"+amount_idr`；`sort_order` 按金额升序重排；审计 `TIER_CREATED`；权限 `config.edit` `[L1]`
**And** 不提供删除；App 端读档位接口与排序不变（回归）`[L1]`

### Story 6.3：D2 运营配置页整体套模板 D

As a 运营，
I want 运营配置页四张卡（问诊定价 / KTP 定价 / PawCoin 档位 / 分享奖励）统一为每卡自带保存钮的配置卡，
So that 改哪张存哪张，不会误提交别的组。

**Acceptance Criteria:**

**Given** `/admin/config` 套模板 D
**When** 打开
**Then** 四卡各自 `<form>`，保存钮未修改禁用 → 修改激活标「已修改」→ 高危确认 → 提交（PRG）；组头不标红；校验错误行内 err（负数示范）`[L2]`
**And** 分享奖励卡（AB-3M）字段与端点不变 `[L1]`
**And** 权限 `config.view` / `config.edit` 沿用 `[L1]`

### Story 6.4：D3 算法参数页整改与变更记录抽屉

As a 产品，
I want 算法参数页去掉编号术语、11 项一行一项两栏排布，历史变更点按钮进抽屉筛着看，
So that 页面清爽、历史可查。

**Acceptance Criteria:**

**Given** `/admin/algo-params` 套模板 D
**When** 打开
**Then** 页头无 FR 编号；分组名「算法参数」；11 项全集一行一项两栏；右栏底部权限与生效范围提醒；保存 = 确认弹层复述改前改后 `[L2]`
**And** 「变更记录」按钮 → 抽屉（`GET /admin/algo-params/changes/drawer`），按参数 / 时间段筛选，数据来自既有变更日志表，不再常驻页尾（D-10）`[L1]`
**And** 权限现状不动（不对普通运营开放）`[L1]`

### Story 6.5：B23 操作审计（WIB）、B24 后台账号 / D6 角色配置套模板

As a 运营与超管，
I want 配置与安全组其余页面（操作审计、后台账号、角色配置）视觉与交互统一，审计页时间也按印尼时间显示，
So that 这一组从头到尾一个样。

**Acceptance Criteria:**

**Given** `/admin/audit-logs` 套模板 B 只读：时间列 **WIB**（D-22，`AdminTime`），存储与哈希链复算仍用 UTC；页头哈希链校验状态徽标；筛选 + 显式查询钮 `[L1]`
**And** `/admin/accounts`（Epic 1 功能）套模板 B：行内操作归入抽屉，权限面板按 `AdminPageCatalog` 8 组重排（Story 1.6 已实现分组，本处套壳）`[L2]`
**And** `/admin/roles` 套模板 B 列表 + 整页权限矩阵（矩阵不进窄抽屉，UI 稿 7-8～7-10）`[L2]`

## Epic 7：内容组页面重构

内容管理、评论巡查、顶置、内容标签、排期（并入批量内容）、种子发布、批量工作台 8 页套模板 B/E；内容详情页并入抽屉。

### Story 7.1：B1 内容管理 + 详情抽屉

As a 运营，
I want 内容管理列表点行就在右侧抽屉看全文、数据、物种归属、限流并直接处置，
So that 不再跳独立详情页。

**Acceptance Criteria:**

**Given** `/admin/content` 套模板 B
**When** 打开
**Then** 摘要条：总帖数 · 今日新增 · 审核中 · 限流中 · 已下架（单条 `COUNT FILTER` 聚合，随筛选联动）；筛选类型 / 状态 / 物种 / 来源 / 关键词；表格列按逐页规格 B1（含互动积分列，默认创建时间倒序）`[L1]`
**And** 抽屉吸收 `content-detail` 全部字段：全文 + 图片 / 数据卡（含浏览次数人数）/ 物种归属卡（覆写下拉含清除）/ 限流卡（粒度 × 期限常驻 + 解除）/ 操作条（下架 `content.proactive_takedown` data-confirm、恢复 `content.restore`）`[L2]`
**And** 审核中帖抽屉注明不可下架；作者注销置灰；导出走 `AdminExportWriter`（现有 `export.csv` 按规则 12 分列）`[L1]`
**And** `content-detail.html` 与 `GET /admin/content/{postId}` 退役 `[L1]`

### Story 7.2：B2 评论巡查页签套模板 B

As a 运营，
I want 评论巡查列表有摘要条、抽屉看评论全文与所属帖子，
So that 与其他列表页一致。

**Acceptance Criteria:**

**Given** `/admin/comments` 页签一
**When** 套模板 B
**Then** 摘要条：总评论数 · 今日新增 · 已下架；筛选状态 / 帖子 ID / 关键词；抽屉 = 评论全文 + 所属帖子摘要卡（带图预览可跳 B1 抽屉）+ 作者卡 + 操作条（下架 / 恢复端点权限不变）`[L2]`
**And** 用户自删评论只读删除态 `[L1]`
**And** 页签条与页签二（Epic 4）共存，htmx 局部切换 `[L1]`

### Story 7.3：B3 顶置管理

**Acceptance Criteria:**

**Given** `/admin/content-pins` 套模板 B
**When** 打开
**Then** 摘要条：生效中 · 待生效 · 已结束；表格列类型 / 内容标题 / 生效时间（WIB）/ 状态 / 操作；抽屉 = 坑位预览 + 时间设置 + 操作条 `[L2]`
**And** 新建走抽屉表单：类型二选（推广卡片态切换为图 + 跳转配置字段）+ 内容选择器（搜索 + 分页 + 筛选）+ 起止 WIB；改时间 / 终止（data-confirm）端点不变 `[L1]`
**And** 同坑位同时段冲突 422 行内报错并显示冲突项 `[L1]`

### Story 7.4：B4 内容标签（抽屉多页签，打标入口收拢）

**Acceptance Criteria:**

**Given** `/admin/content-tags` 套模板 B
**When** 打开
**Then** 主表只有标签一张表：胶囊预览 / 标签码（自动 `ct-<id>` 不可填）/ 名称 / 说明 / 生效中分配数 / 状态；摘要条生效标签数 · 生效中分配数 `[L1]`
**And** 抽屉页签一「编辑」（三语名称 / 说明 / 胶囊样式 / 图标必传 PNG·WebP 透明底 ≤512KB 最短边 ≥42px；未修改保存禁用）；页签二「分配记录」（内容 / 打标时间 / **起止时间 / 生效·到期状态** / 操作人 / 移除 + 「＋添加内容」= 唯一打标入口，内容选择器正文关键词 / 帖子 ID）`[L2]`
**And** 独立「给内容打标」按钮废除；新建成功自动打开新标签抽屉停在分配页签；下线 data-confirm 复述生效分配数；端点 `content-tags` / `{id}/edit` / `{id}/retire` / `assign` / `assignments/{id}/remove` 不变 `[L1]`

### Story 7.5：B5 排期发布并入批量内容页签

**Acceptance Criteria:**

**Given** 「批量内容」页新增第二页签「排期发布」
**When** 切到
**Then** 摘要条待发布 · 今日已发 · 失败；表格内容摘要 / 发布账号 / 计划时间 WIB / 状态 / 失败原因 / 操作，默认计划时间升序；改时间为行内编辑态（WIB 日期输入 + 保存 / 取消）；取消 data-confirm；端点 `content-schedules/{rowId}/time` / `cancel` 不变 `[L1/L2]`
**And** `GET /admin/content-schedules` 独立页面路由退役，导航项撤销；失败行红点 + 原因悬浮；已发布行只读 `[L1]`

### Story 7.6：E1 种子内容发布（单发）与 E2 批量工作台套模板 E

**Acceptance Criteria:**

**Given** `/admin/seed-post`
**When** 套模板 E 单步
**Then** 只保留单发表单（物种下拉含「留空由算法推导」/ 类型 / 正文 ≤1000 / 图 ≤9 张单张 ≤10MB / 绑定宠物 / 发布身份确认页）；页内旧批量入口删除，`seed-batch` / `seed-batch/import` 旧端点若仅服务此入口一并删除（写入写操作清单 diff 说明）`[L1]`
**And** `/admin/seed-batches` 套模板 E 四步：步骤条（完成步可点回）+ 每步底部吸底「← 上一步 / 下一步 →」（有未保存行先提示）；素材步共用图池 + 命名引用说明常驻折叠卡；录内容步行内编辑态 / 整表粘贴 / Excel 导入，行级发布账号可混；预览确认步校验结果列 + 点行成帖效果抽屉 + data-confirm 复述行数；交互骨架与端点不变 `[L2]`
**And** 批次列表页排期区块与 B5 同源 `[L1]`

## Epic 8：用户组与订单资金组页面重构

用户、用户标签、运营发布身份、兽医订单、AI 订单、支付记录、兽医月结、红色超额 8 页套模板 B；用户详情、订单详情并入抽屉。

### Story 8.1：B7 用户列表 + 五页签详情抽屉

**Acceptance Criteria:**

**Given** `/admin/users` 套模板 B
**When** 打开
**Then** 摘要条总用户 · 今日新增 · 已停用 · 已删除；筛选关键词（ID / 昵称 / 邮箱 / 手机号）/ 状态；列 ID / 昵称 / 邮箱 / 注册时间 / 手机号 / 状态；「导出召回名单」走 `AdminExportWriter` `[L1]`
**And** 抽屉五页签吸收 `user-detail`：基本信息 / 宠物档案（**增列性别、生日**，FR-19A-8 ②；卡片网格）/ 发布内容（点行跳 B1 抽屉）/ 历史问诊（跳历史会话查询）/ 处置（停用 data-confirm、重新激活、赠送 PawCoin `user.grant_pawcoin` data-confirm 复述昵称 + 金额、删除账号类型必选 + 备注必填 + 双确认）`[L2]`
**And** 端点权限不变；已删除账号行置灰抽屉只读；`user-detail.html` 与 `GET /admin/users/{userId}` 退役 `[L1]`

### Story 8.2：B8 用户标签（与内容标签同构）

**Acceptance Criteria:**

**Given** `/admin/user-tags` 套模板 B
**When** 打开
**Then** 主表徽章预览 / 标签码（自动 `ut-<id>`）/ 名称 / 说明 / 生效中分配数 / 状态；抽屉编辑（图标必传、无徽章底色预设）｜ 分配记录（用户 / 分配时间 / **起止时间 / 状态** / 操作人 / 移除 + 「＋添加用户」唯一入口，多选用户选择器、注销不可选、满 3 顶掉最早含确认复述）`[L2]`
**And** 独立「批量分配」按钮废除；退役 data-confirm 复述生效分配数；端点不变 `[L1]`

### Story 8.3：B9 运营发布身份（双区块）

**Acceptance Criteria:**

**Given** `/admin/virtual-accounts` 套模板 B 双区块
**When** 打开
**Then** 区块一虚拟账号：摘要条（数 / 启用中 / 累计发布）+ 表 + 抽屉（资料 / 启停 / 物种定位改写）；区块二真实账号身份池：搜索纳入（授权说明必填）+ 池表 + 移出；端点 `virtual_account.manage` 不变；被种子内容引用的身份不可移出（提示引用数）`[L1/L2]`
**And** `GET /admin/virtual-accounts/{userId}` / `publish-identities/{userId}` 独立详情路由如存在则并入抽屉后退役 `[L1]`

### Story 8.4：B10 兽医订单 + B11 AI 订单（抽屉吸收详情页）

**Acceptance Criteria:**

**Given** `/admin/consult-orders` 与 `/admin/ai-orders` 套模板 B
**When** 打开
**Then** 兽医订单：摘要条本期成交额 · 单数 · 待核查数；列按 B10；抽屉成交快照卡（单价快照只读注明）/ 阶段时间线 / 待核查标记操作（`{token}/verify`）`[L2]`
**And** AI 订单：顶部收入汇总六格保留；列按 B11；抽屉全只读；导出 `order.export` 走 `AdminExportWriter` `[L1]`
**And** `consult-order-detail.html` / `ai-order-detail.html` 与两条 `/{orderToken}` 路由退役 `[L1]`

### Story 8.5：B12 支付记录 + B13 兽医月结 + B14 红色超额

**Acceptance Criteria:**

**Given** 三页套模板 B
**When** 打开
**Then** B12：摘要条本期笔数 · 成功金额 · 失败/过时数；筛选用途 / 状态 / 用户 / 时间段；**模拟回调三钮仅 `@StagOnly` 渲染并标「仅测试环境」，生产纯只读**；终态不可再模拟 `[L1]`
**And** B13：摘要条待打款单数 · 金额合计 · 本月已打款；抽屉订单构成 + 凭证 + 操作（确认打款 data-confirm 复述兽医名 + 金额附凭证；归档；凭证缺失不可归档）`[L2]`
**And** B14：摘要条待核查用户数；抽屉红色评级历史 + 标记操作（`red-overage/{userId}/review`）；与兽医月结拆帧 `[L2]`

## Epic 9：兽医与问诊组页面重构

兽医账号页吸收编辑、资质、在线状态、评分为抽屉页签；未成功请求、历史会话查询套模板 B；两组合一组置后。

### Story 9.1a：B20 兽医账号——列表、资料 / 账号页签、开户

**Acceptance Criteria:**

**Given** `/admin/vets` 套模板 B
**When** 打开
**Then** 摘要条兽医总数 · 在线数 · 资质待审 · 已封禁；列 ID / 昵称 / 账号 / 状态 / 资质态 / 在线 / 评分 / **最后在线** / 创建日期 `[L1]`
**And** 抽屉页签：资料（昵称 / 邮箱 / 手机 / 头像上传，`vet.edit`）｜ 账号（重置密码 ≥8 位 data-confirm、封禁 / 解封 data-confirm）；资质与评分页签占位由 9.1b 填充 `[L2]`
**And** 开户：右上按钮 → 抽屉表单（昵称 / 邮箱 / 手机 / 初始密码「仅本次可见」提示保留），`vet.create`；流程逐步交互按 UI 稿 6-3 `[L2]`
**And** 在线态并入列表列与资料页签（D-11）；`vet-edit.html` 与 `vets-online.html` 及对应 GET 路由退役；导航去掉「在线状态」 `[L1]`
**And** 写端点 `/admin/vets`、`{id}`、`{id}/avatar`、`{id}/password`、`{id}/status` 不变 `[L1]`

### Story 9.1b：B20 兽医账号——资质页签与评分并入

**Acceptance Criteria:**

**Given** 9.1a 的抽屉
**When** 填充资质页签
**Then** KTP / SIPDH / STRV 编号·机构·有效期·证图 + 学位证 / PDHI / 专长；直录 / 续期表单；审核通过 / 驳回必填理由（`vet.qualify`）；写端点 `{id}/qualification*` 不变 `[L1/L2]`
**And** 评分页签：原 `ratings` 页明细并入（`rating.view`）`[L2]`
**And** `vet-qualification.html` / `ratings.html` 与对应 GET 路由退役；导航去掉「兽医评分」 `[L1]`

### Story 9.2：B21 未成功请求 + B22 历史会话查询

**Acceptance Criteria:**

**Given** 两页套模板 B
**When** 打开
**Then** B21：子区活动 / 已归档；列按逐页规格；抽屉操作保存备注 / 标记跟进 / 归档（端点不变）`[L1]`
**And** B22：查询条件四项 + 显式查询钮；结果表；行抽屉 = 会话取证视图；A4「去取证」深链落本页带会话号自动开抽屉（`?open=`）`[L2]`

## Epic 10：商城组页面重构（最后一批）

Toko 17 页套模板 A/B/C/D。AD-12：本 Epic 全部 story 排在 v1.4.0 电商线合入 `dev_1.3.0` 之后启动，启动前先用 Story 2.1 脚本重新生成商城组写操作清单。

### Story 10.1：A7 Toko 退货审核 + A8 Toko 异常订单（模板 A，导航在商城组）

**Acceptance Criteria:**

**Given** `/admin/shop/returns` 套模板 A
**When** 打开
**Then** 页签待审核 / 待寄回 / 待质检 / 待退款 / 已完结·已驳回；右栏五步进度条 / 退货行表 / 退款试算卡 8 行 / 用户说明与凭证 / 当前步操作区 + 「查开封判例」链接；操作表按步（审核批准/驳回原因必填、登记寄回、质检通过入库处置方式必选 / 不通过原因、执行退款 data-confirm 复述总退回）端点不变 `[L1/L2]`
**And** `/admin/shop/order-exceptions` 套模板 A：页签待处理 / 已处理；右栏异常原因卡 / 订单行表（行级勾选）/ 操作区（部分取消 ≥1 行 data-confirm、整单取消并退款 data-confirm、联系用户后继续）；权限 `shop.order_fulfill` `[L1/L2]`
**And** 两页计数不进待办中心角标（导航归属商城组）`[L1]`

### Story 10.2：B15 Toko 订单履约 + C1 Toko 对账

**Acceptance Criteria:**

**Given** `/admin/shop/orders` 套模板 B
**When** 打开
**Then** 摘要条待发货 · 在途 · 今日签收；筛选含收件人电话独立搜索；抽屉订单卡（金额段 + 时间轴）/ 商品行表（退货规则列）/ 收货信息 / 包裹表（逐包裹标记送达）/ 操作条（发货填承运商单号、标记整单送达 data-confirm、包裹送达）；`shop/orders/{token}` 详情路由退役；异常挂起订单打标链到 A8 `[L1/L2]`
**And** `/admin/shop/reconciliation` 套模板 C：期间筛选 + 四核对卡字段原样；校验行不平整卡红框；只读标识 `[L2]`

### Story 10.3：B16 商品管理（列表模板 B + 表单页模板 D）+ B17 Banner 抽屉化

**Acceptance Criteria:**

**Given** `/admin/shop/products` 套模板 B
**When** 打开
**Then** 摘要条上架数 · 下架 · SKU 总数；列按 B16；操作「编辑」+ 上 / 下架（下架 data-confirm）`[L1]`
**And** 表单页保留独立路由套模板 D 四分组卡（基本信息 / 图片与详情 / 每日建议喂量 / 规格与价格）各自独立保存钮；商品主体与 SKU 两个 `<form>` 现状保留；进货价 `shop.cost_view` 门控；喂量区间不重叠校验；有在途订单的 SKU 不可删 `[L1/L2]`
**And** `/admin/shop/banners`：表格预览图 / 尺寸 / 权重 / 状态三档（生效中 / 已上架被压 / 未上架）；新建 / 编辑收进抽屉（图 objectKey + 权重）；上下架 / 删除（data-confirm 不可恢复）端点不变；纯展示不可点击 `[L2]`

### Story 10.4：B18 库存管理 + 流水页 + B19 开封判例

**Acceptance Criteria:**

**Given** `/admin/shop/inventory` 套模板 B
**When** 打开
**Then** 摘要条售罄 SKU · 低库存 · 锁定合计；抽屉 SKU 流水摘要 + 四操作页签（采购入库 / 退货入库 / 报损原因必填 data-confirm / 盘点调整 data-confirm 复述差异）`shop.inventory_edit`；`/inventory-movements` 独立只读页套模板 B `[L1/L2]`
**And** `/admin/shop/return-precedents` 独立页：业务定位提示常驻；表情形 / 判定 / 理由 / 时间 + 检索；沉淀判例表单；A7 「查判例」落本页检索 `[L2]`

### Story 10.6：D1 服务范围与运费配置（模板 D）

**Acceptance Criteria:**

**Given** `/admin/shop/shipping` 套模板 D
**When** 打开
**Then** 三卡（可配送区域行内编辑 + 新增行 + 启停开关 / 免运门槛 / 退货收件地址）各自保存钮（未修改禁用 → 高危确认）；`shop.*` 权限沿用；端点不变 `[L2]`

### Story 10.5：C2 复购引擎效果 + C3 销售与毛利 + C4 库存周转（模板 C 只读）

**Acceptance Criteria:**

**Given** 三页套模板 C
**When** 打开
**Then** 区块字段现状原样图表化 + 只读标识；C4 统计窗口筛选 + 建议动作列保留；权限沿用（C2 `config.view` 或 `order.view`；C3/C4 `shop.finance_view`，如实按现状）`[L2]`

## Epic 11：全量核对与收尾

按《后台写操作清单》逐操作核对不丢一项；三语 CI 绿；ID 语言全站无截断；8 个独立页路由删除确认；权限面板重排完成。

### Story 11.1：写操作清单 diff 与端点零丢失核对

As a QA，
I want 用 Story 2.1 同一脚本对重构后代码再生成一份清单并与基线 diff，
So that 128 个写操作一个不丢有机器证据。

**Acceptance Criteria:**

**Given** 重构后 `dev_1.3.0`
**When** 运行 `list-admin-write-ops.sh` 并 diff 基线
**Then** 新增端点仅为本版 PRD 定义（AB-15A～22A 与预留字段），无 PRD 外新增；删除端点仅为 Story 7.6 注明的旧批量入口与 8 个独立详情页 GET 路由，其余 128 个写端点路径 / 方法 / 权限码逐条一致 `[L0]`
**And** diff 结果写入 `implementation-artifacts/v1.3.0/后台写操作清单-核对-<日期>.md` `[L0]`

### Story 11.2：三语与 ID 占位全站验收

**Acceptance Criteria:**

**Given** `check-i18n-keys.sh`
**When** CI 运行
**Then** zh_CN / en / id 三包 key 集合相等，`admin.v130.*` 无缺 `[L0]`
**And** 切 ID 语言 49 页 + G0 逐页截图核对：导航、页签、按钮、表头、徽标无截断（UX-DR12）`[L2]`

### Story 11.3：退役路由与模板清理确认

**Acceptance Criteria:**

**Given** 8 个独立详情页 + `reports` 页 + `content-schedules` 页 + `vets/online` + `ratings`
**When** 访问旧地址
**Then** 404（不做跳转，D-23）；对应模板文件已删；无残留 `th:href` 指向它们（grep 为零）`[L0/L1]`

### Story 11.4：权限矩阵与导航一致性收口

**Acceptance Criteria:**

**Given** `AdminPageCatalog`
**When** 对照导航 8 组 49 页与角色矩阵 35 页面维度
**Then** 每个导航项都有对应页面维度与查看权限码；每个写端点的 `@PreAuthorize` 码都出现在矩阵中；无权限账号访问 49 页中任一页均 403 且菜单不渲染 `[L1]`
**And** 账号页 CUSTOM 权限面板分组与角色矩阵分组一致 `[L2]`

### Story 11.5：stag 全量验收与 UI 稿比对

**Acceptance Criteria:**

**Given** stag 部署本版
**When** 按 UI 稿 8 泳道 49 页逐页比对
**Then** 每页形态与逐页规格一致（摘要条指标、列、抽屉结构、操作表），差异记入 `L2-视觉验收报告-v1.3.0.md`；看板 3 日 × 18 项数值核对通过；待办中心角标与页签计数同源 `[L2]`

## 拆分校验记录（2026-09-09）

- **FR 覆盖**：59 / 59，脚本校验无遗漏；UX-DR 15 条均有承接 story（DR1～3 → 2.2 / 2.9；DR4～5 → 2.3～2.9；DR6 → 2.3 与各 B 类页；DR7 → 3.4 / 10.5；DR8 → 6.3；DR9 → 7.6；DR10 → 3.4；DR11～13 → 2.3；DR14 → 各页 story；DR15 → 2.3）。
- **架构一致**：无 starter（brownfield）；8 支迁移分别落在首个需要它的 story（1.1 / 1.4 ×2 / 3.2 / 4.2 / 4.3 / 5.1 / 6.1），无「一次建全表」。
- **依赖流向**：Epic 1 → 2 硬前置；3～6 只依赖 1、2；7～9 依赖 2 的模板壳；10 额外依赖 v1.4.0 合入（AD-12）；11 最后。Epic 内 story 均只依赖前序。跨 Epic 的向前引用只有 Story 1.5 的 `AdminPageCatalog` 被 2.2 / 1.6 / 11.4 复用，方向正确。
- **文件重叠**：后台账号页在 Epic 1（功能）与 Epic 6（套模板）各碰一次，属有意分离（避免 Epic 1 依赖 Epic 2 壳），已在 Epic 1 说明。
- **规模**：11 Epic / 59 Story（就绪度评审后拆 2.3 → 2.3a/b、9.1 → 9.1a/b，新增 10.6）；单 story 均可由单个实现会话完成；每条 AC 标 L0 / L1 / L2。
- **待外部**：Epic 10 启动时点取决于电商线合入日期；契约 X-1～X-4 的 App 侧由 App 分支排期。
- **story 阶段修正（2026-09-09）**：退役数量按代码为 17 条 GET 路由 / 16 个模板（见 Story 11.3）；迁移 8 → 10 支（1.3 邮箱部分唯一索引、1.5 ROLE_TEMPLATE）；AD-8 拆为看板用 `isSyntheticAccount()` 与暖评用 `isVirtualPoolAccount()`；已有详情 GET 的页面复用原 mapping 返抽屉，不新增 `/drawer` 端点。
