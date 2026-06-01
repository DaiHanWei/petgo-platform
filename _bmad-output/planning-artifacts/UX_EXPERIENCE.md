---
title: "PetGo V1.0 Experience Design"
status: draft
created: 2026-05-29
updated: 2026-06-01
platform: iOS · Android
sources:
  - V1版本20260601/V1-0-0PRD20260601.md
  - V1版本20260601/PetGo_V1_全屏效果图.html
---

# PetGo — EXPERIENCE.md

Visual identity tokens referenced as `{colors.*}`, `{typography.*}`, `{rounded.*}`, `{spacing.*}` from DESIGN.md.

---

## Foundation

**Form-factor:** Native mobile — iOS + Android. Portrait-only V1. Standard mobile safe areas apply (status bar top, home indicator bottom).

**UI system:** Custom design system per DESIGN.md. No third-party component library imposed.

**Visual identity reference:** DESIGN.md is the single source of truth for colors, typography, spacing, elevation, and component visual specs. This document specifies behavior only.

---

## Information Architecture

### Navigation Model
Bottom Tab Bar — **5 个位置**，中间为凸起的「＋」发布按钮（PRD FR-19）。一级页面常显，进入全屏 modal / 二级页面（问诊详情、档案编辑等）时隐藏。**首页是唯一无需登录可访问的 Tab**；其余四个未登录点击触发登录引导（FR-0C）。

| 位 | Tab | Icon | Zone accent | Destination | 未登录 |
|----|-----|------|-------------|-------------|--------|
| 1 | 首页 | 🏠 | `{colors.accent-growth}` | Community content feed (FR-17) | ✅ 可访问 |
| 2 | 成长档案 | 🐾 | `{colors.accent-growth}` | 当前用户宠物档案 / 成长时间线 (FR-37) | ❌ FR-0C |
| 3 | ＋ 发布 | ＋ | active Tab zone accent | Publish Compose bottom sheet (FR-12) | ❌ FR-0C |
| 4 | 问诊 | 🩺 | `{colors.accent-consult}` | Consultation dual entry | ❌ FR-0C |
| 5 | 我的 | 👤 | `{colors.accent-growth}` | User profile / settings | ❌ FR-0C |

**Raised 「＋」 button (位 3):** 视觉上凸起于 Tab Bar，区别于其他 4 个平级 Tab；全局可见。点击打开 Publish Compose bottom sheet。其颜色随 active Tab 的区域色切换。

### Screen Map

```
🏠 首页
  ├── Community Feed (default, masonry waterfall)
  │   ├── Tab: 全部 / 日常分享 / 成长日历 / 科普
  │   └── Post Detail Page (FR-28)
  └── 🔔 通知中心 (铃铛, FR-34)

🐾 成长档案
  ├── Pet Profile (Growth Timeline, 时间倒序)
  │   ├── 快乐时刻条目 + 健康事件 (问诊存档, FR-16)
  │   └── 宠物名片 Preview + Share FAB → H5 link
  └── 宠物状态快捷编辑入口 (FR-21)

＋ 发布 (center raised button)
  └── Publish Compose (bottom sheet) — 日常分享 / 专业知识科普 / 成长日历快乐时刻

🩺 问诊
  ├── Consultation Home (dual entry: AI 智能分诊 / 在线兽医)
  ├── AI 问诊 Flow
  │   ├── Upload Page (photo + text)          ← V1 仅图文，无视频
  │   ├── Analyzing State (loading)
  │   └── Result Page (green / yellow / red)
  │       └── Red: Half-Screen Alert → confirm dialog
  │       └── Save to Records (optional, user-confirmed)
  ├── 在线兽医 Chat Page (text / image / video, by 腾讯云 IM)
  └── Consultation History List

👤 我的
  ├── User Profile Page
  ├── 我的发布 (三类内容)
  └── Settings (语言 id/en, 账号管理 / 注销)
```

---

## Voice and Tone

**Register:** 活力社区感——年轻、热情、有温度，像一个爱宠的朋友在说话。不说官方套话。

**Microcopy examples:**

| Context | Copy |
|---------|------|
| 首页空状态 | "还没有内容，快来晒出你的毛孩子！🐾" |
| 问诊历史空状态 | "还没有问诊记录，一切正常就是最好的消息 🥰" |
| AI分析中 | "正在为你的毛孩子分析，稍等一下～" |
| 分享名片引导 | "分享给朋友，让大家认识 [宠物名]！" |
| 红色预警 | "⚠️ 请立即带 [宠物名] 就医" |
| 免责声明 | "本建议仅供参考，最终决策权归您" |
| 稍后处理确认 | "确认已了解风险？" |

**Tone rules:**
- 用 emoji 增加温度，但每条 copy 最多 1 个
- 问诊结果页保持克制，不用感叹号（避免加重焦虑）
- 红色预警文案简短、直接、无歧义

---

## Component Patterns

