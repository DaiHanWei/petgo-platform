---
stepsCompleted: [1, 2, 3, 4, 5, 6]
documentsIncluded:
  prd: _bmad-output/planning-artifacts/analytics-pipeline/prd.md
  architecture: _bmad-output/planning-artifacts/analytics-pipeline/architecture.md
  epics: _bmad-output/planning-artifacts/analytics-pipeline/epics.md
  ux: 无（纯数据管道，没有界面）
---

# 实施就绪度评估报告

**日期：** 2026-11-12
**项目：** petgo-platform · 自建埋点（analytics-pipeline，不挂版本；App 侧随 V1.5.0 切换）

## 文档清单

| 类型 | 文件 | 大小 | 修改时间 |
|---|---|---|---|
| PRD | prd.md（final） | 31 KB | 11-12 16:54 |
| 架构 | architecture.md（complete） | 29 KB | 11-12 17:49 |
| Epics | epics.md（评估时 4 epic / 25 story，修订后 28 story） | 44 KB | 11-12 18:00 |
| UX | 无 | — | — |

参考（不作为评估对象）：decisions.md、department-data-summary(.zh).md、.decision-log.md、review-*.md、reconcile-*.md。

无重复文档，无分片版本。

## 评估方式

三份文档由同一作者写成，为避免自我审查盲区，PRD 分析、覆盖校验、story 质量审查交给一个未参与写作的独立 agent 完成，完整报告见 [review-readiness-independent.md](review-readiness-independent.md)（含 FR / NFR 覆盖矩阵、三份文档矛盾对照、代码引用核实表）。作者逐条核实（订单状态、CI 路径过滤、`AnalyticsClient` 签名、`internal_user_id` 等均对照代码确认），全部属实。

## PRD 分析

- 27 条 FR、5 条 NFR，结构完整，每条 FR 都有可验证结果。
- 发现 PRD 层面的口径问题 4 处（日活对账不同口径、登出后归属、已付款判据、兽医是否排除），已由 Dai 拍板并回写 PRD。

## Epic 覆盖校验

- 评审前：27 条 FR 编号全部映射到 epic，但按「可验证结果」逐条核对，有部分覆盖缺口：FR-14（登录前事件归属）、FR-21（周期去重长期可答）、FR-17 第 3 条（运营位是否推广 Toko）、FR-23（发送记录取错表）、FR-6（stag 隔离无验收）。
- 修订后：以上缺口均落到具体 story 的验收标准（1.5、4.1、4.4、4.5、2.5 等）。覆盖率 100%。

## UX 对齐

无 UX 文档。纯数据管道，App 侧只替换埋点发送端、不改界面，不需要 UX。

## Epic 质量审查

评审前问题（详见独立报告）：

| 级别 | 数量 | 代表问题 |
|---|---|---|
| 🔴 关键 | 3 | 登录前匿名事件归不到人；周期去重指标从日表算不出；明细层 DEFAULT 分区会让分区维护失败 |
| 🟠 重要 | 20 | 架构安装判定与 PRD 相反；业务库无 `PAID`；第二数据源接线三个坑；服务端事件只带哈希；AI 账号越权读原始层；对账口径不可比；Epic 2 往后依赖；清单严格拒收永久丢数；进度竞态；关联表丢历史；IP 限流误伤；队列内存上限；擦除超时；擦除漏属性；压测打共用实例；比对测试查不到调用点；购物车汇总含个人；推送发送数取错表；story 过大；token / id 关联未定义 |
| 🟡 次要 | 19 | 决策日志未记的调整、命名清单不全、H5 来源无落点、兽医被排除、Android null 判定、分区时区、可疑容差、新人券 id、冷启动时序、服务端事件丢弃策略、不可测 AC、联调需非 debug 包、stag 角色共享等 |

## 处理结果

| 处理 | 内容 |
|---|---|
| Dai 拍板（4 条） | 日活对账改同口径；登出后事件归上一个账号；退款 / 取消订单仍算已付款；兽医排除 |
| 修进架构 | D-2、D-3、D-4、D-7、D-8、D-10、D-11、A4、A5、A8、A10、Q5、接口、队列、启动隔离、生命周期事件、服务端事件、命名；新增 D-12、D-13；压测改到本地实例 |
| 修进 PRD | FR-25 对账口径、§4.5.1 与 FR-22 已付款判据、FR-21 周期去重入表 |
| 重写 epics | Epic 1 拆为 10 个 story（骨架与清单分开、明细转换与分区清理分开）；Epic 4 拆为 8 个（购物车与商品单列）；Epic 2 匿名 id 并入 2.1、新事件在 1.2 预登记，消除往后依赖；全部验收标准按上表修订 |
| 补记决策日志 | FR-8 处理方式变更、FR-16 提前到 Epic 1，以及本轮全部拍板与技术修订 |

修订后 story 共 28 个：Epic 1 × 10、Epic 2 × 6、Epic 3 × 4、Epic 4 × 8。

## 总结与建议

### 总体就绪状态

**可以进入实现（修订后）**。评审前为「修完再实现」；关键与重要问题已全部处理，次要问题已在对应 story 里落实或记为已知偏差。

### 仍需注意

1. 修订由原作者完成，未经独立 agent 二次复核。可选：对修订后的 epics.md 再跑一次轻量复审。
2. 1.2（事件清单盘点约 191 种事件）、1.6（明细层转换）仍是较大的 story，dev-story 时如上下文吃紧可再拆。
3. 需要真实环境的 L2 验收：1.4 本地 10 倍压测、1.9 stag 测试支付、2.5 TestFlight 真包、3.2 真机推送、4.8 AI 实测。

### 建议下一步

1. 提交本轮修订。
2. 建 sprint-status 与 story 文件（按项目惯例放 implementation-artifacts 下的主题目录）。
3. 按 Epic 1 → Epic 2 / 3 并行 → Epic 4 的顺序实现；Epic 1 先上 stag，再随 V1.5.0 前上生产。

### 结语

本次评估在 3 份文档中共发现 42 个问题（3 关键、20 重要、19 次要），4 条口径由 Dai 拍板，其余按推荐修订，三份文档已对齐。

评估人：独立审查 agent + 作者核实；日期 2026-11-12。
