# V1.4.0 精选自营电商（Epic 1–9，57 条 story）

> **体量：** 55 commits · 445 files · +57,433 / −123 · **24 个 Flyway 迁移（V101–V125）**
> **状态：** 55 条 review + 2 条按纪律主动留白 · 后端 1995 通过 / 0 失败 / 6 跳过 · 前端 analyze 零问题 + 953 通过

---

## ⚠️ 先说怎么评审这个 PR

**不要试图逐行读完 57k 行。** 建议按风险分层看，下面按「最该被人盯」的顺序排：

### 第 1 层 · 安全攸关（4 处，务必人看）

| 位置 | 看什么 |
|---|---|
| `shop/returns/domain/RefundSplit.java` | **AD-2 整数累计法**：按累计额取整、`thisCash` 由相减得出、`coin_ratio` 不参与任何金额计算。有「多次部分退款后全额退款精确归零」的测试 |
| `shared/boundary/` + 两端 `ConsultShopBoundaryTest` | **FR-110 能力缺席**：问诊侧不 import shop、消息体过滤商品链接、结论页只允许品类跳转。已入 CI（两个 workflow 各一步命名 Guard） |
| `AdminPermissions`（49 → 53）+ `AdminShopFinanceController` | **NFR-11**：进货价与毛利是**两个**权限位；三张财务页都要 `shop.finance_view`，不默认授予既有运营角色 |
| `CheckoutService` / `ShopOrderPaymentService` | **FR-100A**：PawCoin 段只退 PawCoin（服务层**无对应方法**，不是有方法加权限判断） |

### 第 2 层 · 共享物（三线并行的撞车面）

- **24 个迁移**：全部在本工作线独占号段 **V101–V125** 内
- ⚠️ **触碰共享 CHECK `ck_notifications_type` 4 次**（V116/V117/V120/V122）—— 均为**追加值**，依临时授权
- 🔴 **V121 触碰共享表 `pet_profiles`**（加 `weight_kg` / `neuter_status`，只加列 / 全可空 / 不回填）——
  **不在临时授权范围内，认领待补**（见 PR 检查项）
- `AdminPermissions` / admin `layout.html` / 三份 i18n：**只追加不重排**
- `OrderCenterService`（三线共享）：四个 `if` 分支与四个映射器**一行未改**，只改了取数那一句与归并/游标段

### 第 3 层 · 其余按 Epic 读
每个 Epic 的实现说明在 `_bmad-output/implementation-artifacts/v1.4.0/<epic>-<n>-*.md`，
每份都写了 AC → 验证方式的对照表和「为什么这么做」。

---

## 做了什么

| Epic | 内容 |
|---|---|
| 1 商品上架与浏览 | 8/8 · 商品/SKU/库存建模 + 后台录入 + Toko 浏览 |
| 2 收货地址与配送范围 | 5/5 · 地址簿（首次引入 PII 数据集）+ Kecamatan 粒度运费表 |
| 3 完成一次购买 | 10/10 · 加购 → 两段金额结算 → PawCoin/QRIS/混合支付 → 订单 |
| 4 履约物流与收货 | 6/6 · SPEC-2 三条出口（后台标记 / 用户在已发货态确认 / 7 日自动） |
| 5 退货与退款 | 9/10 · 行级部分退货 + AD-2 两段拆分退款 + 质检入库 |
| 6 复购引擎（版本核心） | 7/7 · 档案推荐 + 粮量见底预估 + AB-13B 效果看板 |
| 7 商品评价 | 3/4 · 同步审核、**不走先发布后审核** |
| 8 经营数据与对账 | 4/4 · 毛利 / 周转 / 对账（PawCoin 段与现金段拆分） |
| 9 边界守护与效果度量 | 3/3 · FR-110 入 CI + 归因链闭合 + 全量埋点核对 |

**主动留白 2 条**（不是遗漏）：`5.9 退货进度页` ← UX-DR5 · `7.2 评价页` ← UX-DR4。
无视觉稿不自行发挥；后端接口都已就位，出稿即可开工。

---

## 顺带修掉的既有缺陷（不属于本版本范围，但都会静默丢数据）

### 同一族的三处分页缺陷
游标只有 `created_at`、还截断到毫秒、查询用严格 `<` → **同刻记录在分页边界被整批跳过，用户永久看不到**。
一毫秒内写多行在生产上完全正常（批量触达、一次结算写多条流水）。

| action_item | 端点 | 备注 |
|---|---|---|
| `NOTIFY-CURSOR-TIE` | `/api/v1/notifications` | 发现于全量回归偶发红 —— **不是抖动** |
| `ORDER-CENTER-CURSOR-TIE` | `/api/v1/orders` | 跨 4 源归并，全序改 `(createdAt, sourceRank, id)` |
| `PAWCOIN-LEDGER-CURSOR-TIE` | `/api/v1/me/pawcoin` | **同族最容易撞上的一处** —— 这是钱的账 |

🔴 **三处 `nextCursor` 的 wire 格式都变了**（→ base64url 复合键）。对客户端都是**不透明串**，
Flutter 侧只原样回传（已逐个核对）；服务端各留一轮过渡兼容。
新增共享件 `shared/paging/KeysetCursor`，**以后写分页直接用它**。

### 三处埋点漏洞（NFR-5）
- `Analytics.scrub` **不递归 List** → 行级归因的 `items[]` 整块透传，三道规则全不生效
- PII 黑名单是**精确相等** → `receiver_name` / `receiver_phone` / `address_line` 一个都不命中，
  而这三个正是 Epic 2 引入、NFR-5 点名新增的禁记项
- 检查**自身**的盲点：属性提取正则以第一个 `)` 收工，嵌套 payload 整块漏检

### Story 2.2 的缺口
原 story 只出了服务层、**没做 AB-11C 后台页** —— 意味着运营无法配置配送范围，
**不配就一单都发不出去**。已补 `shop-shipping.html`，并回写进 2.2 的 story 文档。

---

## 测试

```
后端  mvn -B test        1995 通过 / 0 失败 / 6 跳过   BUILD SUCCESS
前端  flutter analyze    零问题
      flutter test       953 通过
```

**变异验证 20 次，全部先红后绿再还原**（护栏写完必须把被守护的东西弄坏一次，
确认它真的会红 —— 本工作线出过三次假绿，都是「护栏的判定方式与它声称看守的对象对不上」）。
每条的清单在各 Epic 的 story 文档里。

---

## 合并前必须闭合（PR 检查项）

- [ ] 🔴 **V121 `pet_profiles` 共享表加列的认领签字**（不在临时授权内）
- [ ] 🔴 **HEX-SIGNOFF 发出并收齐**
- [ ] ⚠️ **S-12「赠币核销额（近似）」财务确认精度够用** —— 不够用要回头改钱包，是全套里最贵的返工
- [ ] ⚠️ **确认没有仓库外的脚本/后台在解析 `nextCursor`**（三处 wire 格式已变）
- [ ] ⚠️ **产品决策：健康记录页的品类跳转入口留不留**（见验收清单 B4；不留就删一个 widget）

## 合并后

按 `_bmad-output/implementation-artifacts/v1.4.0/人工验收清单.md` 走：
**合 stag → 部署 staging → 在 staging 上做 L2 → 生产 → 盯 C 节两个告警**。

🔴 **staging 部署时重点看那 24 个迁移的日志** —— 它们至今只在本地空库上顺序跑过，
staging 是从生产克隆的库，是它们第一次面对真实数据。
