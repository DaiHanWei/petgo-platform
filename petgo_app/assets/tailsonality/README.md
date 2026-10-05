# assets/tailsonality —— Tailsonality 性格测试素材

V1.3.2 batch-a Epic 2（FR-117）。素材**分批入库**：已到 `quiz_p*`（2026-10-05，12 张）；其余到货后**只往本目录放同名文件、不改代码**即生效；
文件缺失时 App 画代码绘制的占位（浅紫底 + 图标），不会崩。本目录**扁平存放**，文件名不得含四字母商标词（Story 2.2 扫描会查）。

| 文件名 | 用途 | 规格 | 引用处 |
|---|---|---|---|
| `intro_poster.webp` | 测试说明抽屉顶部海报（Story 2.3） | 16:9，建议 1600×900（源图 3072×1728，`cwebp -q 82 -resize 1600 0`） | `presentation/widgets/tailsonality_intro_sheet.dart` |
| `quiz_p1_0.webp` … `quiz_p1_3.webp` | 图片题 P1 位置示意（门口 / 客厅正中 / 角落 / 藏身处） | 4:3，入库 800×600 webp q82（P1 源图 1:1，左右补白到 4:3，防 `BoxFit.cover` 裁掉边缘标记） | `presentation/widgets/ts_image_option_grid.dart` |
| `quiz_p2_0.webp` … `quiz_p2_3.webp` | 图片题 P2 姿态剪影（剧烈闪避 / 僵住 / 轻微一顿 / 纹丝不动） | 同上 | 同上 |
| `quiz_p3_0.webp` … `quiz_p3_3.webp` | 图片题 P3 时刻场景（深夜 / 清晨 / 傍晚 / 全天一样） | 同上 | 同上 |
| ~~`role_<CODE>.webp`~~ | 16 角色卡**不放本目录**（2026-10-05 产品定：不打包）：在后端 `static/tailsonality/`，出结果时按需下载（`data/ts_role_art.dart`） | 3:4，900×1200 webp q88 | 结果卡 / 分享卡 / Diary 缩略图 |
| `match_tier<N>.webp` | 5 档配型插画（Story 2.5 起用） | 3:4 | Story 2.5 |

图片题四张之间须有明确强弱梯度（+2 / +1 / −1 / −2，设计资产清单 §6）；序号 `_0`..`_3` = 选项原始序号，即设计稿 `+2`→`_0`、`+1`→`_1`、`−1`→`_2`、`−2`→`_3`。
