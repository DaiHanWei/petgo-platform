# V1.3.4 ktp 主题 · 发版检查清单

> 分支 `feat/1.3.4-ktp-revamp`（story 全部 review，L1/L2 待本地验收）。发版前逐条打勾，任何一条没过不发。
> 来源：各 story Completion Notes、决策日志 `_bmad-output/planning-artifacts/v1.3.4/决策日志-ktp.md`（D-3 / D-20）。

## 🔴 发版必改

- [ ] **`new` 角标截止日期**（D-3 / AD-11）：`petgo_app/lib/features/profile/domain/id_card_style.dart` 的
      `kIdCardNewStylesBadgeUntil` 当前是开发期占位 **WIB 2027-01-01 00:00**（`DateTime.utc(2026, 12, 31, 17)`）。
      改为「**上线日 + 30 天**」那天的 WIB 00:00（以 UTC 写：WIB 某日 00:00 = 前一日 UTC 17:00）。
      到期后所有用户的 5 款新卡 `new` 角标自动消失，无后台开关。

## 发布顺序

- [ ] **先发后端、再发 App**（D-20）：新版 App 建卡调用 `POST /api/v1/pet-profiles/me/id-cards/v2`，后端没上线时新 App 建卡会 404。
      旧接口 `POST /api/v1/pet-profiles/me/id-cards` 保留给 1.3.3 及更早 App，不要删。
- [ ] 后端打包用 `mvn -B clean package`（CLAUDE.md：不 clean 会把旧迁移文件一起打进 jar）。

## 数据库迁移（3 支，Flyway 自动执行）

- [ ] `V20261013_0411__id_cards_card_variant_and_checks.sql`：`card_variant` + 存量学生卡回填 KLASIK + 3 条 CHECK。
- [ ] `V20261013_0643__id_cards_hd_unlocked_at.sql`：`hd_unlocked_at` + 按 D-8 回填存量。
- [ ] `V20261013_0649__id_card_number_owners.sql`：编号归属表 + 回填 + **删除 `card_no` / `passport_no` 全局唯一**。
- [ ] 上线前在 `petgo_stag` 上先跑通三支迁移，把各迁移注释里的自检 SQL 结果补进对应 story（1-1 / 2-1 / 2-2）。
- ⚠️ 0649 上线后**不要回滚到旧 jar 继续建卡**（旧代码不登记编号归属，且已无全局唯一兜底）。操作者已确认基本不回滚。

## 验收（L1 / L2，发版前在本地 / stag 完成）

- [ ] 后端集成测试（连本分支库）：`IdCardEndpointIntegrationTest`、`IdCardHdIntegrationTest`、`AdminUserPurchasesIntegrationTest`、
      `CardNumberOwnershipIntegrationTest`、`IdCardNumberReuseIntegrationTest`、`PetPassportIntegrationTest`。
- [ ] `ktp_unlock_succeeded` 在 PostHog 带 `card_type` / `card_variant`（PawCoin 解锁 SIM、QRIS 解锁学生卡 Malam 各一次）。
- [ ] 旧版 App（1.3.3）建 KTP 不填品种仍能成功（走旧接口）；新版 App 缺品种时按钮置灰。
- [ ] 8 款卡面对照参考图校准（当前坐标为 UI 稿换算起点；Bintang 生日行压在底图圆章上）。
- [ ] 网格缩略图、Kartuku（空 / 失败 / 有卡）、Buka HD 标签付费、建卡表单与物种 / 品种下拉、建卡后付费落点（关闭 / 成功 / 失败都进新卡详情、成功自动弹保存分享）、id / en 两语文案。
