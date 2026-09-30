# assets/tailsonality —— Tailsonality 性格测试素材

V1.3.2 batch-a Epic 2（FR-117）。**设计素材暂不入库**（决策日志 D-21）：素材到货后**只往本目录放同名文件、不改代码**即生效；
文件缺失时 App 画代码绘制的占位（浅紫底 + 图标），不会崩。本目录**扁平存放**，文件名不得含四字母商标词（Story 2.2 扫描会查）。

| 文件名 | 用途 | 规格 | 引用处 |
|---|---|---|---|
| `intro_poster.webp` | 测试说明抽屉顶部海报（Story 2.3） | 16:9，建议 1600×900（源图 3072×1728，`cwebp -q 82 -resize 1600 0`） | `presentation/widgets/tailsonality_intro_sheet.dart` |
| `quiz_p1_0.webp` … `quiz_p1_3.webp` | 图片题 P1 位置示意（门口 / 客厅正中 / 角落 / 藏身处） | 4:3，建议 600×450 | `presentation/widgets/ts_image_option_grid.dart` |
| `quiz_p2_0.webp` … `quiz_p2_3.webp` | 图片题 P2 姿态剪影（剧烈闪避 / 僵住 / 轻微一顿 / 纹丝不动） | 同上 | 同上 |
| `quiz_p3_0.webp` … `quiz_p3_3.webp` | 图片题 P3 时刻场景（深夜 / 清晨 / 傍晚 / 全天一样） | 同上 | 同上 |
| `role_<CODE>.webp` | 16 角色插画（Story 2.4 起用，CODE = 四字母代号） | 3:4，建议 1080×1440 | Story 2.4 |
| `match_tier<N>.webp` | 5 档配型插画（Story 2.5 起用） | 3:4 | Story 2.5 |

图片题四张之间须有明确强弱梯度（+2 / +1 / −1 / −2，设计资产清单 §6）；序号 `_0`..`_3` = 选项原始序号。
