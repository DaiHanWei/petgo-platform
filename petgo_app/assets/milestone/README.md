# 里程碑徽章素材（V1.3.2 Story 5.1）

- 命名：`<语义键>.webp`，语义键见 `lib/features/profile/domain/milestone_badge_assets.dart` 的 `kMilestoneBadgeKeys`（40 个）+ 全局锁定图 `locked.webp`。
- 规格：透明底，建议 512×512；同一张图在 14px 角标到 120px 大徽章之间等比缩放（不做大小两套）。
- 素材未入库（D-21）：目录里只有本 README。到货后同名放入即生效，**不改代码**；缺失时各处回落原外观。
- 测试 `test/profile/milestone_badge_test.dart` 会拒绝本目录下名字不在映射表里的 `.webp`（防拼错的孤儿文件）。
