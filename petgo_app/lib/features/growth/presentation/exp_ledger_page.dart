import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../notify/domain/notification_grouping.dart';
import '../data/growth_repository.dart';
import '../domain/growth_models.dart';
import 'growth_ledger_tile.dart';

/// EXP 明细页（V1.4.0 ① Story 1.5 · AC5，UI 稿 B6 ~ B8）。路由 `/me/growth/history`。
///
/// 按 **WIB 自然日**五段分组（复用通知中心 `groupOf` 与 `notifyGroup*` 文案，`toLocal` 传 UTC+7）；游标分页，
/// 列表末尾的加载行一出现就拉下一页；加载更多失败显示可点的重试行，已加载条目不丢。列表状态由页面持有（离开即释放）。
class ExpLedgerPage extends ConsumerStatefulWidget {
  const ExpLedgerPage({super.key, this.now});

  static const location = '/me/growth/history';

  /// 测试注入「现在」；生产为 null（取 `DateTime.now()`）。
  final DateTime Function()? now;

  @override
  ConsumerState<ExpLedgerPage> createState() => _ExpLedgerPageState();
}

class _ExpLedgerPageState extends ConsumerState<ExpLedgerPage> {
  final List<GrowthLedgerEntry> _items = [];
  String? _cursor;
  bool _hasMore = true;
  bool _loading = false;
  bool _firstLoadFailed = false;
  bool _moreFailed = false;
  bool _loadedOnce = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    if (_loading) return;
    setState(() {
      _loading = true;
      _moreFailed = false;
      _firstLoadFailed = false;
    });
    try {
      final page = await ref.read(growthRepositoryProvider).fetchLedger(cursor: _cursor);
      if (!mounted) return;
      setState(() {
        _items.addAll(page.items);
        _cursor = page.nextCursor;
        // 空页却带游标（异常）→ 停止翻页，避免加载行反复触发请求。
        _hasMore = page.nextCursor != null && page.items.isNotEmpty;
        _loadedOnce = true;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        if (_loadedOnce) {
          _moreFailed = true; // 同一游标重试，已加载的不丢
        } else {
          _firstLoadFailed = true;
        }
      });
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.growthExpHistoryTitle)),
      body: _body(l10n),
    );
  }

  Widget _body(AppLocalizations l10n) {
    if (!_loadedOnce) {
      if (_firstLoadFailed) {
        return EmptyState(
          key: const ValueKey('growthLedgerError'),
          icon: Icons.cloud_off_outlined,
          title: l10n.growthExpLoadFailed,
          message: l10n.feedErrorBody,
          actionLabel: l10n.notificationLoadRetry,
          onAction: _load,
        );
      }
      return const Center(child: CircularProgressIndicator());
    }
    if (_items.isEmpty) {
      return EmptyState(
        key: const ValueKey('growthLedgerEmpty'),
        icon: Icons.bolt,
        iconBackground: AppColors.cream2,
        title: l10n.growthExpEmptyTitle,
        message: l10n.growthExpEmptyBody,
      );
    }
    final rows = _rows(l10n);
    return ListView.builder(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge, vertical: 8),
      itemCount: rows.length + (_hasMore || _moreFailed ? 1 : 0),
      itemBuilder: (context, i) {
        if (i < rows.length) return rows[i];
        if (_moreFailed) {
          return InkWell(
            key: const ValueKey('growthLedgerLoadMoreRetry'),
            onTap: _load,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 16),
              child: Text(
                l10n.notifyLoadMoreRetry,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 13, color: AppColors.muted),
              ),
            ),
          );
        }
        // 加载行一被构建（滚到底附近）就拉下一页。
        if (!_loading) WidgetsBinding.instance.addPostFrameCallback((_) => _load());
        return const Padding(
          key: ValueKey('growthLedgerLoadingMore'),
          padding: EdgeInsets.symmetric(vertical: 16),
          child: Center(child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))),
        );
      },
    );
  }

  List<Widget> _rows(AppLocalizations l10n) {
    final now = (widget.now ?? DateTime.now)();
    final groups = <NotificationGroup, List<GrowthLedgerEntry>>{};
    for (final e in _items) {
      groups.putIfAbsent(groupOf(e.createdAt, now, toLocal: toWib), () => []).add(e);
    }
    final labels = {
      NotificationGroup.today: l10n.notifyGroupToday,
      NotificationGroup.yesterday: l10n.notifyGroupYesterday,
      NotificationGroup.last7Days: l10n.notifyGroupLast7Days,
      NotificationGroup.last30Days: l10n.notifyGroupLast30Days,
      NotificationGroup.earlier: l10n.notifyGroupEarlier,
    };
    final rows = <Widget>[];
    for (final g in NotificationGroup.values) {
      final list = groups[g];
      if (list == null) continue;
      rows.add(Padding(
        key: ValueKey('growthLedgerGroup_${g.name}'),
        padding: const EdgeInsets.fromLTRB(4, 12, 4, 4),
        child: Text(
          labels[g]!.toUpperCase(),
          style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, letterSpacing: 0.6, color: AppColors.muted),
        ),
      ));
      final clock = g == NotificationGroup.today || g == NotificationGroup.yesterday;
      for (final e in list) {
        rows.add(GrowthLedgerTile(entry: e, showClock: clock));
      }
    }
    return rows;
  }
}
