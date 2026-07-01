#!/usr/bin/env python3
"""一次性建表脚本（Story 1.1 + 1.2）——独立于只读拉取代码。

用你的企业自建应用（须有 bitable 读写 scope）程序化创建结构化 Bug 多维表格：
建 App → 建 Bugs 表 → 逐个建 A/B/C/D 四组字段 → 建 5 个流转视图。
建完打印 app_token / table_id / 字段名→field_id 映射，并写 data/setup-output.json。

凭证从 .env 读（APP_ID/APP_SECRET），绝不写死/入库。可选 FOLDER_TOKEN 指定建到哪个云盘文件夹
（团队能访问的文件夹，否则表只有 App 自己可见）。

⚠️ 本脚本会对你的真实 Lark 租户执行【写】操作（建文档）。与只读拉取（pull_bugs.py）无关，
   不要把它的写能力混进 larkapi.py。
"""

import argparse
import json
import sys
import urllib.parse
from pathlib import Path

from larkapi import LarkClient, LarkError
from pull_bugs import load_env

# Lark Bitable 字段类型码
T_TEXT = 1
T_SINGLE = 3
T_MULTI = 4
T_PERSON = 11
T_ATTACH = 17
T_CREATED_TIME = 1001
T_MODIFIED_TIME = 1002
T_CREATED_USER = 1003
T_AUTO_NUMBER = 1005


def _opts(*names):
    return {"options": [{"name": n} for n in names]}


# 主字段（建表时即为索引字段，必须文本类）
PRIMARY_FIELD = {"field_name": "Title", "type": T_TEXT}

# 其余字段，按 A/B/C/D 组顺序。(name, type, property|None)
FIELDS = [
    # A 组
    ("Bug ID", T_AUTO_NUMBER, {"auto_serial": {"type": "auto_increment_number"}}),
    ("Created Time", T_CREATED_TIME, None),
    ("Reporter", T_CREATED_USER, None),
    # B 组
    ("Steps to Reproduce", T_TEXT, None),
    ("Actual Result", T_TEXT, None),
    ("Expected Result", T_TEXT, None),
    ("Screenshots", T_ATTACH, None),
    ("Recording", T_ATTACH, None),
    ("Reported On", T_MULTI, _opts("App", "Admin Backend")),
    ("Platform", T_MULTI, _opts("iOS", "Android")),
    ("Module", T_SINGLE, _opts("Home", "Growth", "Consult", "Me", "Other")),
    ("Severity", T_SINGLE, _opts("P0", "P1", "P2", "P3", "P4")),
    ("Bug Type", T_SINGLE, _opts("Functional", "UI", "Performance", "Crash", "Suggestion")),
    # C 组
    ("Status", T_SINGLE, _opts(
        "Open", "In Progress", "Fixed - Pending Verification", "Verified",
        "Reopened", "Won't Fix", "Duplicate", "Accepted", "Planned", "Declined")),
    ("Assignee", T_PERSON, {"multiple": False}),
    ("Affected Service", T_MULTI, _opts("App", "Backend", "Admin H5")),
    ("Root Cause / Code Location", T_TEXT, None),
    ("Dev Notes", T_TEXT, None),
    ("Fixed in Version", T_TEXT, None),
    ("Duplicate Of", T_TEXT, None),
    ("Confidence", T_SINGLE, _opts("高", "中", "低")),
    ("Updated by Engineer", T_MODIFIED_TIME, None),
    # D 组
    ("Artifact Type", T_SINGLE, _opts(
        "Android APK", "iOS TestFlight", "iOS Ad-hoc IPA", "Other")),
    ("Artifact Link", T_ATTACH, None),
    ("Build Version", T_TEXT, None),
    ("Verification Result", T_SINGLE, _opts("Pending", "Pass", "Fail")),
    ("Verification Notes", T_TEXT, None),
    ("Verifier", T_PERSON, {"multiple": False}),
]

# Story 1.2 五视图（名称 + 状态筛选；筛选经 API best-effort，失败则建好空视图待 UI 配）
VIEWS = [
    ("待处理", ["Open"]),
    ("处理中", ["In Progress"]),
    ("待验收", ["Fixed - Pending Verification"]),
    ("已关闭", ["Verified", "Won't Fix", "Duplicate", "Declined"]),
    ("全部", None),
]


def _post(client, token, path, body):
    url = f"{client.base}{path}"
    return client._json("POST", url, headers={"Authorization": f"Bearer {token}"}, data=body)


def create_app(client, token, name, folder_token):
    body = {"name": name}
    if folder_token:
        body["folder_token"] = folder_token
    data = _post(client, token, "/open-apis/bitable/v1/apps", body)["data"]
    return data["app"]["app_token"]


