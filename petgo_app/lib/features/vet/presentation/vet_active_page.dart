import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/im/im_service.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/vet_repository.dart';
import '../domain/vet_workbench_lists.dart';
import 'vet_empty_state.dart';

/// 进行中 Tab：兽医当前已接单、对话中的会话列表。点卡进 [VetConversationPage]（IM 占位聊天）。
class VetActivePage extends ConsumerStatefulWidget {
  const VetActivePage({super.key});

  @override
  ConsumerState<VetActivePage> createState() => _VetActivePageState();
}

class _VetActivePageState extends ConsumerState<VetActivePage> with WidgetsBindingObserver {
  List<VetActiveItem>? _items; // null = 首屏加载中
  bool _loading = true;

  // 入站消息信号 → 实时刷新未读角标（兽医停在本 Tab 时收到机主新消息即跳数）。
  StreamSubscription<void>? _inboundSub;
  bool _refreshing = false;
  Timer? _reloadDebounce;

  // 定时轮询兜底：用户刚支付成功生成的新会话此刻还没有任何 IM 消息进来，
  // inboundSignals 不触发，列表会静默不动，直到 App 重启才拉到（bug 20260720-305）。
  // 轮询保证兽医不必「杀后台重进」即可看到新会话。退后台停轮询省电。
  static const Duration _pollInterval = Duration(seconds: 5);
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _reloadAll();
    // 工作台 IndexedStack 下本页常驻：跨 Tab 也能在收到消息时后台刷新角标。
    _inboundSub = ref.read(imServiceProvider).inboundSignals.listen((_) {
      _refreshUnread(); // 即时刷未读角标
      _scheduleReload(); // 节流重拉列表：捕获新会话进入 / 排序变化
    });
    _startPoll();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _inboundSub?.cancel();
    _reloadDebounce?.cancel();
    _poll?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 退后台停轮询省电；回前台立即静默拉一次再恢复轮询。
    if (state == AppLifecycleState.resumed) {
      _reloadAll(silent: true);
      _startPoll();
    } else {
      _poll?.cancel();
    }
  }

  /// 定时静默重拉进行中列表（不闪首屏加载圈），兜底捕获新支付成功的会话。
  void _startPoll() {
    _poll?.cancel();
    _poll = Timer.periodic(_pollInterval, (_) {
      if (mounted) _reloadAll(silent: true);
    });
  }

  /// 入站消息节流重拉：连续消息只在停顿 2s 后打一次后端，静默更新（不闪首屏加载圈）。
  void _scheduleReload() {
    _reloadDebounce?.cancel();
    _reloadDebounce = Timer(const Duration(seconds: 2), () {
      if (mounted) _reloadAll(silent: true);
    });
  }

  /// 全量重载：后端取进行中列表 → 登录 IM → 合并未读 / 最近消息。
  /// [silent]=true 时不显首屏加载圈（后台节流刷新用）。
  Future<void> _reloadAll({bool silent = false}) async {
    if (!silent) setState(() => _loading = true);
    List<VetActiveItem> items;
    try {
      items = await ref.read(vetRepositoryProvider).activeSessions();
    } catch (_) {
      items = const [];
    }
    items = await _mergeUnread(items);
    if (mounted) {
      setState(() {
        _items = items;
        _loading = false;
      });
    }
  }

  /// 仅刷新未读（不打后端）：用当前列表对端 ID 回查 IM 会话摘要，合并跳数。
  Future<void> _refreshUnread() async {
    final current = _items;
    if (current == null || current.isEmpty || _refreshing) return;
    _refreshing = true;
    try {
      final merged = await _mergeUnread(current);
      if (mounted) setState(() => _items = merged);
    } finally {
      _refreshing = false;
    }
  }

  /// 登录 IM（兽医恒可签）后按 userId 拼对端账号回查未读 + 最近消息，合并进卡片。
  /// IM 任一步失败 → 原样返回（卡片不显角标，优雅降级，不阻塞列表）。
  Future<List<VetActiveItem>> _mergeUnread(List<VetActiveItem> items) async {
    if (items.isEmpty) return items;
    final im = ref.read(imServiceProvider);
    try {
      await im.loginIfNeeded();
      final summaries = await im.conversationSummaries(items.map((e) => e.imPeerId).toList());
      if (summaries.isEmpty) return items;
      return items.map((e) {
        final s = summaries[e.imPeerId];
        if (s == null) return e;
        return e.copyWith(unread: s.unread, lastMessage: s.lastMessage ?? e.lastMessage);
      }).toList();
    } catch (_) {
      return items;
    }
  }

  Future<void> _open(VetActiveItem item) async {
    // 进会话即清该对端未读（标已读），返回后全量重载纠偏。
    await ref.read(imServiceProvider).markRead(item.imPeerId);
    if (!mounted) return;
    await context.push('/vet/conversation/${item.sessionId}');
    if (mounted) _reloadAll();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final items = _items;
    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.vetTabActive),
        actions: [
          IconButton(
            key: const ValueKey('vetActiveRefresh'),
            icon: const Icon(Icons.refresh),
            onPressed: _reloadAll,
          ),
        ],
      ),
      body: _loading && items == null
          ? const Center(child: CircularProgressIndicator())
          : (items == null || items.isEmpty)
              ? VetEmptyState(icon: Icons.chat_outlined, message: l10n.vetActiveEmpty)
              : RefreshIndicator(
                  onRefresh: _reloadAll,
                  child: ListView.separated(
                    padding: const EdgeInsets.all(AppSpacing.md),
                    itemCount: items.length,
                    separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
                    itemBuilder: (ctx, i) => _ActiveCard(item: items[i], onTap: () => _open(items[i])),
                  ),
                ),
    );
  }
}

