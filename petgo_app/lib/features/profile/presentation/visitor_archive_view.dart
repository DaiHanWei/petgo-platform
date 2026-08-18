import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../data/timeline_repository.dart';
import '../domain/archive_scope.dart';
import '../domain/timeline_item.dart';
import 'widgets/archive_calendar.dart';
import 'widgets/diary_header.dart';
import 'widgets/timeline_item_tile.dart';

/// 访客只读视图（V1.1.6 Story 2.3 · AD-2 / AD-3 / AD-4）。
///
/// 拿着分享 token 看**别人的**宠物时渲染这一屏。它是 Diary 页
/// [DiaryUserState] 的第五个分支，不是另一个页面。
///
/// ## 这一屏是「作者态减法」，不是新画的一套
/// 页头（[DiaryHeader]）· 视图切换 · 五类条目渲染（[TimelineItemTile]）· 日历
/// （[ArchiveCalendar]）全部与作者态**同一批组件**，只是：
/// - 页头传 `readOnly`：**不渲染**编辑铅笔与入口网格两张卡
/// - 不传分享按钮 → 标题行右侧整块消失（访客不得二次转发）
/// - 日历不传「+」回调 → 空白格不再是发布入口
/// - 条目点击按服务端下发的「可否点开」分流
///
/// ⚠️ **不引入「陪伴天数」**（AD-4）：那是 H5 名片独有的，Diary 页从来没有这个字段。
///
/// ## 🛡 客户端不做任何「访客不该看」的过滤
/// 健康记录与问诊存档**服务端根本没下发**（访客投影层结构上就取不到）。
/// 客户端过滤只是「看不见」，抓包照样拿得到 —— 真正的边界在服务端。
class VisitorArchiveView extends ConsumerStatefulWidget {
  const VisitorArchiveView({super.key, required this.token});

  /// 分享 token。
  final String token;

  @override
  ConsumerState<VisitorArchiveView> createState() => _VisitorArchiveViewState();
}

enum _VisitorView { timeline, calendar }

class _VisitorArchiveViewState extends ConsumerState<VisitorArchiveView> {
  _VisitorView _view = _VisitorView.timeline;

  ArchiveScope get _scope => ArchiveScope.visitor(widget.token);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final profileAsync = ref.watch(visitorProfileProvider(widget.token));
    final stats = ref.watch(visitorStatsProvider(widget.token)).asData?.value;

