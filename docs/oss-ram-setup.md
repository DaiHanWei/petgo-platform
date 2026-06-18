# 阿里云 RAM 角色配置（Story 2.1 STS 直传）

> 目的：客户端上传图片走 **STS 临时凭证**，绝不下发主账号 AccessKey。后端用主账号 key 调
> `AssumeRole` 拿到一个**收窄到「该用户该前缀」**的临时凭证再发给客户端。这一步需要你在阿里云
> 控制台建一个 **RAM 角色**，把 `RoleArn` 填回 `petgo-backend/.env` 的 `STS_ROLE_ARN`。
>
> 本项目已探明的账号信息：
> - 账号 ID（UID）：`5967981790439929`
> - 桶：`tailtopia`，区域 `ap-southeast-5`（雅加达）
> - 主账号 AccessKey：仅注入 `petgo-backend/.env`（gitignored），**不在本文档/仓库出现明文**

---

## 一、建 RAM 角色（控制台）

1. 打开 **RAM 访问控制 → 角色 → 创建角色**。
2. 选择 **可信实体类型 = 阿里云账号**，授信本账号（当前账号）。
3. 角色名填：`tailtopia-oss-upload`（随意，但要记住）。
4. 创建后进入角色详情，确认其 **信任策略** 如下（控制台会自动生成等价内容）：

```json
{
  "Statement": [
    {
      "Action": "sts:AssumeRole",
      "Effect": "Allow",
      "Principal": {
        "RAM": ["acs:ram::5967981790439929:root"]
      }
    }
  ],
  "Version": "1"
}
```

> `:root` 表示本账号下任意身份（含主账号 AccessKey）都可 AssumeRole。后端再用每次请求的内联
> 策略把权限收窄到单用户前缀，所以这里给 root 是安全的。

## 二、给角色绑权限策略（角色能做什么的上限）

角色本身的权限是「上限」，后端 STS 每次再用内联策略二次收窄（取交集）。给角色一个**只限本桶
写 + 打 ACL** 的自定义权限策略：

1. **RAM → 权限策略 → 创建权限策略**，名为 `tailtopia-oss-put`，脚本编辑模式粘贴：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["oss:PutObject", "oss:PutObjectAcl"],
      "Resource": ["acs:oss:*:*:tailtopia/*"]
    }
  ]
}
```

2. 回到角色 `tailtopia-oss-upload` → **新增授权** → 选刚建的 `tailtopia-oss-put`。

> 为何含 `oss:PutObjectAcl`：单桶方案下，公开域对象上传时要打 `public-read` ACL 才能走公网读，
> 该动作在 RAM 鉴权时校验 `oss:PutObjectAcl`。私密域上传后端内联策略不会授此动作。

## 三、把 RoleArn 填回 .env

角色详情页顶部有 **ARN**，形如：

```
acs:ram::5967981790439929:role/tailtopia-oss-upload
```

填到 `petgo-backend/.env`：

```
STS_ROLE_ARN=acs:ram::5967981790439929:role/tailtopia-oss-upload
```

> `.env` 已被 git 忽略，凭证不会进版本库。

---

## 四、（可选）公网可读前置检查

单桶 + 对象级 ACL 依赖「对象 public-read 能生效」。若桶开了 **阻止公共访问（Block Public
Access）**，公开域图片即便打了 `public-read` 也会被拦。请在 **OSS 控制台 → tailtopia → 权限管理**
确认未开启「阻止公共访问」（或仅按需放开）。

## 五、命令行等价（可选，aliyun CLI）

```bash
# 信任策略
aliyun ram CreateRole --RoleName tailtopia-oss-upload \
  --AssumeRolePolicyDocument '{"Statement":[{"Action":"sts:AssumeRole","Effect":"Allow","Principal":{"RAM":["acs:ram::5967981790439929:root"]}}],"Version":"1"}'
# 权限策略
aliyun ram CreatePolicy --PolicyName tailtopia-oss-put \
  --PolicyDocument '{"Version":"1","Statement":[{"Effect":"Allow","Action":["oss:PutObject","oss:PutObjectAcl"],"Resource":["acs:oss:*:*:tailtopia/*"]}]}'
aliyun ram AttachPolicyToRole --PolicyType Custom --PolicyName tailtopia-oss-put --RoleName tailtopia-oss-upload
```

完成后告诉我，我跑后端 L2 验收脚本（STS 签发→直传→签名 URL 403/200/过期→越权被拒）。
