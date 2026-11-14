import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../content/presentation/report_sheet.dart';
import '../data/gift_repository.dart';
import '../domain/gift_order_detail.dart';
import 'gift_page.dart' show giftSenderAvatar;
import 'gift_style.dart' show giftIcon;

/// 礼物详情抽屉（V1.5.0 gift-pack Story 2-6 · H1 主人 / H2 访客 / H3 祝福语已隐藏 / H4 送礼人已注销）。
///
/// 底部抽屉（遮罩 + 上圆角），调 `GET /api/v1/gift-orders/{token}`；加载中骨架、失败可重试。
/// 访客与主人同一抽屉：访客侧没有任何「未拆」字样（服务端访客视角本就不给 `opened`）。
Future<void> showGiftOrderDetailSheet(BuildContext context, String orderToken) => showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
      builder: (_) => GiftOrderDetailSheet(orderToken: orderToken),
    );

/// 举报祝福语（H5 / H6）：**原样复用** `report_sheet.dart` 的抽屉（文案一字不改），只换提交目标。
/// 举报成功后本地不隐藏祝福语（以后台处理为准）。
void openGiftMessageReport(BuildContext context, WidgetRef ref, String orderToken) {
  final l10n = AppLocalizations.of(context);
  openReportSheet(
    context,
    ref,
    submit: (reason) async {
      try {
        await ref.read(giftRepositoryProvider).reportGiftMessage(orderToken, reason.wire);
      } catch (_) {
        // 含 422 `gift-message-not-reportable`（理论上 UI 已不展示入口）：通用失败提示，抽屉留在表单态
        // 抽屉仍开着：toast 置顶，不盖住「Kirim Laporan」（照 Story 2.2 AC7 / account_report_sheet，2-6 复审 #1）
        if (context.mounted) showAppToast(context, l10n.giftOpenFailed, top: true);
        rethrow;
      }
    },
  );
}

class GiftOrderDetailSheet extends ConsumerStatefulWidget {
  const GiftOrderDetailSheet({super.key, required this.orderToken});

  final String orderToken;

  @override
  ConsumerState<GiftOrderDetailSheet> createState() => _GiftOrderDetailSheetState();
}

class _GiftOrderDetailSheetState extends ConsumerState<GiftOrderDetailSheet> {
  GiftOrderDetail? _detail;
  bool _failed = false;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final gen = ++_generation;
    if (_failed) setState(() => _failed = false);
    try {
      final d = await ref.read(giftRepositoryProvider).fetchGiftOrderDetail(widget.orderToken);
      if (!mounted || gen != _generation) return;
      setState(() => _detail = d);
    } catch (_) {
      if (!mounted || gen != _generation) return;
      setState(() => _failed = true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final d = _detail;
    final Widget body;
    if (d != null) {
      body = _DetailBody(detail: d);
    } else if (_failed) {
      body = Padding(
        key: const ValueKey('giftDetailFailed'),
        padding: const EdgeInsets.symmetric(vertical: 40),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Text(l10n.giftLoadFailed, style: const TextStyle(fontSize: 15, color: AppColors.ink)),
          const SizedBox(height: 12),
          OutlinedButton(
            key: const ValueKey('giftDetailRetry'),
            onPressed: _load,
            child: Text(l10n.commonRetry),
          ),
        ]),
      );
    } else {
      body = const _Skeleton();
    }
    return SafeArea(
      top: false,
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Center(
          child: Container(
            width: 36,
            height: 4,
            margin: const EdgeInsets.only(top: 10, bottom: 6),
            decoration: BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
          ),
        ),
        Flexible(child: SingleChildScrollView(child: body)),
      ]),
    );
  }
}

class _DetailBody extends ConsumerWidget {
  const _DetailBody({required this.detail});

