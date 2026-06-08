---
title: "[App Name] V1.0 Design System"
status: draft
created: 2026-05-29
updated: 2026-05-29
platform: iOS · Android
---

# [App Name] — DESIGN.md

## Brand & Style

**Emotional register:** 温暖活泼、有活力可爱、干净现代、轻韩式风格、有质感小资格调。

**Voice:** 活力社区感——像小红书，年轻、社交、有温度。"12 位铲屎官正在等你 🎉" "快来晒出你的毛孩子！"

**Anti-patterns:** 不做冷感医疗蓝（全屏）、不做过于幼稚的卡通感、不做重工业深色风。

**Inspiration:** OURO 品牌（暖栗 + 燕麦）、Tailpeller（森绿克制）、Daily Paw（粉蓝温柔）、Petly UI kit（干净组件）。

---

## Colors

```yaml
colors:
  # Base — unified across all zones
  background: "#FAF8F5"        # 米白色，全局底色
  surface: "#FFFFFF"           # 卡片、浮层
  border: "#EDE8E3"            # 分割线、描边
  overlay: "rgba(0,0,0,0.4)"  # 半屏遮罩

  # Text
  text-primary: "#1C1917"      # 主文字
  text-secondary: "#9C8C84"    # 辅助文字、时间戳
  text-disabled: "#C4B8B0"     # 禁用态

  # Zone accents — used for CTA, active states, badges, icons only
  accent-growth: "#C8874A"     # 成长档案 / 首页 — 焦糖
  accent-consult: "#7BA7BC"    # 专业问诊 — 莫兰迪蓝
  accent-gather: "#F08040"     # 宠物聚会活动 — 暖橙

  # Semantic — medical triage states (Morandi-toned, not pure saturated)
  triage-green-bg: "#D4EDD4"
  triage-green-text: "#3A7A3A"
  triage-yellow-bg: "#F5E8C0"
  triage-yellow-text: "#8B6914"
  triage-red-bg: "#C97A7A"     # Morandi red，用于半屏遮罩
  triage-red-text: "#FFFFFF"
```

**Zone color rule:** 每个 Tab 使用其区域色作为 active 状态、主要 CTA 按钮、关键 badge 和图标的填充色。页面底色始终为 `background: #FAF8F5`，不因 Tab 切换而整屏变色。

---

## Typography

```yaml
typography:
  font-family: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', sans-serif"

  # Scale（以 390px 标准屏为基准）
  display:    { size: "24px", weight: "700", line-height: "1.3" }
  title:      { size: "18px", weight: "600", line-height: "1.4" }
  heading:    { size: "16px", weight: "600", line-height: "1.4" }
  body:       { size: "14px", weight: "400", line-height: "1.5" }
  body-small: { size: "13px", weight: "400", line-height: "1.5" }
  caption:    { size: "11px", weight: "400", line-height: "1.4" }
  micro:      { size: "10px", weight: "400", line-height: "1.3" }

  # Special
  badge:      { size: "10px", weight: "500" }
  button:     { size: "14px", weight: "600" }
  button-sm:  { size: "12px", weight: "500" }
  disclaimer: { size: "10px", weight: "400", color: "text-disabled" }
```

---

## Rounded

```yaml
rounded:
  xs: "6px"     # 小 badge、chip
  sm: "10px"    # 小卡片、输入框
  md: "14px"    # 标准卡片
  lg: "20px"    # 大卡片、featured card
  xl: "28px"    # Pill nav 容器
  full: "999px" # 胶囊形按钮、头像
  phone: "44px" # 手机外框（仅 mockup 用）
```

---

## Spacing

```yaml
spacing:
  xxs: "4px"
  xs:  "6px"
  sm:  "8px"
  md:  "12px"
  lg:  "16px"
  xl:  "20px"
  xxl: "24px"
  section: "28px"

  # Layout
  screen-padding: "16px"        # 页面左右内边距
  card-padding: "12px"          # 卡片内边距
  masonry-gap: "8px"            # 瀑布流列间距
  nav-height: "70px"            # 底导航高度（含安全区）
  status-bar: "44px"            # 状态栏 + header 区高度
```

---

## Elevation & Depth

```yaml
elevation:
  card:    "0 2px 8px rgba(0,0,0,0.06)"
  nav:     "0 -2px 16px rgba(0,0,0,0.06)"
  modal:   "0 8px 32px rgba(0,0,0,0.16)"
  fab:     "0 4px 16px rgba(0,0,0,0.16)"
  overlay: "rgba(0,0,0,0.4)"   # 半屏遮罩背景
```

---

## Shapes

- **卡片（Card）** — `rounded.md` (14px)，白底，`elevation.card`
- **Featured Card** — `rounded.lg` (20px)，白底，顶部 4px 区域色条带，`elevation.card`
- **Pill Nav 容器** — `rounded.xl` (28px)，白底，`elevation.nav`，水平 padding 16px，垂直 padding 6px
- **Active Tab Circle** — 34×34px，`rounded.full`，区域色填充，白色图标
- **FAB** — 52×52px，`rounded.full`，区域色填充，白色图标，`elevation.fab` + CSS pulse animation
- **Filter Chip** — `rounded.full`，active=区域色填充/白字；inactive=`border: 1px solid #EDE8E3`/`text-secondary`
- **Primary Button** — 全宽，高度 44px，`rounded.md`，区域色背景，白字，`typography.button`
- **Outlined Button** — 同尺寸，区域色描边，区域色文字，透明背景

---

## Components

### Bottom Pill Navigation
一个圆角矩形容器悬浮在屏幕底部（margin: 0 16px 8px，自动适配安全区）。4 个 Tab 等宽分布。
- **Active:** 34×34px 填充圆（区域色），白色图标/emoji
- **Inactive:** 图标/emoji 14px，`text-disabled`色，无标签文字
- Tab 切换动效：图标淡出（旧）→ 淡入（新），持续 120ms ease

### Masonry Card（首页瀑布流）
两列不等高布局，列间距 8px，左右各 padding 16px。
- 图片区：全宽，高度可变（80px-160px），`rounded.md` 仅上圆角
- 文字区：padding 8px，标题 `body-small` 最多2行，meta `caption`（时间 + 心形数量 in 区域色）

### Triage Result Card（问诊结果）
白底，`rounded.md`，左侧 3px 区域色边框（黄/绿）或红色背景半屏。
- 危险等级 badge：`rounded.full`，对应 `triage-*-bg/text` 色
- 倒计时协议块：`accent-consult` 浅底 `#EEF4F7`，`rounded.sm`

### Event Card（活动卡片）
Featured：顶部 4px 区域色条带，`rounded.lg`，`elevation.card`，内含出席者头像列表（18px 圆形）+ 橙色主按钮。
Standard：`border: 1px solid #EDE8E3`，无顶部条带，偏小。

### Publish Compose（发布页）
单页全屏 bottom sheet（从底部滑入）。内容：内容类型 Segment（全部/日常/成长日历/科普）→ 图片上传区 → 文字输入区 → 发布按钮。无独立页面跳转。

---

## Do's and Don'ts

**Do:**
- 区域色只做点缀（按钮、图标、active状态），不整屏铺色
- 问诊界面用莫兰迪蓝营造冷静感，避免焦虑
- 活力社区感体现在 microcopy，不是视觉噪音
- 卡片阴影保持轻盈（不超过 `elevation.card`）

**Don't:**
- 不在问诊结果页放促销内容或干扰性 CTA
- 不在红色预警状态放「升级服务」入口
- 不让免责声明字体大到干扰阅读主流程
- 不在底导航放 App logo 或品牌图标
