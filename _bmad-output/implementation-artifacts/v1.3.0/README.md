# V1.3.0 实现产物

> 集成分支 `dev_1.3.0`；story 由各功能分支 `feat/1.3.0-<主题>` 产出后合回本目录。目前为空。

- `sprint-status-v1.3.0-<主题>.yaml` —— **一主题一份**，`story_location` 均指向本目录；主题清单、Epic 号段与各主题的实际产物文件名见 `../../planning-artifacts/v1.3.0/README.md` 登记表。
- `cloud-rules-<主题>.md` —— 该主题云端批跑的追加规则，由 `scripts/cloud-run-story-loop.sh` 自动拼进提示词。
- 不同主题的 story 编号会重复（都有 `1-1-*`），文件名靠中文名区分；**口头引用 story 必须带主题**。
- `<epic>-<n>-<中文名>.md` —— story 文件。
- 主题内独立的评审/验收记录直接放本目录，文件名带主题，如 `L2-视觉验收报告-ops-ui.md`。
