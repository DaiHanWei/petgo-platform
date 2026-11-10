---
name: ops AI 查数
description: ops 后台内部查数工具。沿用 admin.css，本文件只写新增部分。
status: final
updated: 2026-11-09
colors:
  brand: '#845EC9'
  brand-strong: '#6C48AE'
  brand-tint: '#F8F2FF'
  brand-line: '#DCD2F7'
  surface: '#FFFFFF'
  surface-quiet: '#FAFAFB'
  bg: '#F5F6F8'
  ink: '#1F2330'
  ink-2: '#444A57'
  muted: '#6C717D'
  line: '#E6E8EC'
  info: '#2B68D7'
  warn: '#936600'
  warn-bg: '#FBF1D9'
  danger: '#C0392B'
  danger-bg: '#FDE8E8'
typography:
  answer-lead: { fontSize: 16px, lineHeight: '1.55' }
  body: { fontSize: 13px }
  caption: { fontSize: 12px }
rounded:
  sm: 6px
  md: 8px
components:
  answer-table: { radius: '{rounded.md}', border: '{colors.line}' }
  composer: { radius: '{rounded.md}', border: '{colors.line}' }
  file-card: { radius: '{rounded.md}', background: '{colors.surface-quiet}' }
---

## Brand & Style

内部高频工具，沿用 ops 后台现有视觉语言：中性、高密度、紫色只用来指路。不做 AI 产品常见的渐变和吉祥物。

## Colors

除 `{colors.surface-quiet}` 外，均取自 `static/admin/admin.css` 的令牌，不新增品牌色。主按钮紫色只用于“发送”和“用原问题重试”。状态色：查询中 `{colors.info}`；超时、额度用尽、连接中断 `{colors.warn}`；查询失败、授权失效 `{colors.danger}`。

## Typography

沿用 admin.css 字体栈。结论句用 `{typography.answer-lead}`，提问文字用加粗正文，统计口径和页脚用 `{typography.caption}`。表格、计时、列表时间使用等宽数字。

## Layout & Spacing

沿用 `tpl-a-workbench`：左栏 360px，右栏最小 720px，电脑端专用。右栏内边距 20px 24px，多轮之间用一条分隔线。

## Elevation & Depth

不加阴影，用描边和浅底分区。输入框获得焦点时显示 `{colors.brand-line}` 2px 外环。

## Shapes

容器 `{rounded.md}`，内部小元素 `{rounded.sm}`。

## Components

新增样式集中在 `mockups/ops-ai-query.css` 的 `aiq-` 前缀下：问题 `aiq-q`、结论 `aiq-lead`、表格 `aiq-table`、口径 `aiq-scope`、页脚 `aiq-foot`、文件卡 `aiq-file`、状态 `aiq-st`、查询中 `aiq-run`、错误卡 `aiq-err`、输入框 `aiq-box`。

## Do's and Don'ts

- 答案用报告式，占满右栏，不用聊天气泡。
- 状态必须同时有文字，不只靠颜色或动效。
- 动效只保留“查询中”的圆点呼吸，并遵守 `prefers-reduced-motion`。
- 不写新的按钮、徽章或表格基础样式。
