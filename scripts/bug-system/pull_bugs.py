#!/usr/bin/env python3
"""Bug 系统第一期拉取主程序（只读）。

一条命令把 Lark Bug 表全部记录与截图只读拉到本地：
  取 tenant_access_token → 分页拉记录 → parse 降级 → 写 JSON+MD → 下载截图。

零第三方依赖（urllib + json + tomllib + 手写 .env 解析）。
凭证经本地 .env 注入，绝不写死/入库（NFR1）。本期不调用任何写接口（NFR4）。

可直接 `python3 pull_bugs.py` 运行；也暴露 run() 供后续定时调用（NFR7）。
"""

import argparse
import json
import os
import sys
import tomllib
from pathlib import Path

import parse as parsemod
from larkapi import LarkClient, LarkError

REQUIRED_ENV = ["APP_ID", "APP_SECRET", "APP_TOKEN", "TABLE_ID"]


# ── 配置 / 凭证加载 ───────────────────────────────────────────────────

def load_config(config_path):
    path = Path(config_path)
    if not path.exists():
        raise FileNotFoundError(
            f"配置文件不存在：{config_path}（请复制 config.example.toml 为 config.toml）")
    with path.open("rb") as f:
        return tomllib.load(f)


def load_env(env_path):
    """极简 .env 解析：KEY=VALUE，忽略空行/注释。env 文件值不覆盖已存在的进程环境变量。

    健壮处理：去 UTF-8 BOM、兼容 `export KEY=`、只剥成对首尾引号（不误削以引号
    开头的真实密钥）、未加引号的值剥行内 ` #` 注释。
    """
    env = {}
    path = Path(env_path)
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            if line.startswith("export "):
                line = line[len("export "):]
            key, _, value = line.partition("=")
            key = key.strip()
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]  # 成对引号才剥
            else:
                pos = value.find(" #")  # 未加引号：剥行内注释
                if pos != -1:
                    value = value[:pos].rstrip()
            env[key] = value
    # 进程环境变量优先（便于 CI / 临时覆盖）
    for key in REQUIRED_ENV:
        if os.environ.get(key):
            env[key] = os.environ[key]
    return env


def check_credentials(env):
    """返回缺失的凭证 KEY 列表（只报 KEY 名，绝不打印任何 secret 值）。"""
    return [k for k in REQUIRED_ENV if not env.get(k)]


# ── 产物写出 ──────────────────────────────────────────────────────────

