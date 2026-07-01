"""按选中 BugID 下载其截图/视频（深挖阶段用）。复用 pull_bugs 的下载逻辑，只针对子集。

用法：python3 fetch_media.py 20260630-155 20260630-157
读 data/bugs.json（须先 /bug-pull），按 bug_id 过滤记录 → 下媒体到 data/attachments/。
"""
import json
import sys
from pathlib import Path

from larkapi import LarkClient, LarkError
from pull_bugs import load_config, load_env, check_credentials, download_screenshots


def run(bug_ids, config_path="config.toml", env_path=".env", out_dir="data"):
    cfg = load_config(config_path)
    env = load_env(env_path)
    missing = check_credentials(env)
    if missing:
        sys.stderr.write("❌ 凭证缺失：" + ", ".join(missing) + "\n")
        return 2

    out = Path(out_dir)
    bugs_json = out / "bugs.json"
    if not bugs_json.exists():
        sys.stderr.write("❌ 未找到 data/bugs.json，请先运行 /bug-pull\n")
        return 1
    records = json.loads(bugs_json.read_text())

    wanted = set(bug_ids)
    selected = [r for r in records if r.get("bug_id") in wanted]
    found = {r.get("bug_id") for r in selected}
    for bid in wanted - found:
        sys.stderr.write(f"⚠️ {bid} 不在 bugs.json（可能已关闭或需重拉）\n")
    if not selected:
        sys.stderr.write("❌ 选中的 BugID 均不在本地，未下载\n")
        return 1

    client = LarkClient(env["APP_ID"], env["APP_SECRET"], region=cfg.get("region", "cn"))
    try:
        token = client.get_token()
    except LarkError as exc:
        sys.stderr.write(f"❌ 取 token 失败：{exc}\n")
        return 1

    failures = download_screenshots(
        client, selected, token, out,
        cfg.get("screenshots_field", "Screenshots"), env["TABLE_ID"])
    if failures:
        (out / "_failures.json").write_text(
            json.dumps(failures, ensure_ascii=False, indent=2))
        print(f"⚠️ {len(selected)} 条媒体下载：部分失败 {len(failures)} 项（见 _failures.json）")
        return 3
    print(f"✅ {len(selected)} 条媒体已下载 → {out}/attachments/")
    return 0


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if not argv:
        sys.stderr.write("用法：python3 fetch_media.py <BugID> [<BugID> ...]\n")
        return 2
    return run(argv)


if __name__ == "__main__":
    sys.exit(main())
