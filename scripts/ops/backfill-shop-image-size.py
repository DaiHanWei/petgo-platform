#!/usr/bin/env python3
"""商品主图尺寸回填（存量数据）。

背景
----
`shop_products.main_image_w / main_image_h` 两列是 2026-08-27 加的（迁移 `V20260827_1400`），
而尺寸是**上传图片时**从字节里当场测出来的（`AdminSeedImageService.measure`）——
**迁移本身一个值都不写**。于是加列之前就已经在库里的商品，这两列恒为 NULL。

后果不是"少个字段"这么轻：App 的 Toko 列表是两列瀑布流，卡片高度 = 列宽 × (h/w)。
拿不到尺寸就无法在图片解码前用 AspectRatio 预置高度 ⇒ 那些商品仍会出现
「占位 1:1 → 真实比例」的高度突变。本脚本把它们补上，让存量商品也不跳。

做法
----
只下载图片**头部**解析宽高，不下整张。JPEG / PNG / WebP 的尺寸都写在文件开头，
通常读前 64KB 足够 —— 回填上千个商品的流量因此从 GB 级降到 MB 级。
（走 HTTP Range 请求；服务端不支持 Range 时回退为普通 GET，但仍在读满上限后主动断开。）

用法
----
    export DB_URL='postgresql://user:pass@host:5432/dbname'
    export OSS_CDN_BASE_URL='https://cdn.example.com'

    python3 scripts/ops/backfill-shop-image-size.py --dry-run    # 只看会改什么，不写库
    python3 scripts/ops/backfill-shop-image-size.py --limit 50   # 先试 50 条
    python3 scripts/ops/backfill-shop-image-size.py              # 全量回填

🔴 **只补 NULL，绝不覆盖已有值** —— 重跑安全（幂等）。已有值要么是上传时测的、
   要么是上一轮回填写的，都比重测更可信（图可能已被替换，而 key 没变）。
🔴 **只写 main_image_w / main_image_h 两列**，不碰任何其他字段，不删不建。
⚠️ **失败的一律保持 NULL**，不猜、不填默认值：客户端对 NULL 有占位兜底，
   而填一个错的比例比没有更糟 —— 卡片高度与图对不上，且没人知道它是错的。

前置
----
- `psql` 需可用（脚本不依赖 psycopg，与 `daily-health-check.py` 一样保持纯标准库）。
  本机没装客户端时用 `--psql 'docker exec -i <容器名> psql'` 借容器里的。
- 图必须可访问：公开桶直接 HTTP GET 即可；私有桶请先换成带读权限的 base 或在能访问的网络里跑。
- `OSS_CDN_BASE_URL` 必须与后端 `ShopImageUrlResolver` 用的是同一个值，
  否则拼出来的 URL 全是 404（脚本会如实报为失败，不会写脏数据）。
"""

import argparse
import os
import shlex
import struct
import subprocess
import sys
import urllib.error
import urllib.request

# 读多少字节就足够解析出尺寸。
# JPEG 的 SOF 段可能被一大堆 EXIF / ICC 元数据推后，64KB 是实测够用又不浪费的折中；
# 真遇到超过的，脚本报 "header too short" 并跳过，不会误填。
HEAD_BYTES = 64 * 1024

TIMEOUT = 15


# ---------------------------------------------------------------- 图片头解析
# 🔴 自己解析而不用 Pillow：这三种格式的尺寸都在文件开头的固定位置，
#    解析逻辑不到 60 行，而引入 Pillow 会让这个运维脚本多一个必须先 pip install 的前置。
#    格式白名单与上传侧一致（jpeg / png / webp），其余一律报 unsupported 而不是猜。

def _png_size(b: bytes):
    """PNG：签名 8 字节 + 4 字节长度 + 'IHDR' + 宽(4) + 高(4)，全部大端。"""
    if len(b) < 24 or b[:8] != b"\x89PNG\r\n\x1a\n" or b[12:16] != b"IHDR":
        return None
    w, h = struct.unpack(">II", b[16:24])
    return (w, h)