### Bottom Tab Bar
See DESIGN.md `## Components > Bottom Tab Bar` for visual spec.

**Behavior:**
- Tab 切换触发左右 Slide 动效：新 Tab 从对应方向滑入（左→右 or 右→左），持续 250ms ease-in-out
- Active Tab zone accent 随 Tab 切换即时更新
- 中间凸起「＋」颜色随 active Tab 区域色切换，带 120ms color transition
- 进入二级页面（问诊详情、档案编辑等）时 Tab Bar 隐藏（PRD FR-19）

### FAB (Global Publish Button)
- 始终悬浮在 Tab Bar 上方右下角，margin-right 16px，margin-bottom = nav height + 12px
- CSS pulse animation: `scale(1.0) → scale(1.04)`，2s infinite，仅在用户≥30秒未操作时激活（引导发布）
- 点击 → Publish Compose 从底部滑入（`presentation: .sheet` iOS / `bottomSheetDialog` Android）
- 仅在全屏 modal 打开时隐藏

### Masonry Waterfall (首页)
- 2列等宽，column gap: `{spacing.masonry-gap}` (8px)，padding: `{spacing.screen-padding}` (16px)
- 图片区高度由内容决定（不裁切），最小 80px，最大 200px
- 无限滚动加载（load more when ≤3 cards from bottom）
- 下拉刷新：标准 pull-to-refresh，区域色 loading indicator

### Tab Row (首页 content tabs)
- 横向 scroll，4 tabs: 全部 / 日常分享 / 成长日历 / 科普
- Active: `{colors.accent-growth}` underline 2px + text color
- Tab 切换：内容区 cross-fade，不 slide（避免与底导航 slide 冲突）
- 成长日历 Tab：仅显示有宠物档案的帖子；空状态见 Voice & Tone

### Triage Result Cards
- **Green:** Left border 3px `{colors.triage-green-text}`，badge `{colors.triage-green-bg/text}`
- **Yellow:** Left border 3px `{colors.triage-yellow-text}`，badge `{colors.triage-yellow-bg/text}`；Observation Protocol block `{colors.accent-consult}` light tint bg
- **Red:** See Red Alert Half-Screen below

---

## State Patterns

### Empty States
Each empty state: centered illustration area (emoji-based, no external image) + headline + subtext + optional CTA button.

| Screen | Headline | CTA |
|--------|----------|-----|
| 首页 Feed | "快来晒出你的毛孩子！🐾" | "发布第一条内容" |
| 问诊历史 | "还没有问诊记录" | "开始问诊" |
| 成长档案 (我的) | "还没有记录，快来留下第一个瞬间" | "立即记录" |

### Loading States
- **Feed loading:** Skeleton cards (gray shimmer placeholders, same masonry layout)
- **AI analyzing:** Full-screen centered animation + "正在为你的毛孩子分析，稍等一下～" text，`{colors.accent-consult}` spinner
- **Image upload:** Progress bar in `{colors.accent-consult}` within the upload area

### Error States
- **Network error:** Inline banner top of screen: "网络不稳定，请检查连接" + 重试 button，自动消失5s
- **Upload failed:** Toast at bottom: "上传失败，请重试" 3s auto-dismiss
- **Vet unavailable:** Card state: "当前兽医较忙，预计等待 X 分钟" + "继续等待" / "改用 AI 问诊"

### Triage Red Alert — Half-Screen Overlay (FR-3)
Critical interaction. Triggered when AI returns red-level assessment.

**States in sequence:**
1. **Lock phase (0–5s):** Half-screen slides up from bottom. Red background `{colors.triage-red-bg}`. Content: ⚠️ icon (large, white) + "请立即带 [宠物名] 就医" (display size, white) + subtitle "AI 判断为紧急情况，建议立即前往宠物医院". Two buttons DISABLED for 5 seconds (countdown visible on button).
2. **Unlocked (5s+):** Buttons become active:
   - Primary: "去导航" → triggers system confirm dialog: "是否打开地图前往附近宠物医院？" [确认] [取消]
   - Secondary: "稍后处理" → triggers in-app confirm: "确认已了解风险？" [已了解，暂不就医] [返回]
3. **Dismissed:** Half-screen slides down. Result page remains visible. No vet consult CTA shown on result page (per PRD FR-3).

**Accessibility:** Red background paired with ⚠️ icon + large text — does not rely on color alone.

---

## Interaction Primitives

### Navigation Transitions
- **Tab switch (Bottom Nav):** Horizontal slide. Left Tab → right Tab: new screen slides in from right. Right Tab → left Tab: new screen slides from left. Duration 250ms, ease-in-out.
- **Push (drill-down):** Standard iOS-style right-to-left push. Android: left-to-right slide-in.
- **Modal / Bottom Sheet:** Slides up from bottom. Dismiss: drag down or tap backdrop. Duration 300ms spring.
- **Tab content (首页 Tab Row):** Cross-fade only (not slide, to avoid conflict with bottom nav slide).

