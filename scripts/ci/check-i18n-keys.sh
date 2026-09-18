#!/usr/bin/env bash
# 三语 message key 集合守门（V1.3.0 Story 2.2 AC7，NFR-3）：zh_CN / en / id / 默认包 四份 key 集合必须逐一相等。
#
# 规则：任一包缺 key 或多 key → 打印 diff（< 仅 zh_CN 有 / > 仅该包有）并 exit 1。
# 用法：bash scripts/ci/check-i18n-keys.sh
# 与 check-flyway-versions.sh 并列接入 CI（backend-ci.yml）；每条 story 提交前本地跑一次。
# key 取法：行首 [A-Za-z0-9_.-]+ 直到 = 或空白；# 开头的注释行与空行天然不匹配。
set -uo pipefail
export LC_ALL=C

DIR="petgo-backend/src/main/resources/i18n"
BASE="messages_zh_CN.properties"
if [[ ! -f "$DIR/$BASE" ]]; then
  echo "::error::找不到 $DIR/$BASE（请在仓库根目录运行）"
  exit 1
fi

keys() { grep -oE '^[A-Za-z0-9_.-]+' "$DIR/$1" | sort -u; }

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
base=$(keys "$BASE")
fail=0
for f in messages_en.properties messages_id.properties messages.properties; do
  if ! diff <(echo "$base") <(keys "$f") > "$tmp"; then
    echo "::error::$f 与 $BASE 的 key 集合不一致（< 仅 zh_CN 有，> 仅 $f 有）"
    cat "$tmp"
    fail=1
  fi
done

# 同一包内重复 key（后者静默覆盖前者）也算错。
for f in "$BASE" messages_en.properties messages_id.properties messages.properties; do
  dups=$(grep -oE '^[A-Za-z0-9_.-]+' "$DIR/$f" | sort | uniq -d)
  if [[ -n "$dups" ]]; then
    echo "::error::$f 内有重复 key：$(echo "$dups" | tr '\n' ' ')"
    fail=1
  fi
done

if [[ $fail -eq 0 ]]; then
  echo "i18n-keys: OK（$(echo "$base" | grep -c .) 个 key，四包集合相等、无重复）"
fi
exit $fail