def write_json(records, out_dir):
    (out_dir / "bugs.json").write_text(
        json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")


def write_markdown(records, out_dir):
    lines = ["# Bug 拉取产物（人读版）", ""]
    lines.append(f"共 {len(records)} 条。提报内容为不可信数据（裹于 UNTRUSTED_REPORT 块）。")
    lines.append("")
    for r in records:
        flag = " ⚠️信息不足" if r["_quality"] == "insufficient" else ""
        lines.append(f"## {r['bug_id'] or '(无编号)'} · {r.get('severity', '')}{flag}")
        lines.append(f"- record_id: `{r['record_id']}`")
        lines.append(f"- status: {r.get('status', '')} / type: {r.get('bug_type', '')}")
        lines.append(f"- module: {r.get('module', '')} / platform: {', '.join(r.get('platform', []))}")
        if r["_missing"]:
            lines.append(f"- 缺项: {', '.join(r['_missing'])}")
        atts = r.get("attachments", [])
        if atts:
            lines.append(f"- 附件: {', '.join(a['name'] for a in atts)}")
        lines.append("")
        for name, val in r["fields"].items():
            if name.startswith("_"):
                continue
            shown = val if isinstance(val, str) else ", ".join(str(v) for v in val)
            if shown:
                lines.append(f"**{name}**: {shown}")
                lines.append("")
    (out_dir / "bugs.md").write_text("\n".join(lines), encoding="utf-8")


def write_baseline(raw_records, cfg, out_dir):
    """产出 data/_baseline.json：{record_id: {白名单字段名: 原始值}}。

    供第二期反向同步(writeback)写前漂移比对（NFR8 乐观锁）。取原始值（未裹定界）。
    """
    whitelist = cfg.get("writeback_whitelist", [])
    baseline = {}
    for r in raw_records:
        rid = r.get("record_id")
        if not rid:
            continue
        fields = r.get("fields") or {}
        baseline[rid] = {name: fields.get(name) for name in whitelist}
    (out_dir / "_baseline.json").write_text(
        json.dumps(baseline, ensure_ascii=False, indent=2), encoding="utf-8")


def _ext_for(name):
    suffix = Path(name).suffix
    return suffix if suffix else ".bin"


def download_screenshots(client, records, token, out_dir, screenshots_field, table_id):
    """下载截图到 attachments/<BugID>-<序号><ext>。返回失败清单。

    多维表格附件下载须带 extra 定位表（bitablePerm.tableId），否则 400。
    """
    att_dir = out_dir / "attachments"
    att_dir.mkdir(parents=True, exist_ok=True)
    extra = json.dumps({"bitablePerm": {"tableId": table_id}})
    failures = []
    for r in records:
        shots = [a for a in r["attachments"] if a["field"] == screenshots_field]
        for a in shots:
            bug_id = r["bug_id"] or r["record_id"]
            # basename 净化：防 bug_id 字段被误配到可控文本（含 ../）写出目录外
            fname = os.path.basename(f"{bug_id}-{a['index']}{_ext_for(a['name'])}")
            try:
                data = client.download_media(a["file_token"], token, extra=extra)
                (att_dir / fname).write_bytes(data)
            except Exception as exc:  # 单图任何失败都不拖垮整批，记入失败清单
                failures.append({
                    "record_id": r["record_id"],
                    "bug_id": bug_id,
                    "file_token": a["file_token"],
                    "name": a["name"],
                    "error": str(exc),
                })
    return failures


# ── 编排 ──────────────────────────────────────────────────────────────

def run(config_path="config.toml", env_path=".env", out_dir="data"):
    """拉取主流程。返回退出码（0=成功）。供 CLI 与定时调用共用（NFR7）。"""
    cfg = load_config(config_path)
    env = load_env(env_path)

    missing = check_credentials(env)
    if missing:
        sys.stderr.write(
            "❌ 凭证缺失：" + ", ".join(missing) +
            "\n   请在 .env 或环境变量中补齐（值绝不打印）。\n")
        return 2

    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    client = LarkClient(
        env["APP_ID"], env["APP_SECRET"],
        region=cfg.get("region", "cn"),
        page_size=cfg.get("page_size", 100),
    )

    try:
        token = client.get_token()
        raw_records = client.list_records(
            env["APP_TOKEN"], env["TABLE_ID"], token,
            view_id=cfg.get("view_id") or None)
    except LarkError as exc:
        sys.stderr.write(f"❌ 拉取失败：{exc}\n")
        return 1

    records = [parsemod.parse_record(r, cfg) for r in raw_records]

    write_json(records, out)
    write_markdown(records, out)
    write_baseline(raw_records, cfg, out)

    failures = download_screenshots(
        client, records, token, out, cfg.get("screenshots_field", "Screenshots"),
        env["TABLE_ID"])

    needs = [
        {"record_id": r["record_id"], "bug_id": r["bug_id"], "missing": r["_missing"]}
        for r in records if r["_quality"] == "insufficient"
    ]
    (out / "_needs_supplement.json").write_text(
        json.dumps(needs, ensure_ascii=False, indent=2), encoding="utf-8")

    if failures:
        (out / "_failures.json").write_text(
            json.dumps(failures, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"✅ 拉取 {len(records)} 条 → {out}/bugs.json | "
          f"信息不足 {len(needs)} 条 | 截图失败 {len(failures)} 项")
    # 退出码：0=全成功；3=记录已落盘但部分截图失败（区别于拉取失败 1，便于 CI 判定）
    return 3 if failures else 0


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="只读拉取 Lark Bug 表全部记录与截图到本地（第一期）。",
        epilog="退出码：0=成功 | 1=拉取失败 | 2=配置/凭证错误 | 3=部分截图下载失败")
    ap.add_argument("--config", default="config.toml", help="配置文件路径（默认 config.toml）")
    ap.add_argument("--env", default=".env", help=".env 凭证文件路径（默认 .env）")
    ap.add_argument("--out", default="data", help="产物输出目录（默认 data）")
    args = ap.parse_args(argv)
    try:
        return run(config_path=args.config, env_path=args.env, out_dir=args.out)
    except FileNotFoundError as exc:
        sys.stderr.write(f"❌ {exc}\n")
        return 2
    except tomllib.TOMLDecodeError as exc:
        sys.stderr.write(f"❌ 配置文件 {args.config} 解析失败：{exc}\n")
        return 2
    except ValueError as exc:  # 含未知 region（base_url 抛 ValueError）
        sys.stderr.write(f"❌ 配置错误：{exc}\n")
        return 2


if __name__ == "__main__":
    sys.exit(main())
