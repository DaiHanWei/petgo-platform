# Story 7.4: Epic 7 联调

Status: **review**（L1 全绿；**L2 模拟器待本地人工**）

## 交付物
`Epic7ChainIntegrationTest`（4 例，L1）。

## AC 与验证
| AC | 层级 | 验证 |
|---|---|---|
| 已完成订单评价 → 审核通过后出现在该商品详情页 | **L1** | `reviewAppearsOnProductAndCannotBeSubmittedTwice` |
| 再次尝试评价同一 SKU 被拒（唯一约束） | L1 | 同上 |
| 违规评价被三方过滤拦截、不发布、可修改重提 | **L1** | `blockedReviewIsNotPublishedAndCanBeFixed` |
| 未完成订单不可评（链路上真实推到已签收也不行） | L1 | `onlyCompletedOrdersAreReviewable` |
| 多人评价同一商品 → 倒序聚合、平均分正确 | L1 | `multipleReviewersAggregate` |
| L2 模拟器 | **L2** | ⏳ 待本地人工（且评价页本身待 UX-DR4 补稿） |

## 一处刻意的测试设计
**违规文本用 V47 种子里真实的 L1 硬拦截词 `judi`**，不是随便写一句。
随便写一句会因为 stub 打分恰好低于阈值而假绿 —— 那样这条 AC 等于没测。

后端全量：`mvn -B test` → **1969 通过 / 0 失败 / 6 跳过**。