def create_table(client, token, app_token):
    body = {"table": {"name": "Bugs", "default_view_name": "全部",
                      "fields": [PRIMARY_FIELD]}}
    data = _post(client, token,
                 f"/open-apis/bitable/v1/apps/{app_token}/tables", body)["data"]
    return data["table_id"]


def add_field(client, token, app_token, table_id, name, ftype, prop):
    body = {"field_name": name, "type": ftype}
    if prop is not None:
        body["property"] = prop
    path = f"/open-apis/bitable/v1/apps/{app_token}/tables/{table_id}/fields"
    data = _post(client, token, path, body)["data"]
    return data["field"]["field_id"]


def create_view(client, token, app_token, table_id, name):
    path = f"/open-apis/bitable/v1/apps/{app_token}/tables/{table_id}/views"
    body = {"view_name": name, "view_type": "grid"}
    data = _post(client, token, path, body)["data"]
    return data["view"]["view_id"]


def run(env_path=".env", out_dir="data", table_name="Bug 管理表", dry_run=False):
    env = load_env(env_path)
    folder_token = env.get("FOLDER_TOKEN", "")
    region = env.get("REGION", "cn")

    if dry_run:
        print(f"[dry-run] 将建表「{table_name}」"
              f"（region={region}, folder={'有' if folder_token else '无'}）"
              f"，字段 {len(FIELDS) + 1} 个，视图 {len(VIEWS)} 个。")
        for name, ftype, _ in [("Title", T_TEXT, None)] + FIELDS:
            print(f"  - {name} (type {ftype})")
        return 0

    missing = [k for k in ("APP_ID", "APP_SECRET") if not env.get(k)]
    if missing:
        sys.stderr.write("❌ 缺凭证：" + ", ".join(missing) + "（填进 .env，值不打印）\n")
        return 2

    client = LarkClient(env["APP_ID"], env["APP_SECRET"], region=region)
    try:
        token = client.get_token()
        app_token = create_app(client, token, table_name, folder_token)
        print(f"✅ 建 App: {app_token}")
        table_id = create_table(client, token, app_token)
        print(f"✅ 建表 Bugs: {table_id}")
    except LarkError as exc:
        sys.stderr.write(f"❌ 建 App/表失败：{exc}\n")
        return 1

    field_ids = {"Title": "(primary)"}
    field_failures = []
    for name, ftype, prop in FIELDS:
        try:
            fid = add_field(client, token, app_token, table_id, name, ftype, prop)
            field_ids[name] = fid
            print(f"  ✓ 字段 {name} -> {fid}")
        except LarkError as exc:
            field_failures.append({"field": name, "type": ftype, "error": str(exc)})
            print(f"  ✗ 字段 {name} 失败：{exc}")

    view_ids = {}
    view_failures = []
    for vname, _filter in VIEWS:
        try:
            vid = create_view(client, token, app_token, table_id, vname)
            view_ids[vname] = vid
            print(f"  ✓ 视图 {vname} -> {vid}")
        except LarkError as exc:
            view_failures.append({"view": vname, "error": str(exc)})
            print(f"  ✗ 视图 {vname} 失败：{exc}")

    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    result = {
        "app_token": app_token,
        "table_id": table_id,
        "field_ids": field_ids,
        "view_ids": view_ids,
        "field_failures": field_failures,
        "view_failures": view_failures,
    }
    (out / "setup-output.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"\n══ 建表完成 ══")
    print(f"APP_TOKEN={app_token}")
    print(f"TABLE_ID={table_id}")
    print(f"字段成功 {len(field_ids) - 1 + 1}/{len(FIELDS) + 1}，失败 {len(field_failures)}；"
          f"视图成功 {len(view_ids)}/{len(VIEWS)}，失败 {len(view_failures)}")
    print(f"详情写入 {out}/setup-output.json")
    if field_failures:
        print("⚠️ 有字段建失败，详见 setup-output.json（可补建或 UI 手动加）")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="一次性建结构化 Bug 多维表格（写操作，用你的 App 凭证）。")
    ap.add_argument("--env", default=".env", help=".env 路径（默认 .env）")
    ap.add_argument("--out", default="data", help="输出目录（默认 data）")
    ap.add_argument("--name", default="Bug 管理表", help="多维表格名")
    ap.add_argument("--dry-run", action="store_true", help="只打印将建什么，不真建")
    args = ap.parse_args(argv)
    return run(env_path=args.env, out_dir=args.out, table_name=args.name, dry_run=args.dry_run)


if __name__ == "__main__":
    sys.exit(main())
