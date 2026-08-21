#!/usr/bin/env python3
"""把门店商品主图上传到 OSS 公开桶，让 shop_products.main_image_key 真正取到图。

背景
----
`shop_products.main_image_key` 存的是 **OSS objectKey，不是 URL**（CLAUDE.md 护栏：签名 URL
禁入库）。展示侧由 `ShopImageUrlResolver` 拼成 `OSS_CDN_BASE_URL + "/" + objectKey`。
所以「商品有图」= 桶里 objectKey 这个位置真有对象，**与数据库无关**——
本脚本只搬对象，一行 SQL 都不会执行。

图片来源：运营《Tailtopia 比价表》里 28 个 Tokopedia 商详页链接，24 个抓到主图
（4 个链接已 410 下架）。文件名即 objectKey 的 basename，与库里既有 key 一一对应，
因此**上传完成即生效，不需要改库**。

用法
----
    export ALIYUN_ACCESS_KEY_ID=...      # 绝不写进任何文件
    export ALIYUN_ACCESS_KEY_SECRET=...
    export OSS_ENDPOINT=https://oss-ap-southeast-5.aliyuncs.com
    export OSS_PUBLIC_BUCKET=...
    export OSS_CDN_BASE_URL=https://...  # 只有 --verify 需要

    python3 scripts/upload-shop-images-oss.py --dry-run       # 先看要传什么
    python3 scripts/upload-shop-images-oss.py                 # 真传
    python3 scripts/upload-shop-images-oss.py --verify        # 传完回读 CDN 校验

🔴 对象必须落**公开桶**并带 public-read ACL —— 本仓库读侧没有签名 URL 能力
（见 ShopImageUrlResolver 的类注释），落私有桶会拼出 URL 但公网 403。

🔴 objectKey 前缀默认 `seed/tailtopia/`，与库里既有 main_image_key 完全一致。
   不要套用 MEDIA_OSS_KEY_PREFIX —— 那个前缀只作用于后端**新签发**的上传，
   ShopImageUrlResolver 不加它；套上去就会 404。
"""
import argparse
import base64
import email.utils
import hashlib
import hmac
import mimetypes
import os
import sys
import urllib.error
import urllib.request

DEFAULT_DIR = os.path.expanduser("~/Desktop/petgo-shop-images/oss")
DEFAULT_PREFIX = "seed/tailtopia/"


def env(name, required=True):
    v = os.environ.get(name, "").strip()
    if required and not v:
        sys.exit(f"✗ 缺少环境变量 {name}（凭证只经 env 注入，绝不入库/入文件）")
    return v


def sign(secret, verb, content_md5, content_type, date, oss_headers, resource):
    """OSS V1 签名（HMAC-SHA1）。"""
    canon_headers = "".join(
        f"{k.lower()}:{v}\n" for k, v in sorted(oss_headers.items(), key=lambda kv: kv[0].lower())
    )
    to_sign = f"{verb}\n{content_md5}\n{content_type}\n{date}\n{canon_headers}{resource}"
    mac = hmac.new(secret.encode(), to_sign.encode(), hashlib.sha1).digest()
    return base64.b64encode(mac).decode()


def put_object(endpoint, bucket, key, body, content_type, ak, sk):
    host = endpoint.split("://", 1)[-1].rstrip("/")
    scheme = endpoint.split("://", 1)[0] if "://" in endpoint else "https"
    url = f"{scheme}://{bucket}.{host}/{key}"
    date = email.utils.formatdate(usegmt=True)
    md5 = base64.b64encode(hashlib.md5(body).digest()).decode()
    oss_headers = {"x-oss-object-acl": "public-read"}
    auth = sign(sk, "PUT", md5, content_type, date, oss_headers, f"/{bucket}/{key}")
    req = urllib.request.Request(url, data=body, method="PUT", headers={
        "Host": f"{bucket}.{host}",
        "Date": date,
        "Content-Type": content_type,
        "Content-MD5": md5,
        "Content-Length": str(len(body)),
        "Authorization": f"OSS {ak}:{auth}",
        **oss_headers,
    })
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.status


def main():
    ap = argparse.ArgumentParser(description="上传门店商品主图到 OSS 公开桶")
    ap.add_argument("--dir", default=DEFAULT_DIR, help=f"图片目录（默认 {DEFAULT_DIR}）")
    ap.add_argument("--key-prefix", default=DEFAULT_PREFIX,
                    help=f"objectKey 前缀（默认 {DEFAULT_PREFIX}，须与库里 main_image_key 一致）")
    ap.add_argument("--dry-run", action="store_true", help="只打印计划，不上传")
    ap.add_argument("--verify", action="store_true", help="上传后回读 CDN URL 校验可公开访问")
    args = ap.parse_args()

    if not os.path.isdir(args.dir):
        sys.exit(f"✗ 目录不存在：{args.dir}")
    files = sorted(f for f in os.listdir(args.dir)
                   if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp")))
    if not files:
        sys.exit(f"✗ {args.dir} 里没有图片")

    bucket = env("OSS_PUBLIC_BUCKET", required=not args.dry_run)
    endpoint = os.environ.get("OSS_ENDPOINT", "https://oss-ap-southeast-5.aliyuncs.com")
    ak = env("ALIYUN_ACCESS_KEY_ID", required=not args.dry_run)
    sk = env("ALIYUN_ACCESS_KEY_SECRET", required=not args.dry_run)
    cdn = os.environ.get("OSS_CDN_BASE_URL", "").rstrip("/")

    print(f"目录     {args.dir}")
    print(f"桶       {bucket or '(dry-run 未校验)'}  @ {endpoint}")
    print(f"objectKey  {args.key_prefix}<文件名>   共 {len(files)} 个\n")

    ok = failed = 0
    for i, fn in enumerate(files, 1):
        key = args.key_prefix + fn
        path = os.path.join(args.dir, fn)
        body = open(path, "rb").read()
        ctype = mimetypes.guess_type(fn)[0] or "application/octet-stream"
        if args.dry_run:
            print(f"[{i:2}/{len(files)}] DRY-RUN  {key}  ({len(body):,} B, {ctype})")
            ok += 1
            continue
        try:
            put_object(endpoint, bucket, key, body, ctype, ak, sk)
            print(f"[{i:2}/{len(files)}] ✓ {key}  ({len(body):,} B)")
            ok += 1
        except urllib.error.HTTPError as e:
            print(f"[{i:2}/{len(files)}] ✗ {key}  HTTP {e.code} {e.read()[:200].decode('utf-8','replace')}")
            failed += 1
        except Exception as e:
            print(f"[{i:2}/{len(files)}] ✗ {key}  {type(e).__name__}: {e}")
            failed += 1

    print(f"\n上传 {ok} 成功 / {failed} 失败")

    if args.verify and not args.dry_run:
        if not cdn:
            sys.exit("✗ --verify 需要 OSS_CDN_BASE_URL（要和后端 ShopImageUrlResolver 用的同一个值）")
        print(f"\n回读校验 {cdn} ...")
        bad = 0
        for fn in files:
            u = f"{cdn}/{args.key_prefix}{fn}"
            try:
                with urllib.request.urlopen(u, timeout=30) as r:
                    if r.status != 200:
                        print(f"  ✗ {r.status} {u}"); bad += 1
            except Exception as e:
                print(f"  ✗ {type(e).__name__} {u}"); bad += 1
        print(f"  {len(files) - bad}/{len(files)} 可公开访问" + ("" if not bad else "  ← 检查桶 ACL / CDN 回源"))
        if bad:
            sys.exit(1)

    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
