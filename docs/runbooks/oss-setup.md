# 阿里云 OSS 配置（Story 2.1 媒体上传）

> 方案：**单桶 + 对象级 ACL + 后端预签名上传**。后端用 env 注入的 AccessKey 现签一张「限定对象 +
> 限定头 + 短 TTL」的预签名 PUT URL 给客户端，客户端凭此**直传 OSS（字节不绕后端）**，而
> **真 AccessKey 始终只在后端**。无需 STS、无需 RAM 角色。

## 需要准备的

1. **一个 OSS 桶**（本项目 `tailtopia`，区域 `ap-southeast-5` 雅加达）。public/private 两个桶名
   都填这一个桶；前缀区分隐私域（`public/{userId}/` vs `private/{userId}/`）。
2. **一个有该桶 OSS 写权限的 AccessKey**（主账号或 RAM 子用户均可）。最小权限：对
   `acs:oss:*:*:tailtopia/*` 的 `oss:PutObject` + `oss:PutObjectAcl`（后者用于公开域打 public-read）。
   读签名 URL 由后端用同一 key 现签，无需额外权限。
3. **桶级「阻止公共访问（Block Public Access）」= 关闭**。否则公开域对象打 `public-read` 会被拒
   （`Put public object acl is not allowed`）。账号级 BPA 不影响本桶单独设置。

## 填入 `petgo-backend/.env`（gitignored，绝不入库）

```
ALIYUN_ACCESS_KEY_ID=<你的 AccessKey ID>
ALIYUN_ACCESS_KEY_SECRET=<你的 AccessKey Secret>
OSS_ENDPOINT=https://oss-ap-southeast-5.aliyuncs.com
OSS_REGION=ap-southeast-5
OSS_PUBLIC_BUCKET=tailtopia
OSS_PRIVATE_BUCKET=tailtopia
OSS_CDN_BASE_URL=https://tailtopia.oss-ap-southeast-5.aliyuncs.com
# 可选：UPLOAD_URL_TTL_SECONDS=600  SIGNED_URL_TTL_SECONDS=300
```

> `OSS_CDN_BASE_URL` 公开域对外读 URL 前缀。暂无 CDN 时直接用 OSS 桶公网域名（如上）；接入 CDN 后
> 换成 CDN 加速域名即可，代码无需改。

## 验收

本地配齐后跑 `python3 scripts/local/oss_l2_accept.py`（gitignored，读 env，不打印密钥）：
- J1  公开域预签名直传 + 公网 GET=200
- J1b 预签名 URL 绑定单对象（改对象路径 → SignatureDoesNotMatch）
- J1c 私密域预签名直传
- J2  私密读签名 URL 三态（无签名 403 / 有效 200 / 过期 403）

## 与客户端的契约

`POST /api/v1/media/upload-url`（需 JWT）body `{scope, contentType}` → 返回
`{uploadUrl, objectKey, method:"PUT", headers, publicUrl?}`。客户端用 `method` 把字节 PUT 到
`uploadUrl`，并**原样带上 `headers`**（含签入的 `Content-Type`；公开域含 `x-oss-object-acl`）。
漏发/改动这些头或改对象路径 → OSS `SignatureDoesNotMatch`。