### Gestures
- **Pull-to-refresh:** Standard, active on feed screens
- **Long-press post:** Context menu (举报 / 分享)
- **Swipe-to-dismiss:** Bottom sheets, modals
- **Tap photo in masonry:** Expand to full-screen photo viewer (standard lightbox)

### Micro-interactions
- **FAB pulse:** `scale(1.0) → scale(1.04)`, 2s ease-in-out infinite, only when idle ≥30s
- **Triage result reveal:** Result card fades in + badge bounces once (scale 0.8 → 1.1 → 1.0, 300ms)
- **Pet card share FAB:** On first visit: scale pulse from 0.9 → 1.0 with ring ripple. Subsequent visits: static.
- **Tab active circle:** Scale 0.7 → 1.0 when tab becomes active, 150ms spring

---

## Accessibility Floor

- **Color contrast:** All text/icon on `{colors.background}` meets WCAG AA (4.5:1 minimum). Triage states use icon + text + color — never color alone.
- **Touch targets:** Minimum 44×44pt for all interactive elements (iOS HIG / Material baseline).
- **Disclaimer text:** Even at `{typography.micro}` (10px), must maintain 3:1 contrast on its background.
- **Red alert:** Half-screen alert is announced to screen readers as "urgent alert, role=alertdialog" (iOS) / `AccessibilityLiveRegion.ASSERTIVE` (Android). 播报文案需提供 id/en 双语（en: "urgent alert" / id: "peringatan darurat"）。
- **Dynamic type:** Body and below scales with system font size setting (up to 3 steps). Headers capped to prevent layout breakage.
- **Bilingual (V1):** App 支持印尼语（id）和英语（en）双语（PRD FR-27）。首次启动跟随设备语言，设备为其他语言回退英语；用户可在「我的 → 语言设置」手动切换，即时生效。所有 UI 文案 / 系统提示 / 错误信息均需提供 id + en 两套；UGC 内容不受语言设置限制。

---

## Key Flows

### KF-1: Putri — AI 问诊 + 升级兽医咨询 (UJ-1)
1. **Entry:** Bottom nav → 🩺 问诊 Tab (zone shifts to Morandi blue)
2. **Dual entry page:** Two equal cards — "AI 智能分诊" + "在线兽医"，均标注"免费"
3. **Tap AI 智能分诊** → Upload page slides in (push)
4. **Upload:** Photo picker (system sheet, V1 仅图片) + text field for symptom description in Bahasa Indonesia
5. **Submit** → Analyzing state (full-screen, Morandi blue spinner, "正在分析中～")
6. **Result page:** Yellow badge + "非紧急" large + Observation Protocol card + Medication card. Soft CTA bottom: "想要更个性化的建议？咨询兽医" (outlined)
7. **Tap CTA** → Vet chat page (push). Chat interface with "免费" badge visible. 支持发送文字 / 图片 / 视频（腾讯云 IM）。
8. **Post-consult:** "是否将本次咨询存入 Oyen 的档案？" bottom sheet. [存入] [跳过].
9. **Climax:** Consultation saved, badge "已存入档案 ✓" appears briefly.

**Red path (edge case):** Result = Red → Half-Screen Alert (see State Patterns). No CTA to vet consult.

---

### KF-2: Aurel — 发布成长记录 + 分享宠物名片 (UJ-2)
1. **Entry:** Global FAB (＋) → Publish Compose bottom sheet (slides up)
2. **Compose:** Segment selector → tap "成长日历" → select "快乐时刻" → upload photo from camera roll → add caption
3. **Publish** → bottom sheet dismisses → new card appears at top of 成长日历 Tab masonry (insertion animation: fade-in + scale 0.95→1.0)
4. **Pet Profile entry (我的 Tab):** Growth timeline shows new record at top
5. **Share FAB (animated pulse on first visit):** Tap → "分享 Mochi 的宠物名片" sheet → generate H5 link → system share (WhatsApp / Instagram / copy link)
6. **Climax (external):** Friend opens H5 link → sees preview (last 5 records) → "下载 App 查看完整成长故事" CTA
7. **Return loop:** Friend downloads → creates own pet → natural network growth

---

## Responsive & Platform

**iOS specifics:**
- Status bar: system default light content on `{colors.background}`
- Safe area: home indicator bottom padding applied to Bottom Tab Bar
- Sheet presentation: `.sheet` for Publish Compose; `.fullScreenCover` not used
- Haptics: `UIImpactFeedbackGenerator` on FAB tap, triage result reveal

**Android specifics:**
- Status bar: transparent with dark icons (light mode)
- Bottom sheet: `BottomSheetDialogFragment`
- Navigation bar: edge-to-edge, Bottom Tab Bar sits above system nav bar
- Ripple: MaterialRipple on all tappable cards; suppress default ripple on FAB (custom pulse animation)

**Dark mode:** `[ASSUMPTION: V1 light-mode only; dark mode deferred to V2]`
