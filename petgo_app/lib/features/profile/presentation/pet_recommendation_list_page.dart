import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../data/pet_recommendation_repository.dart';
import 'pet_recommendation_list_controller.dart';
import 'widgets/recommended_pet_card.dart';

/// 全屏推荐集合页（V1.3.0 batch-b1 Story 4.3 · AC2/AC3/AC4 · UI 稿 E2/E3）。
///
/// <h2>卡片与 Story 4.1 是**同一个组件**</h2>
/// 2 列网格、卡片样式、点击落点、埋点属性形状全部复用 [RecommendedPetCard] ——
/// 只有埋点的 `from` 不同（AC5：`explore_grid`）。另画一套的表现是两处卡片慢慢长歪。
///
/// <h2>🛡 三种失败/空的形态，各自不同（F13 / NFR-10 / AC4）</h2>
/// <table>
///   <tr><th>情形</th><th>这一屏长什么样</th></tr>
///   <tr><td>第一页就失败</td><td>整屏错误态 + 重试按钮（此时本来也没内容可保留）</td></tr>
///   <tr><td><b>翻页失败</b></td><td><b>已加载的宠物一个不动</b>，底部一行「点击重试」</td></tr>
///   <tr><td>池子为空</td><td>空态（<b>不是</b>错误态 —— 新站本来就没人满足门槛）</td></tr>
/// </table>
/// 🔴 把翻页失败做成整屏错误态是 F13 首先要避免的事：用户已经滚过十几只宠物，
/// 一次网络抖动把它们全换成一个「加载失败」，等于惩罚他往下滚。
///
/// <h2>⚠️ 路由**不在**受控前缀里</h2>
/// 本 story 的入口来自 Diary 的登录态分支，但 **Story 4.4 会从首页加入口，而首页游客也能进**。
/// 塞进 `_controlledLocations` 的话 4.4 那个入口对游客就是一条死路（redirect 回 `/home`）。
/// 真正的边界在服务端那条「仅登录可用」的规则上，以及宠物卡自己的 `requireLogin`。
class PetRecommendationListPage extends ConsumerStatefulWidget {
  const PetRecommendationListPage({super.key});

  /// 路由路径。⚠️ 与 `app_router.dart` 的注册值同源，别在别处写字面量。
  ///
  /// 🔴 **不是** `/pets/recommendations`：那会被 `/pets/:petId` 抢先匹配成
  /// petId=`recommendations`（站内访客视图那条路由），表现是点「查看全部」落到一个
  /// 解析不出 petId 的访客页。
  static const String routePath = '/pet-recommendations';

  @override
  ConsumerState<PetRecommendationListPage> createState() => _PetRecommendationListPageState();
}