def _jpeg_size(b: bytes):
    """JPEG：跳过各段，找 SOF（帧起始）段，里面是 精度(1) + 高(2) + 宽(2)。

    ⚠️ 注意是**先高后宽**，与 PNG/WebP 相反 —— 这里搞反了会让所有竖图变横图。
    """
    if len(b) < 4 or b[:2] != b"\xff\xd8":
        return None
    i = 2
    n = len(b)
    while i + 3 < n:
        if b[i] != 0xFF:
            i += 1
            continue
        marker = b[i + 1]
        # 填充字节 / 无载荷标记，直接跳过
        if marker in (0xFF, 0x01) or 0xD0 <= marker <= 0xD9:
            i += 2
            continue
        seg_len = struct.unpack(">H", b[i + 2:i + 4])[0]
        # SOF0..SOF15，排除 DHT(c4) / JPG(c8) / DAC(cc) 这三个不是帧起始的
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            if i + 9 > n:
                return None
            h, w = struct.unpack(">HH", b[i + 5:i + 9])
            return (w, h)
        i += 2 + seg_len
    return None


def _webp_size(b: bytes):
    """WebP：RIFF 容器，三种子格式各有各的尺寸位置。"""
    if len(b) < 30 or b[:4] != b"RIFF" or b[8:12] != b"WEBP":
        return None
    chunk = b[12:16]
    if chunk == b"VP8 ":                       # 有损
        # 帧头：3 字节 tag + 3 字节同步码 + 宽(2) + 高(2)，低 14 位有效
        w, h = struct.unpack("<HH", b[26:30])
        return (w & 0x3FFF, h & 0x3FFF)
    if chunk == b"VP8L":                       # 无损
        if b[20] != 0x2F:
            return None
        bits = struct.unpack("<I", b[21:25])[0]
        return ((bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1)
    if chunk == b"VP8X":                       # 扩展
        w = int.from_bytes(b[24:27], "little") + 1
        h = int.from_bytes(b[27:30], "little") + 1
        return (w, h)
    return None


def probe_size(b: bytes):
    """从图片头部字节解析 (宽, 高)；解析不出返回 None。"""
    for fn in (_png_size, _jpeg_size, _webp_size):
        try:
            size = fn(b)
        except (struct.error, IndexError):
            continue
        # 0 或负数一律当作没测出来 —— 与后端 ImageSize.isUsable 同一判据
        if size and size[0] > 0 and size[1] > 0:
            return size
    return None


# ---------------------------------------------------------------- 网络
def fetch_head(url: str, nbytes: int = HEAD_BYTES) -> bytes:
    """取图片前 nbytes 字节。优先 Range；服务端不认就普通 GET 后主动截断。"""
    req = urllib.request.Request(url, headers={"Range": f"bytes=0-{nbytes - 1}"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return resp.read(nbytes)


# ---------------------------------------------------------------- 数据库
# psql 可执行命令。默认就是 `psql`，但运维机器不一定装了客户端 ——
# 允许整条命令替换，例如 `docker exec -i petgo-postgres psql`（容器里必然有）。
PSQL_CMD = "psql"


def psql(db_url: str, sql: str) -> str:
    """执行一条 SQL 并返回 stdout。-tA = 无表头、无对齐，便于逐行解析。"""
    out = subprocess.run(
        shlex.split(PSQL_CMD) + [db_url, "-v", "ON_ERROR_STOP=1", "-tAc", sql],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        sys.exit(f"psql 失败：{out.stderr.strip()[:400]}")
    return out.stdout


def fetch_targets(db_url: str, limit):
    """待回填清单：有 key、但尺寸为空的商品。

    🔴 `main_image_w IS NULL OR main_image_h IS NULL` 而不是只判一个 ——
    半边有值是异常状态（写入被打断），也应该重测。
    """
    lim = f" LIMIT {int(limit)}" if limit else ""
    sql = (
        "SELECT id, main_image_key FROM shop_products "
        "WHERE main_image_key IS NOT NULL AND main_image_key <> '' "
        "AND (main_image_w IS NULL OR main_image_h IS NULL) "
        f"ORDER BY id{lim};"
    )
    rows = []
    for line in psql(db_url, sql).splitlines():
        line = line.strip()
        if not line:
            continue
        pid, _, key = line.partition("|")
        rows.append((int(pid), key))
    return rows


def update_size(db_url: str, pid: int, w: int, h: int) -> None:
    """写回尺寸。

    🔴 WHERE 里再判一次 IS NULL：脚本可能跑很久，期间运营正好重传了这张图 ——
    那时库里的值是上传当场测的，比这里回填的更可信，不该被覆盖。
    """
    psql(db_url, (
        f"UPDATE shop_products SET main_image_w={int(w)}, main_image_h={int(h)} "
        f"WHERE id={int(pid)} AND (main_image_w IS NULL OR main_image_h IS NULL);"
    ))


# ---------------------------------------------------------------- 主流程
def build_url(base: str, key: str) -> str:
    """与后端 ShopImageUrlResolver.publicUrl 同一拼法：去尾斜杠 + '/' + 去头斜杠。"""
    return base.rstrip("/") + "/" + key.lstrip("/")


def main() -> int:
    ap = argparse.ArgumentParser(description="回填 shop_products 的主图宽高")
    ap.add_argument("--db-url", default=os.environ.get("DB_URL"),
                    help="postgresql://user:pass@host:port/db（或用环境变量 DB_URL）")
    ap.add_argument("--cdn-base", default=os.environ.get("OSS_CDN_BASE_URL"),
                    help="公开桶 CDN 前缀（或用环境变量 OSS_CDN_BASE_URL）")
    ap.add_argument("--psql", default=os.environ.get("PSQL_CMD", "psql"),
                    help="psql 命令；本机没装客户端时可传 "
                         "'docker exec -i <容器名> psql'")
    ap.add_argument("--limit", type=int, default=0, help="只处理前 N 条（先小批量试）")
    ap.add_argument("--dry-run", action="store_true", help="只测量并打印，不写库")
    args = ap.parse_args()

    global PSQL_CMD
    PSQL_CMD = args.psql

    if not args.db_url:
        return int(bool(sys.stderr.write("缺 --db-url / DB_URL\n"))) or 2
    if not args.cdn_base:
        return int(bool(sys.stderr.write("缺 --cdn-base / OSS_CDN_BASE_URL\n"))) or 2

    targets = fetch_targets(args.db_url, args.limit)
    if not targets:
        print("没有待回填的商品（所有带主图的商品都已有尺寸）。")
        return 0

    print(f"待回填 {len(targets)} 条" + ("（dry-run，不写库）" if args.dry_run else ""))
    ok = failed = 0
    failures = []

    for pid, key in targets:
        url = build_url(args.cdn_base, key)
        try:
            head = fetch_head(url)
        except (urllib.error.URLError, urllib.error.HTTPError, OSError) as e:
            failed += 1
            failures.append((pid, key, f"下载失败: {type(e).__name__} {e}"))
            continue

        size = probe_size(head)
        if not size:
            failed += 1
            reason = "header too short" if len(head) < 24 else "格式不支持或头部被元数据推后"
            failures.append((pid, key, f"解析失败: {reason}（读到 {len(head)} 字节）"))
            continue

        w, h = size
        print(f"  id={pid:<6} {w}x{h}  ratio={w / h:.2f}  {key}")
        if not args.dry_run:
            update_size(args.db_url, pid, w, h)
        ok += 1

    print(f"\n成功 {ok} / 失败 {failed}")
    if failures:
        # ⚠️ 失败的**保持 NULL**，客户端走占位兜底。这里逐条列出来是为了让人能判断
        #    是"图真没了"（该清理商品）还是"网络/权限问题"（该重跑），两者处置完全不同。
        print("\n失败明细（这些商品的尺寸保持 NULL，App 端仍走占位兜底）：")
        for pid, key, why in failures[:50]:
            print(f"  id={pid:<6} {key}  → {why}")
        if len(failures) > 50:
            print(f"  …另有 {len(failures) - 50} 条未列出")
    return 0


if __name__ == "__main__":
    sys.exit(main())