  final GiftOrderDetail detail;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context);
    final d = detail;
    final s = d.sender;
    // 昵称缺失（防御，2-6 复审 #2）按注销口径显示
    final nick = s.nickname?.trim() ?? '';
    final name = s.deleted || nick.isEmpty ? l10n.feedDeletedUser : nick;
    final message = d.message;
    final receivedAt = d.receivedAt;
    final userId = s.userId;
    final pets = s.deleted ? const <String>[] : s.petNames;
    return Padding(
      key: const ValueKey('giftDetailBody'),
      padding: const EdgeInsets.fromLTRB(24, 4, 12, 16),
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(
            child: Padding(
              padding: const EdgeInsets.only(top: 8, right: 4),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(l10n.giftDetailTitle(d.giftNameFor(locale), d.petName),
                    key: const ValueKey('giftDetailTitle'),
                    style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: AppColors.ink)),
                if (receivedAt != null) ...[
                  const SizedBox(height: 4),
                  Text(l10n.giftDetailReceivedOn(formatBirthday(context, receivedAt.toLocal())),
                      key: const ValueKey('giftDetailDate'),
                      style: const TextStyle(fontSize: 13, color: AppColors.muted)),
                ],
              ]),
            ),
          ),
          if (d.canReport)
            PopupMenuButton<int>(
              key: const ValueKey('giftDetailMore'),
              icon: const Icon(Icons.more_horiz_rounded, color: AppColors.ink),
              onSelected: (_) => openGiftMessageReport(context, ref, d.orderToken),
              itemBuilder: (_) => [
                PopupMenuItem<int>(
                  key: const ValueKey('giftDetailReport'),
                  value: 0,
                  child: Text(l10n.giftReportMessage),
                ),
              ],
            )
          else
            const SizedBox(width: 12),
        ]),
        const SizedBox(height: 16),
        Padding(
          padding: const EdgeInsets.only(right: 12),
          child: Column(children: [
            // 礼物浮在打开的礼盒上方（礼盒正式图待设计交付，此处只放礼物）
            giftIcon(d.giftIconUrl, size: 96),
            if (d.quantity > 1) ...[
              const SizedBox(height: 4),
              Text(l10n.giftDetailQuantity(d.quantity),
                  key: const ValueKey('giftDetailQuantity'),
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.ink)),
            ],
            if (message != null) ...[
              const SizedBox(height: 16),
              Text(message,
                  key: const ValueKey('giftDetailMessage'),
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 15, height: 1.45, color: AppColors.ink)),
            ] else if (d.messageHidden) ...[
              const SizedBox(height: 16),
              Text(l10n.giftMessageHidden,
                  key: const ValueKey('giftDetailMessageHidden'),
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 14, height: 1.45, color: AppColors.muted)),
            ],
            const SizedBox(height: 20),
            Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              giftSenderAvatar(s.brief, size: 24),
              const SizedBox(width: 8),
              Flexible(
                child: Text.rich(
                  key: const ValueKey('giftDetailSender'),
                  TextSpan(children: [
                    TextSpan(
                        text: l10n.giftReceivedFrom(name),
                        style: const TextStyle(fontWeight: FontWeight.w700, color: AppColors.ink)),
                    if (pets.isNotEmpty) TextSpan(text: ' · ${l10n.giftDetailWithPets(pets.join(', '))}'),
                  ]),
                  style: const TextStyle(fontSize: 13, color: AppColors.muted),
                ),
              ),
            ]),
            if (!s.deleted && userId != null && nick.isNotEmpty)
              TextButton(
                key: const ValueKey('giftDetailSenderProfile'),
                style: TextButton.styleFrom(minimumSize: const Size(44, 44)),
                onPressed: () {
                  final router = GoRouter.of(context);
                  Navigator.of(context).pop();
                  router.push('/users/$userId');
                },
                child: Text(l10n.giftViewSenderProfile(name),
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.mint)),
              ),
          ]),
        ),
      ]),
    );
  }
}

class _Skeleton extends StatelessWidget {
  const _Skeleton();

  @override
  Widget build(BuildContext context) {
    Widget bar(double w, double h) => Container(
          width: w,
          height: h,
          decoration: BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(8)),
        );
    return Padding(
      key: const ValueKey('giftDetailSkeleton'),
      padding: const EdgeInsets.fromLTRB(24, 12, 24, 32),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        bar(180, 18),
        const SizedBox(height: 8),
        bar(120, 12),
        const SizedBox(height: 24),
        Center(child: bar(96, 96)),
        const SizedBox(height: 20),
        bar(double.infinity, 12),
        const SizedBox(height: 8),
        bar(200, 12),
      ]),
    );
  }
}
