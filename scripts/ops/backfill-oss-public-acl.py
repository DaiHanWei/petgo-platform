#!/usr/bin/env python3
"""给公开桶里的存量对象补 x-oss-object-acl: public-read（bug 20260901-472）。

背景
----
桶级并非公开读（BPA 关闭 + 桶 ACL 私有），对象必须**逐个**带 public-read 才能公网直读。
服务端上传（AdminSeedImageService → putPublicObject）此前不带该标记 ——
上传成功但公网 403：后台素材墙裂图，发布后 App 端同样加载不出来。
代码侧已改走 putPublicObjectWithAcl（新对象自带标记）；本脚本只负责**存量**对象补一次。

受影响前缀（同一条上传链路的 5 个业务目录，均在 <MEDIA_OSS_KEY_PREFIX>public/ 之下）：
seed-post / seed-batch / shop-banner / shop-product / virtual-avatar。

用法
----
    export ALIYUN_ACCESS_KEY_ID=...      # 凭证只经 env 注入，绝不入库/入文件
    export ALIYUN_ACCESS_KEY_SECRET=...
    export OSS_ENDPOINT=https://oss-ap-southeast-5.aliyuncs.com
    export OSS_PUBLIC_BUCKET=...

    python3 scripts/ops/backfill-oss-public-acl.py --prefix media/public/ --dry-run  # 先看清单
    python3 scripts/ops/backfill-oss-public-acl.py --prefix media/public/            # 真补

🔴 --prefix 必须填**服务器上实际的** MEDIA_OSS_KEY_PREFIX + "public/"（去 .env 里核对，
   别猜）。前缀给宽了也无妨 —— 这个桶本来就叫「公开桶」，里面不存在"不该公开"的对象；
   本脚本只改 ACL，不动内容、不删除（F21：OSS 对象任何情况不物理删除）。
"""
import argparse
import base64
import email.utils
import hashlib
import hmac
import os
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


def env(name, required=True):
    v = os.environ.get(name, "").strip()
    if required and not v:
        sys.exit(f"✗ 缺少环境变量 {name}（凭证只经 env 注入，绝不入库/入文件）")
    return v


def sign(secret, verb, content_md5, content_type, date, oss_headers, resource):
    """OSS V1 签名（HMAC-SHA1），与 scripts/upload-shop-images-oss.py 同一实现。"""
    canon_headers = "".join(
        f"{k.lower()}:{v}\n" for k, v in sorted(oss_headers.items(), key=lambda kv: kv[0].lower())
    )
    to_sign = f"{verb}\n{content_md5}\n{content_type}\n{date}\n{canon_headers}{resource}"
    mac = hmac.new(secret.encode(), to_sign.encode(), hashlib.sha1).digest()
    return base64.b64encode(mac).decode()


def request(endpoint, bucket, path_and_query, resource, verb, ak, sk, oss_headers=None):
    host = endpoint.split("://", 1)[-1].rstrip("/")
    scheme = endpoint.split("://", 1)[0] if "://" in endpoint else "https"
    url = f"{scheme}://{bucket}.{host}{path_and_query}"
    date = email.utils.formatdate(usegmt=True)
    oss_headers = oss_headers or {}
    auth = sign(sk, verb, "", "", date, oss_headers, resource)
    req = urllib.request.Request(url, method=verb, headers={
        "Host": f"{bucket}.{host}",
        "Date": date,
        "Authorization": f"OSS {ak}:{auth}",
        **oss_headers,
    })
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.status, r.read()


def list_keys(endpoint, bucket, prefix, ak, sk):
    """ListObjects（V1，分页 marker），返回全部 objectKey。"""
    keys, marker = [], ""
    ns = "{http://doc.oss-cn-hangzhou.aliyuncs.com}"  # 命名空间随 region 变，做两手解析
    while True:
        q = f"/?prefix={urllib.parse.quote(prefix)}&max-keys=1000"
        if marker:
            q += f"&marker={urllib.parse.quote(marker)}"
        # 🔴 ListObjects 的签名资源串只有 /bucket/（子资源查询参数不参与 V1 签名）。
        _, body = request(endpoint, bucket, q, f"/{bucket}/", "GET", ak, sk)
        root = ET.fromstring(body)
        def findall(tag):
            return root.findall(tag) or root.findall(ns + tag)
        batch = []
        for c in root.iter():
            if c.tag.endswith("Contents"):
                for k in c:
                    if k.tag.endswith("Key"):
                        batch.append(k.text)
        keys += batch
        truncated = any(e.text == "true" for e in root.iter() if e.tag.endswith("IsTruncated"))
        if not truncated or not batch:
            return keys
        marker = batch[-1]


def set_public_read(endpoint, bucket, key, ak, sk):
    quoted = urllib.parse.quote(key)
    headers = {"x-oss-object-acl": "public-read"}
    status, _ = request(endpoint, bucket, f"/{quoted}?acl", f"/{bucket}/{key}?acl",
                        "PUT", ak, sk, headers)
    return status


def main():
    ap = argparse.ArgumentParser(description="公开桶存量对象补 public-read ACL")
    ap.add_argument("--prefix", required=True,
                    help="objectKey 前缀（服务器 .env 的 MEDIA_OSS_KEY_PREFIX + 'public/'）")
    ap.add_argument("--dry-run", action="store_true", help="只列对象，不改 ACL")
    args = ap.parse_args()

    bucket = env("OSS_PUBLIC_BUCKET")
    endpoint = os.environ.get("OSS_ENDPOINT", "https://oss-ap-southeast-5.aliyuncs.com")
    ak = env("ALIYUN_ACCESS_KEY_ID")
    sk = env("ALIYUN_ACCESS_KEY_SECRET")

    print(f"桶     {bucket} @ {endpoint}")
    print(f"前缀   {args.prefix}")
    keys = list_keys(endpoint, bucket, args.prefix, ak, sk)
    print(f"命中   {len(keys)} 个对象\n")
    if not keys:
        sys.exit("✗ 前缀下没有对象 —— 检查 --prefix 是否与服务器 MEDIA_OSS_KEY_PREFIX 一致")

    ok = failed = 0
    for i, key in enumerate(keys, 1):
        if args.dry_run:
            print(f"[{i}/{len(keys)}] DRY-RUN  {key}")
            continue
        try:
            set_public_read(endpoint, bucket, key, ak, sk)
            ok += 1
            if i % 50 == 0 or i == len(keys):
                print(f"[{i}/{len(keys)}] 已处理 …")
        except Exception as e:  # noqa: BLE001 —— 单个失败不中断，最后汇总
            failed += 1
            print(f"[{i}/{len(keys)}] ✗ {key}: {e}")
    if not args.dry_run:
        print(f"\n完成：public-read {ok} 个，失败 {failed} 个"
              + ("（失败的重跑一次即可，操作幂等）" if failed else ""))


if __name__ == "__main__":
    main()
