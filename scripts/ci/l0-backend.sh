#!/usr/bin/env bash
# 云端 L0 跑法（V1.3.0 Epic 11 起入库）：`mvn -B clean package`，但把需要真库 / 真 Spring 上下文
# （L1）的测试类排除 —— 云沙箱没有 Docker daemon，那些类一跑就是整片 ERROR，
# 把「真的编译/单测挂了」淹没在噪声里。
#
# 用法：bash scripts/ci/l0-backend.sh [额外的 mvn 参数]
#
# ⚠️ **这不是 CI 用的**：CI（backend-ci.yml）有 postgres / redis service，跑的是完整的
#    `./mvnw -B package`，一条都不排除。这个脚本只服务「没有 Docker 的开发/云端环境」。
#    story 的 Debug Log 里写「L0 全绿」时指的就是它 —— 入库是为了让那句话可复现
#    （V1.3.0 Story 11.4 复审 C11：原来它只存在于某个 session 的临时目录里）。
# 排除判据：文件含 @SpringBootTest / @WebMvcTest / @DataJpaTest / @Autowired / extends <已知 L1 基类>（迭代到不动点）。
set -uo pipefail
cd "$(dirname "$0")/../../petgo-backend"
export LC_ALL=C.UTF-8 LANG=C.UTF-8
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
EXC=$(mktemp)
trap 'rm -f "$EXC"' EXIT
python3 - "$EXC" <<'EOF'
import os,re,sys
root='src/test/java'
files={}
for dp,_,fs in os.walk(root):
    for f in fs:
        if f.endswith('.java'):
            p=os.path.join(dp,f); files[p]=open(p,encoding='utf-8',errors='ignore').read()
marker=re.compile(r'@SpringBootTest|@WebMvcTest|@DataJpaTest|@JdbcTest|@Autowired|@MockitoBean|@MockBean|@SpringJUnitConfig|@Testcontainers|@ServiceConnection|@AutoConfigureMockMvc')
l1=set(); names={}
for p,s in files.items():
    cls=os.path.basename(p)[:-5]; names[cls]=p
    if marker.search(s): l1.add(cls)
changed=True
while changed:
    changed=False
    for p,s in files.items():
        cls=os.path.basename(p)[:-5]
        if cls in l1: continue
        m=re.search(r'\bextends\s+([A-Za-z0-9_]+)',s)
        if m and m.group(1) in l1:
            l1.add(cls); changed=True
with open(sys.argv[1],'w') as o:
    for c in sorted(l1):
        rel=os.path.relpath(names[c],root)
        o.write('**/'+os.path.basename(rel)+'\n')
print(f'L0 runner: excluded {len(l1)} Spring-context test classes of {len(files)} test files')
EOF
./mvnw -B clean package -Dsurefire.excludesFile="$EXC" "$@"
