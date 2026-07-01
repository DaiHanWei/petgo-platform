#!/usr/bin/env python3
"""留存清理脚本（NFR9）。

删除本地 data/ 拉取产物（含真实用户/宠物 PII 截图），用完即删、不二次外传。
默认需 --yes 确认，避免误删。
"""

import argparse
import shutil
import sys
from pathlib import Path


def clean(data_dir="data", confirmed=False):
    path = Path(data_dir)
    if not path.exists():
        print(f"无可清理：{data_dir} 不存在")
        return 0
    if not confirmed:
        sys.stderr.write(
            f"将删除 {data_dir} 下全部拉取产物（含 PII 截图）。\n"
            f"确认请加 --yes：python3 clean_data.py --yes\n")
        return 1
    shutil.rmtree(path)
    print(f"✅ 已清空 {data_dir}")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="清空本地 Bug 拉取产物（NFR9 留存清理）。")
    ap.add_argument("--data-dir", default="data", help="产物目录（默认 data）")
    ap.add_argument("--yes", action="store_true", help="确认删除（不加则只提示不删）")
    args = ap.parse_args(argv)
    return clean(data_dir=args.data_dir, confirmed=args.yes)


if __name__ == "__main__":
    sys.exit(main())
