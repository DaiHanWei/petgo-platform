import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../data/timeline_repository.dart';
import '../domain/archive_scope.dart';
import '../domain/timeline_item.dart';
import 'widgets/diary_header.dart';
import 'widgets/timeline_item_tile.dart';

/// 访客只读视图（V1.1.6 Story 2.3 · AD-2 / AD-3 / AD-4）。
///
/// 看**别人的**宠物时渲染这一屏。两种来源共用它（V1.3.0 batch-b1 Story 2.3 · AD-4）：
/// - **分享链接**（`/pet/{token}`，游客可读）—— 它是 Diary 页 [DiaryUserState] 的第五个分支；
/// - **站内**（别人主页的宠物卡，`/pets/{petId}`，仅登录可用）。
///
/// 两者**同一屏、同一份服务端投影**，差别只有两处：打哪个地址（由 [ArchiveScope] 决定）、
/// 以及顶部那条「由 XX 分享」横幅显不显示。**不要为站内再复制一个页面。**
///
/// ## 这一屏是「作者态减法」，不是新画的一套
/// 页头（[DiaryHeader]）与五类条目渲染（[TimelineItemTile]）与作者态**同一批组件**，只是：
/// - 页头传 `readOnly`：**不渲染**编辑铅笔与入口网格两张卡
/// - 不传分享按钮 → 标题行右侧整块消失（访客不得二次转发）
/// - 条目点击按服务端下发的「可否点开」分流
///
/// ⚠️ **2026-08-18 产品决定：访客态先不做日历屏**，本页只有时间线，也就没有视图切换行。
/// 后端的访客日历与某天详情接口已建好且有测试（Story 2.2），是**有意留着的**，
/// 要接回来只需在本页加回切换行与日历组件，服务端零改动。
///
/// ⚠️ **不引入「陪伴天数」**（AD-4）：那是 H5 名片独有的，Diary 页从来没有这个字段。
///
/// ## 🛡 客户端不做任何「访客不该看」的过滤
/// 健康记录与问诊存档**服务端根本没下发**（访客投影层结构上就取不到）。
/// 客户端过滤只是「看不见」，抓包照样拿得到 —— 真正的边界在服务端。
class VisitorArchiveView extends ConsumerStatefulWidget {
  const VisitorArchiveView({super.key, required this.scope, this.analyticsFrom});
  // ⚠️ 这里**刻意没有** `assert(scope.isVisitor)`：const 构造器的断言里既不能调 getter、
  // 也不能读参数对象的字段，三种写法（`isVisitor` / 读字段 / `!= ArchiveScope.me()`）
  // 都会让 `const VisitorArchiveView(...)` 编译不过。作者态走不到这一屏是由**调用方**
  // 保证的（`GrowthArchivePage` 的状态分支 + 站内路由），不是靠这里挡。

  /// 数据作用域：分享 token 态 或 站内 petId 态。
  final ArchiveScope scope;

  /// 从哪个推荐位点进来的（V1.3.0 batch-b1 Story 4.1 · AC7 的 `from`）。
  ///
  /// 非空 → 首帧上报一次 `diary_visitor_viewed`。为空（分享链接落地 / 公开主页的宠物卡
  /// 等既有入口）→ **一条都不报**，本 story 不给既有入口补埋点。
  /// ⚠️ 只喂埋点，不影响任何行为。
  final String? analyticsFrom;

  /// 站内入口的路由前缀。拼 `'$inAppRouteBase/$petId'`。
  ///
  /// ⚠️ 与分享链接落点 `/pet/{token}`（单数、游客可进）**是两条路由，刻意不合并**：
  /// 一条按不可枚举 token 且对未登录开放，一条按 petId 且必须登录 —— 鉴权边界不同。
  static const String inAppRouteBase = '/pets';

  /// go_router 的路由模板（站内入口）。
  static const String inAppRoutePattern = '$inAppRouteBase/:petId';

  @override
  ConsumerState<VisitorArchiveView> createState() => _VisitorArchiveViewState();
}

class _VisitorArchiveViewState extends ConsumerState<VisitorArchiveView> {
  @override
  void initState() {
    super.initState();
    // Story 4.1 AC7：`diary_visitor_viewed`（属性只有 from —— 没有宠物名、没有 petId
    // 之外的任何东西）。⚠️ 放 initState 而不是 build：build 会随每次数据到达重跑，
    // 那样一次浏览会报出三四条。
    final from = widget.analyticsFrom;
    if (from != null) {
      Analytics.capture('diary_visitor_viewed', {'from': from});
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final profileAsync = ref.watch(visitorProfileProvider(widget.scope));
    final stats = ref.watch(visitorStatsProvider(widget.scope)).asData?.value;

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
              // AC4：**站内入口不渲染来源横幅** —— 从别人主页点宠物卡进来时，
              // 「谁分享的」这个前提根本不成立（B1-D1 / AD-4 Rule 5）。
              // 从分享链接进来时照旧显示。
              if (!widget.scope.isInApp) _banner(l10n, profile.ownerNickname),
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
              // ⚠️ **访客态不做日历屏**，只保留时间线 —— 因此这里没有「时间线 / 日历」切换行。
              // 只有一种视图时给个切换器是噪音。
              //
              // 🔴 UI 稿 P1 上**画着** Linimasa / Kalender 两个按钮，所以这里看起来像漏做 ——
              // 不是。2026-08-18 产品决定不做；2026-08-28 对着 P1 逐项核对后**再次确认不做**。
              // 别再当成缺口补上去。
              //
              // 后端的访客日历接口已建好并有测试（Story 2.2）：真要恢复，把切换行与
              // ArchiveCalendar 加回本处即可，服务端无需改动。
              const SizedBox(height: 10),
              _timeline(l10n, profile.name),
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

  Widget _timeline(AppLocalizations l10n, String petName) {
    final async = ref.watch(visitorTimelineProvider(widget.scope));
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
              // 访客看的是别人的宠物 —— 不能沿用主人态「去写第一篇」的引导（2026-09-21 stag 验收）。
              child: Text(l10n.growthArchiveVisitorTimelineEmpty(petName),
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
  /// ✅ 提示样式**用轻提示（toast）**——2026-08-18 产品确认，不再等 UI 稿。
  /// 理由：这是一次「点了没反应」的解释，不是需要用户做决定的事；
  /// 弹窗要多一次点击才能关掉，对一个只是路过看看的访客是打扰。
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
