# Validation Report — Bug 管理与分析闭环系统

- **PRD:** `_bmad-output/planning-artifacts/bug-system/PRD.md`
- **Rubric:** `.claude/skills/bmad-prd/assets/prd-validation-checklist.md`
- **Run at:** 2026-06-30T11:36:53Z
- **Grade:** Fair

## Overall verdict

这是一份与自身形态高度契合、克制得当的内部工具 PRD：三期分阶段论点清晰、数据模型即事实源、字段命名贯穿全文，且正确地未塞入消费级套件（人物画像 / 市场差异化 / 具名 UJ 均属正确缺席）。作为"做什么"的纲领，它够用、可读、可抽取给 story。

但对抗式评审显著改变了画面：整套**安全保证目前几乎全停在"行为愿望"层，没有落到可执行的机制层**——而安全恰恰是本系统自立的卖点。两条 Critical 直指命门：① "文档内容视为数据而非指令"（NFR3）不可实现/不可验证，且截图喂视觉模型是更大的注入面，PRD 只字未提；② 字段白名单（FR9）只是"声明"，Lark 无字段级写授权，白名单必须在代码层强制。叠加状态机死角（Verified 无法重开）、读—确认—写回的丢失更新竞态、iOS 交付在第四期缺席等 High 项。结论：**骨架可放行，但 Epic 3（反向同步）上线前必须把安全脊柱从愿望补成机制。**

## Dimension verdicts

- Decision-readiness 决策就绪 — adequate
- Substance over theater 实质而非摆设 — strong
- Strategic coherence 战略连贯 — adequate
- Done-ness clarity 完成度判据 — strong
- Scope honesty 范围诚实 — adequate
- Downstream usability 下游可用性 — adequate
- Shape fit 形态契合 — strong

## Findings by severity

### Critical (2)

**[对抗式]** — "内容当数据而非指令"是愿望不是规约，注入面完全敞开（NFR3, Story 3.2 AC4, FR6）
读不可信内容与做写决策的是同一个 Claude，数据与指令间无架构边界；AC4 不可测；截图喂视觉模型是更大的、PRD 未提的注入面。
Fix: ① 提报字段包进显式定界块 + 系统提示声明块内祈使句非指令；② 写回是受限工具只接受 (record_id, C组白名单字段, 新值)；③ 补 FR 声明视觉读图结果同为不可信数据；④ NFR3 补可测断言（注入语料集含图片回归"呈现而不执行"）。

**[对抗式]** — 字段白名单只"声明"未"强制"，提报人可借注入翻自己的验收结论（FR9, Story 3.2 AC3）
Lark Bitable 无字段级写授权，有写权限即全表可写；白名单必须代码层硬编码字段 ID，不能依赖 Claude 判断。
Fix: FR9 改写为机制要求——写回经受限函数、代码层维护 C 组白名单、非白名单字段抛错拒绝；Story 3.2 加 AC：试写 Verifier/Verification Result 被代码层拒绝。

### High (8)

**[Decision-readiness]** — 决策闸门"效果满意"无判据（§8.1, §9 第3步）
二期启动系于纯主观词"效果满意"，无可判定口径。
Fix: 给 Epic 2 一句可度量退出标准（如对 N 条历史 Bug 文件级定位 + 人工复核认可 ≥X%）作为进 Epic 3 的 gate。

**[Downstream usability]** — 写回字段白名单自相矛盾，C组 vs D组（FR9, Story 3.2 AC3 vs Story 4.1 AC2）
二期"只写 C 组、不碰 D 组"，三期却要写回 D 组 APK 字段，未调和。
Fix: 白名单显式定义为"C 组 + D 组工程师填写的 APK Link / APK Version"，排除 D 组验收人字段。

**[对抗式 H1]** — 确认闸门被过载、实操可被绕过（NFR2, Story 3.2 AC1/AC2）
确认粒度未定、"明确确认"未定义（可被对话早先概括授权）、diff 未要求旧值→新值对照。
Fix: 以"一次写批次"为确认单位、逐字段展示旧值→新值、禁跨对话概括授权、明确"全部确认"上限+二次确认。

**[对抗式 H2]** — 状态机死角：Verified 之后无法重开（FR2, §5 状态图, FR11）
Reopened 只有"验收 Fail"一个入口；Verified 的 Bug 生产复发后无合法迁移。
Fix: 补 Verified──复发──>Reopened（或 →In Progress），写明触发条件与操作人；修正"已关闭视图"为"可重开的关闭"。

**[对抗式 H3]** — Won't Fix / Duplicate 只进不出，且缺 Duplicate Of 字段（FR2, §5）
误判不可改回、判重无指向字段、Won't Fix 工程师单方决策无申诉回路。
Fix: 补 Duplicate Of 字段、明确可逆性、给 Won't Fix 加轻量复核/申诉约定。

