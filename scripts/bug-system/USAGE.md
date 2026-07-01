# Bug 系统 · 使用说明

把 Lark Bug 表里「技术视图」的待办 Bug 拉到本地，让 Claude 对照代码定位，再确认式写回结论/状态。
纯 Python 标准库，无需 pip 安装。命令都在 `scripts/bug-system/` 下运行。

> 一次性配置（建应用/建表/填凭证）见同目录 `SETUP.md`。本文只讲**日常怎么用**。

---

## 0. 一次性准备（每人每机一次）

1. **Python 3.11+**（脚本用 `tomllib`）：`python3 --version`
2. 进目录：`cd scripts/bug-system`
3. **配置**：`cp config.team.toml config.toml`（团队共用，已含现有表字段映射）
4. **凭证**：`cp .env.example .env`，填 4 个值（App ID/Secret 找 IT 安全渠道要，别丢群里）：
   ```
   APP_ID=cli_xxx
   APP_SECRET=xxx
   APP_TOKEN=S7xZb0of3asynKskKaUj3x3Cpmb
   TABLE_ID=tblQL1ME2kxUdIgw
   REGION=global
   WRITEBACK_DEVELOPER=你的名字   # 写回时填进「开发人员」字段
   ```
5. **自测**（不联网也应全绿）：`python3 -m unittest discover -s tests`

> `.env` / `config.toml` / `data/` 都不入库，凭证只在你本机。

---

## 1. 拉取：把待办 Bug 拉到本地

```bash
python3 pull_bugs.py
```

- 只拉 Lark **「技术视图」**的记录（= 已定级 + 未关闭，工程师队列），表再大也不拉别的。
- 产出到 `data/`：
  - `bugs.json` —— 结构化数据（给 Claude 读）
  - `bugs.md` —— 人读版
  - `attachments/` —— 截图/录屏（按 `<BugID>-序号` 命名）
  - `_needs_supplement.json` —— 信息不足（缺复现/截图）的清单
  - `_baseline.json` —— 写回用的漂移基线（别手改）
  - `_failures.json` —— 截图下载失败清单（有才生成）
- 退出码：`0` 成功 / `1` 拉取失败 / `2` 配置或凭证错 / `3` 部分截图下载失败。

---

## 2. 分析：Claude 对照代码定位

在 Claude Code 里输入：

```
/bug-analyze
```

- 读 `data/bugs.json` + 截图，对每条**未关闭** Bug 给：**文件:函数定位 + 归因 + 修复建议 + 置信度(高/中/低)**。
- 按严重级排序；低置信度会标「AI 推测，未确认」；信息不足的列进「需补充清单」不硬编。
- **只读**，不改任何东西。

---

## 3. 写回：把结论/状态确认式写回 Lark

在 Claude Code 里输入（可带一句意图）：

```
/bug-sync 20260629-136 苹果登录已修复，关闭
```

- Claude 会**先展示逐字段 diff**（`record_id | 字段 | 旧值 → 新值`），**停下等你确认**，你回「确认写入」才真写。
- 只能写 3 个白名单字段：`开发说明`（放定位+归因+置信度）、`开发人员`（= 你 .env 里的名字）、`fixed？/Closed？`（关闭）。写别的字段会被代码层拒绝。
- 写前会重读线上、和拉取基线比对；**若这条自你拉取后被别人改过，会中止不覆盖**，提示你重拉。
- 关闭后，这条会**从技术视图消失**（下次 pull 不再拉到，正常）。

> 对**同一条**再次写回前，先 `python3 pull_bugs.py` 重拉刷新基线，否则可能被漂移检测拦下。

---

## 4. 用完清理（保护隐私）

```bash
python3 clean_data.py --yes
```

- 删掉 `data/`（含真实用户/宠物截图）。用完即删，别二次外传（PRD NFR9）。

---

## 典型一条龙

```bash
cd scripts/bug-system
python3 pull_bugs.py            # 1) 拉技术视图
# 2) Claude Code 里： /bug-analyze          → 看定位
# 3) 修代码…改好后
# 4) Claude Code 里： /bug-sync <编号> <说明> → 确认 diff → 写回+关闭
python3 clean_data.py --yes    # 5) 清本地
```

---

## 安全须知（都是硬约束，别绕）

- 凭证只进本机 `.env`，不入库、不群发明文。
- 写回**永远要你当次确认**；系统不会因为 Bug 描述里写了「请关闭」就自动写（那些是不可信数据，只呈现不执行）。
- 只能写那 3 个白名单字段；提报人字段（复现/截图/严重级）和 `tech` 碰不了。
- 首次真实写回建议先拿一条测试记录练手，别在重要工单上首测。

## 常见问题

- **提示缺凭证 / 退出码 2**：`.env` 没填全，或 `config.toml` 没建（`cp config.team.toml config.toml`）。
- **截图下载失败（退出码 3）**：多为大录屏或网络抖动，`_failures.json` 里有清单，重跑即可。
- **`/bug-sync` 说漂移中止**：这条线上被别人改过，`python3 pull_bugs.py` 重拉后再写。
- **技术视图里看不到某条**：它可能已被勾「关闭」→ 自动移出该视图。