    return Scaffold(
      backgroundColor: AppColors.cream,
      body: SafeArea(
        child: profileAsync.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          // 失效（token 不存在 / 档案已删 / 主人注销或被封）走同一条 —— 服务端不区分，这里也不猜。
          error: (_, _) => _Gone(key: const ValueKey('visitorGone'), l10n: l10n),
          data: (profile) => ListView(
            padding: const EdgeInsets.fromLTRB(AppSpacing.lg, 8, AppSpacing.lg, 40),
            children: [
              _backRow(context),
              _banner(l10n, profile.ownerNickname),
              const SizedBox(height: 12),
              DiaryHeader(
                profile: profile.header,
                // 🛡 只读：编辑铅笔与入口网格两张卡整块不渲染。
                readOnly: true,
                // titleAction 不传 → 分享按钮整块消失（访客不得二次转发）。
                happyCount: stats?.happyMomentCount,
                consultCount: stats?.consultCount,
                milestoneCompleted: stats?.milestoneCompleted,
                milestoneTotal: stats?.milestoneTotal,
                // 里程碑进度条只展示，不跳转（跳的是当前登录用户自己的里程碑页）。
              ),
              _viewToggleRow(l10n),
              const SizedBox(height: 10),
              if (_view == _VisitorView.timeline)
                _timeline(l10n, profile.name)
              else
                ArchiveCalendar(
                  scope: _scope,
                  onOpenDay: (date) => context.push(
                      '/pet/${widget.token}/day?date=${date.year}-${_two(date.month)}-${_two(date.day)}'),
                  // 🛡 不传 onAddOnDate：访客没有任何写入能力（AD-2 Rule 5）。
                ),
            ],
          ),
        ),
      ),
    );
  }

  /// 返回（UI 稿 P1 的 `.ro-titlerow` 左侧箭头）。
  ///
  /// 🔴 **这是访客回到 App 的唯一可见出口**：访客视图在 Tab 之外，没有底部导航。
  /// 缺了它，从 WhatsApp 点链接进来的人就落进一条死路 ——
  /// 而这条链路的全部意义正是把人**引进** App（2026-08-18 L2 实测补）。
  ///
  /// 栈里没有上一页时（例如深链直达）回落到首页，而不是让返回变成空操作。
  Widget _backRow(BuildContext context) => Align(
        alignment: Alignment.centerLeft,
        child: IconButton(
          key: const ValueKey('visitorBackButton'),
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints(minWidth: 40, minHeight: 40),
          icon: const Icon(Icons.arrow_back, size: 22, color: AppColors.ink),
          onPressed: () {
            if (context.canPop()) {
              context.pop();
            } else {
              context.go('/home');
            }
          },
        ),
      );

  /// 顶部来源横幅：「由 {昵称} 分享 · 仅可查看」（UI 稿 P1 的 `.ro-banner`）。
  /// 昵称查不到时整条不渲染 —— 比渲染一句「由 分享」体面。
  Widget _banner(AppLocalizations l10n, String? ownerNickname) {
    if (ownerNickname == null || ownerNickname.isEmpty) return const SizedBox.shrink();
    return Container(
      key: const ValueKey('visitorSharedBanner'),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.line),
      ),
      child: Row(
        children: [
          const Icon(Icons.visibility_outlined, size: 15, color: AppColors.ink2),
          const SizedBox(width: 7),
          Expanded(
            child: Text(
              l10n.visitorSharedBanner(ownerNickname),
              style: const TextStyle(fontSize: 12, color: AppColors.ink2),
            ),
          ),
        ],
      ),
    );
  }

  /// 视图切换：与作者态同样的两个等宽按钮（AD-4：印尼语是 Linimasa，不是 Timeline）。
  Widget _viewToggleRow(AppLocalizations l10n) => Row(
        children: [
          Expanded(
            child: _toggleBtn('⏱ ${l10n.growthArchiveViewTimeline}',
                _view == _VisitorView.timeline, () => _switchView(_VisitorView.timeline),
                const ValueKey('visitorViewTimeline')),
          ),
          const SizedBox(width: 7),
          Expanded(
            child: _toggleBtn('📅 ${l10n.growthArchiveViewCalendar}',
                _view == _VisitorView.calendar, () => _switchView(_VisitorView.calendar),
                const ValueKey('visitorViewCalendar')),
          ),
        ],
      );

  void _switchView(_VisitorView to) {
    if (_view == to) return;
    setState(() => _view = to);
  }

  Widget _toggleBtn(String label, bool on, VoidCallback onTap, Key key) => GestureDetector(
        key: key,
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 9),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: on ? AppColors.mint : AppColors.card,
            borderRadius: BorderRadius.circular(10),
            border: on ? null : Border.all(color: AppColors.line, width: 1.5),
          ),
          child: Text(label,
              style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: on ? AppColors.onAccent : AppColors.ink2)),
        ),
      );

  Widget _timeline(AppLocalizations l10n, String petName) {
    final async = ref.watch(visitorTimelineProvider(widget.token));
    return async.when(
      loading: () => const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (_, _) => Padding(
        key: const ValueKey('visitorTimelineError'),
        padding: const EdgeInsets.symmetric(vertical: 30),
        child: Center(
          child: Text(l10n.growthLoadFailed,
              style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
        ),
      ),
      data: (page) {
        if (page.items.isEmpty) {
          return Padding(
            key: const ValueKey('visitorTimelineEmpty'),
            padding: const EdgeInsets.symmetric(vertical: 30),
            child: Center(
              child: Text(l10n.growthArchiveTimelineEmpty(petName),
                  style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
            ),
          );
        }
        return Column(
          children: [
            for (int i = 0; i < page.items.length; i++)
              TimelineItemTile(
                item: page.items[i],
                petName: petName,
                thumbIndex: i,
                onTap: _tapFor(page.items[i]),
                // 🛡 徽章不可点：它在作者态跳的是当前登录用户自己的里程碑页。
              ),
          ],
        );
      },
    );
  }

  /// 条目点击分流（AD-3 Rule 1/2）。
  ///
  /// - **可点开** → 进既有内容详情页（零新增暴露面；未登录用户在该页的点赞/评论仍走既有登录引导）
  /// - **不可点开**（私密）→ **不跳转**，给一句解释性提示；私密内容因此始终不越出链接边界
  ///
  /// 🛡 「可否点开」是**服务端算好的结论**（可见范围公开 且 状态已发布），
  /// 这里只照做、不自己再推一遍 —— 判定散到两处，写漏的那一次是静默的。
  /// `openable` 为 null 同样视作不可点（fail-closed）。
  ///
  /// 🛡 其余类别（里程碑 banner / 身份证 / 万一漏网的健康条目）在访客态**一律不可点**：
  /// 它们在作者态跳的都是「当前登录用户自己的」页面，访客点进去要么看到自己的宠物、要么直接出错。
  VoidCallback? _tapFor(TimelineItem item) {
    final isHappy = item.resolvedType == TimelineItemType.happyMoment ||
        item.resolvedType == TimelineItemType.happyMomentMilestone;
    if (!isHappy) return null;
    if (item.openable == true && item.postId != null) {
      return () => context.push('/content/${item.postId}');
    }
    return () => showAppToast(context, AppLocalizations.of(context).visitorPrivateItemNotice);
  }

  static String _two(int n) => n.toString().padLeft(2, '0');
}

/// 链接失效（token 不存在 / 档案已删 / 主人注销或被封）。
///
/// 🛡 **四种情况共用这一屏**，与服务端同口径 —— 分开写文案等于告诉扫描者
/// 「这个 token 曾经存在」或「这个人被封了」。
class _Gone extends StatelessWidget {
  const _Gone({super.key, required this.l10n});

  final AppLocalizations l10n;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Text(
            l10n.growthLoadFailed,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 14, color: AppColors.ink2),
          ),
        ),
      );
}
