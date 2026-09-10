# 待安装的 GitHub Actions workflow

> ⚠️ **这个目录里的 yml 不会生效**，直到有人把它们移进 `.github/workflows/`。

## 为什么在这儿而不是直接在 `.github/workflows/`

产出这些文件的云端 session 用的 OAuth 令牌**没有 `workflow` scope**，GitHub 直接拒绝推送：

```
! [remote rejected] refusing to allow an OAuth App to create or update
  workflow `.github/workflows/write-ops-guard.yml` without `workflow` scope
```

这是**凭证权限**问题，不是文件内容问题。把 workflow 悄悄降级成一个「本地跑跑就算了」的脚本、
或者干脆不写，都会让对应 story 的 CI 验收条款名存实亡 —— 所以文件按最终形态写好放这里，
等一次 `git mv` 落位。

## 怎么安装（本地，一次性）

```bash
git mv scripts/ci/github-workflows/write-ops-guard.yml .github/workflows/
git mv scripts/ci/github-workflows/i18n-guard.yml    .github/workflows/
git rm  scripts/ci/github-workflows/README.md        # 目录清空后连这份说明一起删
git commit -m "ci(v1.3.0): 安装 write-ops-guard 与 i18n-guard"
git push
```

移完请把对应 story（11.1 AC5 / 11.2 AC1-AC2）Completion Notes 里的「⏳ 待安装」勾掉。

## 目录内容

| 文件 | 来源 story | 作用 |
|---|---|---|
| `write-ops-guard.yml` | 11.1 AC5 | 重跑写操作清单生成器并与基线 v2 diff：非白名单的端点新增 / 删除、任何 `@PreAuthorize` 漂移 → 不绿 |
| `i18n-guard.yml` | 11.2 AC1 / AC2 | 四份语言包 key 集合相等 + 后台模板与脚本无写死单语文案 |

两者都只需要 bash / python3，不需要 JDK / Docker，秒级到 20 秒级出结果。
安装前可以本地先跑一遍确认：

```bash
bash scripts/ci/list-admin-write-ops.sh \
  --out /tmp/after.md \
  --baseline _bmad-output/implementation-artifacts/v1.3.0/后台写操作清单-20260909-基线v2.md
bash scripts/ci/check-i18n-keys.sh
bash scripts/ci/check-admin-hardcoded-text.sh
```
