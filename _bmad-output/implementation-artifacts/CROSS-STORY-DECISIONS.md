# 跨 Story 决策与风险台账（PetGo V1.0）

> 来源：46 份 story 文件交叉冲突扫描（2026-06-02）。本文件是**跨 story 契约/数据生命周期决策的单一事实源**。dev 实现时如遇本表覆盖的点，以此为准。

## 已拍板决策（已回填进对应 story）

| # | 类别 | 决策 | 落点（已改文件） |
|---|---|---|---|
| **C1** | 契约 | 「当前用户」资源统一用 **`/api/v1/me`**（GET 聚合 / PATCH 改昵称头像状态 / DELETE 注销），**全平台不用 `/users/me`** | 1-6, 1-7, 2-4（原 `/users/me` 已改）；7-1/7-3 本就用 `/me` |
| **C2** | 契约 | `role` 三态归属：**`USER`@1.3、`ADMIN`@3.1（建 Admin shell 时）、`VET`@5.1**；5.1 只补 VET，不重复引入 ADMIN | 5-1（措辞 + Project Structure + References） |
| **C3** | 契约 | 点赞/举报表统一 **`content_likes` / `content_reports`**（带模块前缀），勿用 `likes`/`reports` | 3-4（去掉「或 likes」）；3-7 本就用 content_reports |
| **D1** | 数据生命周期 | 注销时 **`consult_sessions`/`consult_ratings` 匿名化保留**（剥 user PII，保留症状/评级/评分供运营 FR-33 与未来 FR-5 库），与 UGC 一致；**`triage_tasks` 仍物理删除**（纯个人 AI 健康记录） | 7-3（AC2 + B3 删除列表 + 新增 B5b + Dev Notes 权威分类清单 + J2） |
| **D2** | 数据生命周期 | 注销时**腾讯 IM 聊天媒体**：调 IM 删除该用户会话媒体，或确认 IM 侧 TTL 自动清理（二选一，dev 落实并记录）；存档到私密桶②的副本随个人图删除。**不可「按隐私边界处理」含糊带过** | 7-3（新增 B5c + Dev Notes） |
| **E1** | 状态机 | `SessionStatus` 接受第 6 态 **`CANCELLED`**（`WAITING → CANCELLED`，等待中用户主动取消，5.3 引入） | architecture.md §Communication Patterns 状态机已回填 |
| **E2** | 基建 | **Flyway 序号按 dev 执行顺序单调分配**，勿照搬 architecture 示例号（示例 `V2__init_profile` 与 1.3 实占 `V2__init_auth` 会撞）；各 story 用 `V<n>__` 占位 | sprint-status.yaml 头注（全局约定） |
| **E3** | 依赖 | **Admin shell 是跨 Epic 硬依赖**：3.1（Epic3）首建 `/admin/**` 门控 + `role=ADMIN` + `admin/layout.html` + ADMIN 账号种子；5.1/5.6/5.7（Epic5）复用，不重复建 | 5-1 References 标注依赖 3-1 |
| **E4** | 隐私 | EXIF 剥离**客户端为主路径 + 公开桶服务端兜底**：公开桶对外图（尤其 H5 名片）必须经 OSS `x-oss-process` 去元数据或后端重处理，防改过的客户端绕过客户端剥离 | 2-1（AC2 + B2 兜底方法）；2-6（B2 H5 图必走服务端去 EXIF） |
| **E5** | 安全 | 4.2 安全层加**保守否定语境处理**：仅当高危词被紧邻否定**且全文无其他命中**才不计；**存疑即升红**，否定逻辑绝不漏真实急症 | 4-2（B3 匹配逻辑 + J1 否定测试用例） |

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
| `comments` | 3.5 | |
| `content_likes` | 3.4 | C3 统一命名 |
| `content_reports` | 3.7 | C3 统一命名 |
| `triage_tasks` | 4.1 | 4.2 接后置挂载点 |
| `vet_accounts` | 5.1 | |
| `consult_sessions` | 5.3 | 5.4/5.6/5.7 ALTER |
| `consult_ratings` | 5.6 | |
| `health_events` | 2.5 | |
| `notifications` | 6.1 | |

## 跨 story 共享设施归属（扫描确认链路连贯）

- **Admin slice**：3.1 首建 → 3.7（举报队列）/5.1（兽医 CRUD）/5.6（评分查看）/5.7（封禁）复用。
- **`shared/media`（StsService/SignedUrlService/AliyunOssClient）**：2.1 建；`ImToOssArchiver` 2.1 占位 → 2.5 实现 → 5.x 用。
- **`SafetyRuleLayer`**：4.1 预留挂载点 → 4.2 实现。
- **`NotificationService`**：6.1 建 → 6.2/6.3/6.4 用。
- **会话状态机**：5.3 入口(WAITING/CANCELLED) → 5.5 接单(IN_PROGRESS) → 5.6 收尾(PENDING_CLOSE/CLOSED) → 5.7 中断(INTERRUPTED) → 5.8 视图收口。
- **`AccountDeletionJob`**：7.3 建（消费各模块 `deleteByUserId`/`anonymizeByUserId`）。
