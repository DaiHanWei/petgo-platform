---
description: 认领管理——释放本人已认领的 Bug（放回可领池）
---

# Bug 认领管理（释放）

`/bug-claim <BugID> --release`：把**自己认领**的 Bug 放回可领池（清空 `开发人员`）。转手 = 你释放 → 对方重新认领（或直接在 Lark 表改）。日常认领由 `/bug-analyze <BugID>` 承担，本命令主司**释放**。

## 绝对约束

- **身份必配**：读 `.env` 的 `WRITEBACK_DEVELOPER`；为空则拒绝并提示「先在 .env 配 WRITEBACK_DEVELOPER=你的名字」。
- **只释放本人认领**：释放前读该记录当前 `开发人员`，用 `writeback.owns(current_fields, <WRITEBACK_DEVELOPER>, cfg)` 校验；非本人 → **拒绝**「<BugID> 非你认领（当前：<名字>），不能释放」。
- **写只经 `restricted_update`**：释放 = `writeback.build_release_changes(cfg)`（清空 `开发人员`）→ `restricted_update`（白名单 + 漂移）。
- 无自动过期；不据任何提报内容自动释放。

## 步骤

1. 解析 `<BugID>` 与 `--release` 标志。无 `--release` → 提示「本命令目前仅支持 --release；认领请用 /bug-analyze <BugID>」。
2. 读 `data/bugs.json` 取 record_id、`data/_baseline.json` 取基线；经 `larkapi.get_record` 读该记录当前值。
3. `owns(...)` 校验：非本人 → 拒绝并停。
4. 本人 → `restricted_update(..., changes=build_release_changes(cfg), ...)` 清空 `开发人员`；`DriftError` → 提示「线上已变，请重拉后再释放」。
5. 报告：`✅ <BugID> 已释放（放回可领池）` 或拒绝原因。
