# Deferred Work · 专题线 video

延后处理项（L2 走查 / 评审中发现、非当前 story 阻断）。

## 2026-10-17 · stag L2 走查遗留（修复提交 42ed1f51 之后）

- **【下次必改】播放页右侧点赞排版**：点赞数在爱心**右边**（横排），评论数在图标**下方**（竖排），同一竖栏里两种排法不一致。
  - 用户拍板：**不用共用的 `LikeButton`**（它是 Feed / 详情页的横排组件，改它会波及其它页面），播放页单独写一个竖排的点赞控件：图标在上、数字在下，和评论、分享对齐。
  - 位置：`petgo_app/lib/features/content/video/video_post_overlay.dart` 右侧 `videoActions` 栏。
  - 行为要与 `LikeButton` 保持一致：乐观更新 + 失败回滚、游客登录墙、点数字打开点赞人列表、`onSettled` 回写 Feed 计数（`syncFeedCounts`）。
  - 修完后去掉本次为播放页加的 `LikeButton.onDark` 参数（届时无人使用）。
- Video 按钮置灰（已选图片）时没有说明原因。
- 分享卡片封面没有视频标识（播放角标），底部按钮文案「Bagikan ke Diary」对视频帖不贴切。
- H5 分享页提示「Perbarui TailTopia ke versi terbaru」面向的是网页访客（多半没装 App），措辞不对；分享页无 `og:image`（图文帖同样存在，存量问题）。
- 进入播放页先黑屏约 0.3 秒再出封面，可沿用列表封面过渡。
- 未测项：他人视角（举报 / 关注 / 关注流）、有声播放（样片无音轨）、断网下的加载失败重试与 15 秒超时、运营顶置视频卡片。
