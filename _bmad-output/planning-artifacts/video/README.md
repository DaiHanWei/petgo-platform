# 专题线 video：短视频（暂不定版本）

> 暂不挂版本号（决策 V-0）。定版本后，归入对应的 `<ver>/` 集成分支，并在 `_bmad-output/README.md` 的版本表里改登记。
> 调用 BMAD 技能时请在提示词里写明「专题线 video」，路径指向本目录；功能分支命名为 `feat/video-*`。

## 状态

| 件 | 文件 | 状态 |
|---|---|---|
| ① PRD | `PRD-video.md` | **final**（2026-10-16） |
| 附录 | `addendum-video.md` | 技术实现参考（供架构 delta / story 用） |
| 决策日志 | `决策日志-video.md` | V-0 ~ V-28 |
| ② 架构 delta | — | 未开始 |
| ③ epics | — | 未开始 |

## 输入与过程文件

- 调研：`../research/video-post-0{1,2,3}-*-2026-10-16.md`（现有链路 / 范围与规格 / 压缩库验证）
- 竞品调研：`竞品调研-video-2026-10-16.md`
- 收尾查漏：`reconcile-research-01.md`、`reconcile-research-02.md`、`reconcile-research-03.md`、`reconcile-competitive.md`
- 评审：`review-rubric.md`（质量标准逐项评审）、`review-adversarial.md`（对抗式评审 + 代码核实）

## 下一步

1. 压缩库已定为 `pro_video_editor`（决策 V-28）；真机表现放到 FR-7 的 L2 验收时确认（PRD §10 第 1 条）。
2. `bmad-create-architecture`（专题线 video）→ 架构 delta。
3. `bmad-create-epics-and-stories`（专题线 video）。