class _PetRecommendationListPageState extends ConsumerState<PetRecommendationListPage> {
  final ScrollController _scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scroll.removeListener(_onScroll);
    _scroll.dispose();
    super.dispose();
  }

  /// 触底前 400px 就开始取下一页 —— 等真的见底再取，用户会先看到一段空白。
  void _onScroll() {
    if (!_scroll.hasClients) {
      return;
    }
    if (_scroll.position.pixels < _scroll.position.maxScrollExtent - 400) {
      return;
    }
    _startLoadMore();
  }

  /// 🔴 **屏幕没被填满时也要续抓**（code-review 2026-09-15）。
  ///
  /// 服务端完全可能回「1 只 + hasMore=true」（其余候选被拉黑 / 注销 / 没头像过滤掉了）。
  /// 那时 `maxScrollExtent` 是 0：用户滚不动，滚动回调**一次都不会发**，
  /// 池子后面的宠物永远刷不出来 —— 表现是「集合页里就那么一只，明明还有」。
  /// 所以每帧渲染完都回头看一眼：还没填满且还有更多，就自己再抓一轮。
  ///
  /// ⚠️ 这条路径**必须有上限**（`canAutoLoad`），否则服务端连续给空页时它会变成
  /// 一个用户什么都没做却不停发请求的死循环。
  void _maybeAutoLoadMore() {
    final notifier = ref.read(petRecommendationListProvider.notifier);
    if (!notifier.canAutoLoad || notifier.loadMoreFailed) {
      return; // 失败了就等用户点那个重试，别偷偷重试
    }
    // 判据与触底那条**逐字相同**：滚不动（maxScrollExtent 只有十几像素）时，
    // 它天然成立 —— 这正是「屏幕没填满」要覆盖的情形。
    // ⚠️ 别写成 `maxScrollExtent <= 0`：一行卡片的网格常常能滚个十几像素，
    //    那一点余量既不足以让用户滑到触发线，也让「== 0」判不出来（实测 15.8px）。
    // 🛡 这条不会变成「用户停在底部就一直抓」：真抓到东西之后内容变长，
    //    pixels 就不在触发窗口里了；抓不到东西的那一路由 canAutoLoad 收口。
    final atTrigger = !_scroll.hasClients ||
        _scroll.position.pixels >= _scroll.position.maxScrollExtent - 400;
    if (!atTrigger) {
      return;
    }
    _startLoadMore();
  }

  /// 取下一页的**唯一出口**（触底 / 没填满 / 底部重试三处共用）。
  ///
  /// ⚠️ 两次 `setState` 是必须的：控制器的 `isLoadingMore` / `loadMoreFailed` 是普通字段，
  /// 改它们不会触发重建；而失败重试时新旧 `AsyncData` 可能**值相等**（同一个 page 实例），
  /// Riverpod 会跳过通知 —— 那样底部既不转圈、再失败也没有任何反馈。
  void _startLoadMore() {
    final notifier = ref.read(petRecommendationListProvider.notifier);
    final page = ref.read(petRecommendationListProvider).value;
    if (page == null || !page.hasMore || notifier.isLoadingMore) {
      return;
    }
    setState(() {});
    notifier.loadMore().whenComplete(() {
      if (mounted) {
        setState(() {});
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(petRecommendationListProvider);
    final controller = ref.read(petRecommendationListProvider.notifier);
    final page = async.value;
    // 渲染完回头看一眼「屏幕填满了吗」—— 没填满且还有更多就自己续抓（见 _maybeAutoLoadMore）。
    if (page != null && page.hasMore) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          _maybeAutoLoadMore();
        }
      });
    }

    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        scrolledUnderElevation: 0,
        // UI 稿 E2：集合页的标题是「其他宠物」—— 分区标题那句「认识别人家的毛孩子」
        // 是一段邀约，放在整页 AppBar 上读起来像一句口号。
        title: Text(l10n.petRecommendListTitle, style: AppTypography.title),
      ),
      body: _body(l10n, async, page, controller),
    );
  }

  Widget _body(AppLocalizations l10n, AsyncValue<RecommendedPetPage> async,
      RecommendedPetPage? page, PetRecommendationListController controller) {
    // 🔴 F13：先看有没有已加载内容。有就继续显示，绝不因为增量失败把它换掉。
    if (async.hasError && page == null) {
      return EmptyState(
        key: const ValueKey('petRecommendListError'),
        title: l10n.petRecommendErrorTitle,
        message: l10n.petRecommendErrorBody,
        icon: Icons.cloud_off_rounded,
        actionLabel: l10n.commonRetry,
        onAction: controller.retryFirstPage,
      );
    }
    if (page == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (page.items.isEmpty && page.hasMore && controller.canAutoLoad) {
      // 🔴 **空 items + hasMore 不是空态**（code-review 2026-09-15）：
      //    服务端在「这一页的宠物全被过滤掉」时就是这么回的，后面还有。
      //    直接摆空态的表现是「永久显示『还没有宠物』，而且再也翻不动」——
      //    因为空态里既没有网格也没有滚动，没有任何东西能触发下一页。
      //    所以这里先转圈，由 _maybeAutoLoadMore 把后面的页翻过来。
      // ⚠️ 转圈**只在还能自动续抓时**给：续抓额度用完还是空的，那就按空态处理
      //    （对这位查看者来说池子实际上是空的）—— 否则就是一个永远转不完的圈。
      return const Center(child: CircularProgressIndicator());
    }
    if (page.items.isEmpty) {
      // AC4 空态：**不是**错误态 —— 新站本来就没有宠物满足「有头像 + 公开记录≥3 + 近 14 天活跃」。
      // ⚠️ 刻意没有 CTA：用户没法让别人去发帖。
      return EmptyState(
        key: const ValueKey('petRecommendListEmpty'),
        title: l10n.petRecommendEmptyTitle,
        message: l10n.petRecommendEmptyBody,
        icon: Icons.pets_rounded,
      );
    }
    return GridView.builder(
      key: const ValueKey('petRecommendListGrid'),
      controller: _scroll,
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        crossAxisSpacing: AppSpacing.sm,
        mainAxisSpacing: AppSpacing.sm,
        childAspectRatio: 0.72,
      ),
      // 末尾那一格是「底部状态」（加载中 / 重试 / 到底了），所以 +1。
      itemCount: page.items.length + 1,
      itemBuilder: (context, i) {
        if (i == page.items.length) {
          return _footer(l10n, controller);
        }
        return RecommendedPetCard(
            pet: page.items[i], from: kPetRecommendFromExploreGrid);
      },
    );
  }

  /// 网格末尾那一格：翻页失败给重试、正在翻页给转圈、到底了什么都不给。
  ///
  /// ⚠️ 放在网格里（而不是网格下面）是为了让它跟着一起滚 ——
  /// 悬在外面的一行会在「到底了」时留下一条空带。
  Widget _footer(AppLocalizations l10n, PetRecommendationListController controller) {
    if (controller.loadMoreFailed) {
      // 🛡 F13：上面那些宠物一个没动，只有这一格是错误的。
      return Center(
        child: TextButton(
          key: const ValueKey('petRecommendLoadMoreRetry'),
          // ⚠️ 走同一个出口（而不是直接 controller.loadMore）：否则重试时底部不转圈、
          //    再失败一次也没有任何反馈（新旧 AsyncData 值相等，Riverpod 不通知）。
          onPressed: _startLoadMore,
          child: Text(l10n.petRecommendLoadMoreRetry,
              textAlign: TextAlign.center, style: AppTypography.micro),
        ),
      );
    }
    if (controller.isLoadingMore) {
      return const Center(
          key: ValueKey('petRecommendLoadingMore'),
          child: SizedBox(
              width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)));
    }
    // 🔴 **没在取就不要转圈**（哪怕 hasMore=true）：一个不对应任何请求的转圈
    //    既误导用户「还在加载」，也让任何 `pumpAndSettle` 永远等不到静止。
    return const SizedBox.shrink();
  }
}