**[对抗式 H4]** — 并发：读—确认—写回是丢失更新竞态，NFR8 未解决（FR12, NFR8, Story 3.2, §8.2）
两人基于各自旧快照写回会互相覆盖；AC5"回读校验"恰把覆盖确认为成功。
Fix: 写回前重读目标记录与基线比对，漂移即中止提示（乐观锁/last-modified）；补"认领"约定；写进 NFR8 与 Story 3.2 AC。

**[对抗式 H5]** — iOS 修复在第四期闭环里整个缺席（FR3, Epic 4, §3.3）
Platform 含 iOS，但交付验收全程 APK（Android-only），iOS Bug 无交付/验收路径。（rubric 亦以 medium 标注）
Fix: (a) 第四期显式 Android-only + iOS 临时关闭约定；或 (b) 交付字段泛化为"构建产物"（APK/TestFlight/Ad-hoc IPA），Story 4.1/4.2 覆盖 iOS。

**[对抗式 H6]** — APK 交付链路：访问权限/失效/版本全是挥手带过（FR10, Story 4.1）
"约定的云盘位置"非规约；谁有读权限、链接还是附件（§5 两可）、过期后追溯断裂、两个版本字段强制一致是设计味道。
Fix: 规定固定云盘目录与权限模型、统一 APK Link 类型（建议附件）、明确版本字段差异或合并。

### Medium (8)

**[Strategic coherence]** — 无成功指标（§1.1）：Goals 全定性，闭环成效无法事后验证。Fix: 加 2–3 条轻量运营指标（流转天数、AI 定位采纳率、人工搬运削减量）。

**[Done-ness clarity]** — 非法流转处置二义（Story 3.3 AC3）："阻止或提示"未定。Fix: 一律 hard block，放行须再次人工确认。

**[对抗式 M1]** — Bug ID 用"自动编号"做主键不可靠（A组, FR8）：展示字段非 record_id。Fix: join key 明确为 record_id，Bug ID 仅展示。

**[对抗式 M2]** — Reopened 二轮不清场、验收历史被覆盖（Story 4.2, §5 D组）。Fix: 规定 Reopened 时字段清空/更新；验收备注改追加式或单列验收历史。

**[对抗式 M3]** — 本地 Bug 数据含用户 PII、缺留存/清理策略（NFR1, Story 2.2 AC5）。Fix: 补 NFR——本地数据/截图设留存期与清理、不二次外传、可最小化。

**[对抗式 M4]** — "置信度"未定义、低置信度是否回写未规定（Story 2.3 AC3, FR7）。Fix: 定义刻度 + 回写门槛（低档不自动回写或标"AI 推测未确认"）。

**[对抗式 M5]** — Suggestion 走不通 Bug 状态机（B组, §5, Epic 4）。Fix: 给 Suggestion 独立流转（Accepted/Declined/Planned）或说明复用语义。

**[对抗式 M6]** — Epic 3 后只读约束退化为约定（Story 2.3 AC5, NFR4, Story 3.1）。Fix: 保留独立只读应用/凭证（双应用），或承认约定化并纳入代码层护栏。

**[对抗式 M7]** — 缺劣质/缺失提报输入的处理需求（FR6, Story 2.2/2.3）。Fix: 补 FR——输入退化时稳健降级（不崩、标"信息不足"、列需补充清单）。

### Low (5)

**[Scope honesty]** — 无 Non-Goals 集中声明（全文）。Fix: 加"本期不做"节，显式化 NFR2/NFR7 隐含非目标。

**[Downstream usability]** — Assignee 是否可写回悬空（§5 C组, Story 3.2 AC3）。Fix: 白名单显式纳入或排除 Assignee。

**[对抗式 L1]** — 服务→仓库映射"按实际调整"让 FR6 核心输入悬空（§4.3, §4.1）。Fix: Epic 1/2 前把真实映射与 Module 取值定稿为前置交付物。

**[对抗式 L2]** — 一批不可测 AC（Story 2.3 AC1/AC4 "理解每条 Bug"）。Fix: 改写为可观察产物 + 字段完整性验收。

**[对抗式 L3]** — 变更记录作者是"IT"非具体人（§1.3）。Fix: 指名负责人与审核人。

**[对抗式 L4]** — 拉取脚本缺限流/分页失败处理（Story 2.2 AC1）。Fix: 补限流退避、失败重试、断点续传/失败清单。

## Mechanical notes

- 词表：无独立 Glossary，§5 数据模型代偿，字段名跨节一致；后续喂架构建议单提状态枚举/服务名小词表。
- ID 连续性：FR1–12、NFR1–8、Epic 1–4、Story 1.1–4.3 连续无缺号/重号，跨节引用可解析。
- 假设索引：无 [ASSUMPTION]/[NOTE FOR PM]；"按实际调整"实为未确认假设，建议加标 + 文末轻量索引。
- UJ 主角：Story 无具名主角，对本工具形态属正确取舍。
- 必备章节：缺 Success Metrics 与 Non-Goals（已计入对应维度），其余齐备。
- 字段白名单一致性：C 组/D 组写回范围需统一定义（见 Downstream usability high + 对抗式 C2）。

## Reviewer files

- `review-rubric.md`
- `review-adversarial-general.md`
