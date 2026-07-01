---
description: 联网拉取 Lark Bug 表全部记录与截图到本地（跑 pull_bugs.py，写 data/）
---

# Bug 拉取（联网 · 写本地产物）

运行 `scripts/bug-system/pull_bugs.py`，从 Lark Bug 表把全部记录与截图**只读**拉到本地 `scripts/bug-system/data/`，供 `/bug-analyze` 后续对照代码分析。

## 步骤

1. 从仓库根执行（默认配置/凭证/输出都在 `scripts/bug-system/` 下，故显式传路径）：

   ```bash
   python3 scripts/bug-system/pull_bugs.py \
     --config scripts/bug-system/config.toml \
     --env scripts/bug-system/.env \
     --out scripts/bug-system/data
   ```

   若用户在本命令后带了参数（如 `--config xxx`），以用户参数为准。

2. **按退出码汇报**，不要谎报成功：
   - `0` = 成功：说明拉到多少条、截图存到 `data/attachments/`，提示可跑 `/bug-analyze`。
   - `1` = 拉取失败：贴出脚本报错（网络/接口/token），不要吞。
   - `2` = 配置/凭证错误：多半 `.env`/`config.toml` 没填全或路径不对，提示检查（**只报缺哪个 KEY 名，绝不打印 secret 值**）。
   - `3` = 部分截图下载失败：记录本身拉到了，但有截图没下全；看 `data/_failures.json` 列出的失败项，如实说明。

3. 顺带看一眼产物：`data/bugs.json` 是否生成、`data/_needs_supplement.json`（信息不足清单）有没有内容，简述本批规模。

## 约束

- 本命令**只读拉取**，由只读应用凭证技术保证——不写回、不改线上任何字段。
- **绝不把 `.env` / `config.toml` 里的凭证值打印到对话**；报错只提 KEY 名。
- 拉下来的 `data/` 是 gitignored 的本地产物，不要 `git add`。