class _ActiveCard extends StatelessWidget {
  const _ActiveCard({required this.item, required this.onTap});

  final VetActiveItem item;
  final VoidCallback onTap;

  /// 机主头像:有 URL → 网络图;否则机主名/宠物名首字母圆底。
  Widget _ownerAvatar() {
    final url = item.ownerAvatarUrl;
    if (url != null && url.isNotEmpty) {
      return CircleAvatar(
        backgroundColor: AppColors.vetPrimary.withValues(alpha: 0.15),
        backgroundImage: NetworkImage(url),
        onBackgroundImageError: (_, _) {},
      );
    }
    final label = (item.ownerName?.isNotEmpty ?? false)
        ? item.ownerName!
        : (item.petName.isNotEmpty ? item.petName : '?');
    return CircleAvatar(
      backgroundColor: AppColors.vetPrimary.withValues(alpha: 0.15),
      child: Text(label.characters.first.toUpperCase(),
          style: const TextStyle(color: AppColors.vetPrimary, fontWeight: FontWeight.w700)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return InkWell(
      key: ValueKey('vetActiveCard_${item.sessionId}'),
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.all(AppSpacing.md),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.border),
        ),
        child: Row(
          children: [
            _ownerAvatar(),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // 标题=机主名(无则宠物名);有机主名时副行带宠物名,兽医一眼看到「人 + 宠物」。
                  Text(
                    (item.ownerName?.isNotEmpty ?? false) ? item.ownerName! : item.petName,
                    style: AppTypography.body,
                  ),
                  if ((item.ownerName?.isNotEmpty ?? false) && item.petName.isNotEmpty) ...[
                    const SizedBox(height: 2),
                    Text('🐾 ${item.petName}',
                        style: AppTypography.caption, maxLines: 1, overflow: TextOverflow.ellipsis),
                  ],
                  // unread/lastMessage 由 active 页登录 IM 后按 userId 回查会话摘要合并（后端不下发）；
                  // 无 IM 数据 / 未登录时隐藏降级。
                  if (item.lastMessage.isNotEmpty) ...[
                    const SizedBox(height: 2),
                    Text(
                      item.lastMessage,
                      style: AppTypography.caption,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ],
              ),
            ),
            if (item.unread > 0) ...[
              const SizedBox(width: AppSpacing.sm),
              Container(
                padding: const EdgeInsets.all(6),
                decoration: const BoxDecoration(color: AppColors.danger, shape: BoxShape.circle),
                constraints: const BoxConstraints(minWidth: 22, minHeight: 22),
                child: Text(
                  '${item.unread}',
                  textAlign: TextAlign.center,
                  style: AppTypography.caption.copyWith(color: Colors.white),
                ),
              ),
            ],
            const Icon(Icons.chevron_right, color: AppColors.textSecondary),
          ],
        ),
      ),
    );
  }
}
