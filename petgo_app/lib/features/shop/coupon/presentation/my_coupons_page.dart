/// 我的券（v1.4.2 shop Story 9.3 · G1–G8）：兑换区 + 「Bisa dipakai / Sudah dipakai」两页签 + 游标分页列表。
///
/// - 路由 `/me/coupons`（`/me` 前缀受控 → 游客先登录）。
/// - 可用页签原样渲染服务端结果（C-3：被待付款订单占用的券带 `lockedByPendingOrder`，置灰不可点）；
///   **App 不按状态过滤、不按本地时钟隐藏**。
/// - 兑换：请求中按钮转圈、输入只读、不可重复提交；成功插顶 + 「Baru」+ `toko_coupon_claimed{source: code}`；
///   失败按 `typeSlug` 显示在输入框下（不 toast），未知错误才 toast；429 / `coupon-redeem-locked` 本页存续期间禁用。
/// - 🔴 兑换码明文不进日志 / 埋点（埋点只带模板 `couponToken`）。
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/shop_tokens.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/utils/date_format.dart';
import '../../../../shared/widgets/app_toast.dart';
import '../../presentation/widgets/shop_surface.dart';
import '../data/coupon_repository.dart';
import '../domain/user_coupon.dart';
import 'coupon_card.dart';
import 'my_coupons_controller.dart';

class MyCouponsPage extends ConsumerStatefulWidget {
  const MyCouponsPage({super.key});

  static const String routePath = '/me/coupons';

  @override
  ConsumerState<MyCouponsPage> createState() => _MyCouponsPageState();
}

class _MyCouponsPageState extends ConsumerState<MyCouponsPage> {
  final _input = TextEditingController();
  final _focus = FocusNode();
  CouponTab _tab = CouponTab.available;

  /// 兑换在途（防重复提交）。
  bool _submitting = false;

  /// 当前框下错误（null = 无）。
  CouponRedeemError? _error;

  /// 本页存续期间锁定（G8，服务端计时，App 不倒计时）。
  bool _locked = false;

  /// 本页兑换成功的用户券：刷新后「Baru」仍保留到离开本页。
  final Set<String> _newTokens = {};

  /// 已访问页签的订阅：切走不回收（来回切不重复请求），离开本页随 dispose 释放。
  final Map<CouponTab, ProviderSubscription<AsyncValue<MyCouponsState>>> _subs = {};

  @override
  void initState() {
    super.initState();
    _keepAlive(CouponTab.available);
  }

  void _keepAlive(CouponTab tab) {
    _subs.putIfAbsent(tab, () => ref.listenManual(myCouponsProvider(tab), (_, _) {}));
  }

  @override
  void dispose() {
    for (final s in _subs.values) {
      s.close();
    }
    _input.dispose();
    _focus.dispose();
    super.dispose();
  }

  void _selectTab(CouponTab tab) {
    if (tab == _tab) return;
    _keepAlive(tab);
    setState(() => _tab = tab);
  }

  /// 规范化后不足 4 位不可提交（不发请求、不耗试错次数，复审 #3）。
  bool get _canSubmit => !_submitting && !_locked && isSubmittableCouponCode(_input.text);

  Future<void> _redeem() async {
    if (!_canSubmit) return;
    final code = normalizeCouponCode(_input.text);
    final l10n = AppLocalizations.of(context);
    final container = ProviderScope.containerOf(context, listen: false);
    setState(() {
      _submitting = true;
      _error = null;
    });
    final UserCoupon coupon;
    try {
      coupon = await container.read(couponRepositoryProvider).redeem(code);
    } catch (e) {
      final err = CouponRedeemError.from(e);
      if (!mounted) return;
      setState(() {
        _submitting = false;
        if (err == CouponRedeemError.locked) _locked = true;
        if (err.isInline) _error = err;
      });
      if (err == CouponRedeemError.other) showAppToast(context, l10n.couponRedeemFailed);
      return;
    }
    // 🔴 coupon_id = 模板 token（D-23，与 toko_order_submitted.coupon_ids 同口径）；不带兑换码。
    Analytics.capture('toko_coupon_claimed', {'coupon_id': coupon.couponToken, 'source': 'code'});
    // 入口计数 / 首页「给你的券」按最新数据重算（结算试算每次进入都会重拉，无需失效）。
    container.invalidate(couponSummaryProvider);
    container.invalidate(couponsForYouProvider);
    if (!mounted) return;
    _keepAlive(CouponTab.available);
    _newTokens.add(coupon.userCouponToken);
    _input.clear();
    _focus.unfocus();
    setState(() {
      _submitting = false;
      _tab = CouponTab.available;
    });
    // toast 在键盘收起后显示。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) showAppToast(context, l10n.couponRedeemSuccess);
    });
    await _mergeRedeemed(coupon);
  }

  /// 把兑换到的券并入可用列表并后台刷新（复审 #2）：首屏还没返回时先等它完成再插顶，
  /// 否则 insertTop 落空、新券要等刷新才出现（且首屏结果会覆盖掉本地插入）。首屏失败则直接刷新。
  Future<void> _mergeRedeemed(UserCoupon coupon) async {
    final provider = myCouponsProvider(CouponTab.available);
    if (!ref.read(provider).hasValue) {
      try {
        await ref.read(provider.future);
      } catch (_) {
        // 首屏失败：下面的 refresh 会重拉（成功则带上新券）。
      }
      if (!mounted) return;
    }
    final notifier = ref.read(provider.notifier);
    notifier.insertTop(coupon);
    // 后台刷新列表与计数（不闪屏）。
    await notifier.refresh();
  }

  String? _errorText(AppLocalizations l10n) => switch (_error) {
        CouponRedeemError.invalid => l10n.couponRedeemInvalid,
        CouponRedeemError.codeUsed => l10n.couponRedeemUsed,
        CouponRedeemError.notInPeriod => l10n.couponRedeemNotInPeriod,
        CouponRedeemError.alreadyClaimed => l10n.couponAlreadyClaimed,
        CouponRedeemError.soldOut => l10n.couponSoldOut,
        CouponRedeemError.locked => l10n.couponRedeemLocked,
        _ => null,
      };

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(
        title: l10n.couponMyCoupons,
        tone: ShopAppBarTone.light,
        leading: IconButton(
          icon: const Icon(Icons.chevron_left_rounded, size: 28),
          onPressed: () => Navigator.of(context).maybePop(),
        ),
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _redeemBox(l10n),
          _tabs(l10n),
          Expanded(child: _CouponList(tab: _tab, newTokens: _newTokens)),
        ],
      ),
    );
  }

  Widget _redeemBox(AppLocalizations l10n) {
    final err = _errorText(l10n);
    final enabled = !_locked;
    OutlineInputBorder border(Color c) => OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: c),
        );
    final lineColor = err != null ? ShopColors.error : ShopColors.border;
    return Container(
      color: ShopColors.surface,
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: SizedBox(
                  height: 48,
                  child: TextField(
                    key: const ValueKey('couponRedeemInput'),
                    controller: _input,
                    focusNode: _focus,
                    enabled: enabled,
                    readOnly: _submitting,
                    textCapitalization: TextCapitalization.characters,
                    maxLength: kCouponCodeMaxLength,
                    inputFormatters: [_CouponCodeFormatter()],
                    textInputAction: TextInputAction.done,
                    onSubmitted: (_) => _redeem(),
                    onChanged: (_) => setState(() {
                      if (!_locked) _error = null;
                    }),
                    style: AppTypography.body.copyWith(fontSize: 15, color: ShopColors.text),
                    decoration: InputDecoration(
                      hintText: l10n.couponRedeemHint,
                      counterText: '',
                      isDense: true,
                      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
                      enabledBorder: border(lineColor),
                      disabledBorder: border(lineColor),
                      focusedBorder: border(err != null ? ShopColors.error : ShopColors.purple),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              SizedBox(
                height: 36,
                child: FilledButton(
                  key: const ValueKey('couponRedeemButton'),
                  onPressed: _canSubmit ? _redeem : null,
                  style: FilledButton.styleFrom(
                    backgroundColor: ShopColors.purple,
                    disabledBackgroundColor: ShopColors.border,
                    shape: const StadiumBorder(),
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                  ),
                  child: _submitting
                      ? const SizedBox(
                          key: ValueKey('couponRedeemSpinner'),
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: ShopColors.surface),
                        )
                      : Text(l10n.couponRedeemButton),
                ),
              ),
            ],
          ),
          if (err != null)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(err,
                  key: const ValueKey('couponRedeemError'),
                  style: AppTypography.caption.copyWith(fontSize: 12, color: ShopColors.errorText)),
            ),
        ],
      ),
    );
  }

  Widget _tabs(AppLocalizations l10n) {
    final count = ref.watch(myCouponsProvider(CouponTab.available)).value?.availableCount;
    Widget tab(CouponTab t, String label) {
      final on = t == _tab;
      return Expanded(
        child: InkWell(
          key: ValueKey('couponTab_${t.api}'),
          onTap: () => _selectTab(t),
          child: Container(
            height: 44,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              border: Border(
                bottom: BorderSide(color: on ? ShopColors.purple : Colors.transparent, width: 2),
              ),
            ),
            child: Text(label,
                style: AppTypography.caption.copyWith(
                  fontSize: 13,
                  color: on ? ShopColors.text : ShopColors.text2,
                  fontWeight: on ? FontWeight.w600 : FontWeight.w400,
                )),
          ),
        ),
      );
    }

    return Container(
      decoration: const BoxDecoration(
        color: ShopColors.surface,
        border: Border(bottom: BorderSide(color: ShopColors.divider)),
      ),
      child: Row(children: [
        tab(CouponTab.available,
            count == null ? l10n.couponTabAvailablePlain : l10n.couponAvailableCount(count)),
        tab(CouponTab.used, l10n.couponTabUsed),
      ]),
    );
  }
}

/// 输入即规范化（复审 #3）：大写、只留 `[A-Z0-9]`、限长 32；光标按过滤后的位置保留。
class _CouponCodeFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    final text = normalizeCouponCode(newValue.text);
    final sel = newValue.selection;
    final cursor = sel.isValid
        ? normalizeCouponCode(newValue.text.substring(0, sel.extentOffset.clamp(0, newValue.text.length)))
            .length
        : text.length;
    return TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: cursor.clamp(0, text.length)),
    );
  }
}

/// 一个页签的列表：加载 / 失败（G5）/ 空（G4）/ 数据 + 加载更多。
class _CouponList extends ConsumerWidget {
  const _CouponList({required this.tab, required this.newTokens});

  final CouponTab tab;
  final Set<String> newTokens;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(myCouponsProvider(tab));
    final notifier = ref.read(myCouponsProvider(tab).notifier);
    final data = async.value;
    if (data == null) {
      if (async.hasError) {
        return _Failed(onRetry: notifier.retry);
      }
      return const Center(child: CircularProgressIndicator());
    }
    if (data.items.isEmpty) {
      return RefreshIndicator(
        onRefresh: notifier.refresh,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            _Empty(
              title: l10n.couponEmptyTitle,
              hint: tab == CouponTab.available ? l10n.couponEmptyHint : null,
            ),
          ],
        ),
      );
    }
    final itemCount = data.items.length + (data.hasMore || data.loadMoreFailed ? 1 : 0);
    return RefreshIndicator(
      onRefresh: notifier.refresh,
      child: ListView.separated(
        key: ValueKey('couponList_${tab.api}'),
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        itemCount: itemCount,
        separatorBuilder: (_, _) => const SizedBox(height: 8),
        itemBuilder: (context, i) {
          if (i >= data.items.length) {
            if (data.loadMoreFailed) {
              return Center(
                child: TextButton(
                  key: const ValueKey('couponLoadMoreRetry'),
                  onPressed: notifier.loadMore,
                  child: Text(l10n.commonRetry),
                ),
              );
            }
            WidgetsBinding.instance.addPostFrameCallback((_) => notifier.loadMore());
            return const Padding(
              padding: EdgeInsets.all(8),
              child: Center(
                  child: SizedBox(
                      width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))),
            );
          }
          final c = data.items[i];
          return _MyCouponTile(
            coupon: c,
            isNew: c.isNew || newTokens.contains(c.userCouponToken),
          );
        },
      ),
    );
  }
}

/// WIB（UTC+7，不随手机时区，P-6）墙钟时间。
DateTime _wib(DateTime t) {
  final u = t.toUtc().add(const Duration(hours: 7));
  return DateTime(u.year, u.month, u.day, u.hour, u.minute);
}

String _categoryName(AppLocalizations l10n, String code) => switch (code) {
      'MAKANAN' => l10n.tokoCategoryMakanan,
      'OBAT_VITAMIN' => l10n.tokoCategoryObatVitamin,
      'CAMILAN' => l10n.tokoCategoryCamilan,
      'PERAWATAN' => l10n.tokoCategoryPerawatan,
      _ => '',
    };

/// 一张我的券（券面文案集中在 [CouponCard]，这里只决定用哪些可选参数）。
class _MyCouponTile extends StatelessWidget {
  const _MyCouponTile({required this.coupon, required this.isNew});

  final UserCoupon coupon;
  final bool isNew;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final c = coupon;
    final used = c.status == UserCouponStatus.used;
    final until = c.validUntil;
    String? validity;
    if (until != null) {
      validity = (c.expiringSoon && !used && !c.isLocked)
          ? l10n.couponEndsAt(formatDayMonthYearTime(context, _wib(until)))
          : l10n.couponValidUntil(formatDayMonthYear(context, _wib(until)));
    }
    final scope = switch (c.scopeType) {
      CouponScopeType.all => l10n.tokoAllProducts,
      CouponScopeType.category => () {
          final names = c.scopeCategories
              .map((e) => _categoryName(l10n, e))
              .where((e) => e.isNotEmpty)
              .toList();
          return names.isEmpty ? l10n.couponScopeProduct : l10n.couponScopeCategory(names.join(', '));
        }(),
      CouponScopeType.product => l10n.couponScopeProduct,
    };
    final caption = AppTypography.caption.copyWith(fontSize: 12, color: ShopColors.text3);
    final lines = <Widget>[
      Text(scope, key: const ValueKey('couponScope'), style: caption),
    ];
    if (used && c.usedAt != null && c.usedOrderNo != null) {
      final text = Text(
        l10n.couponUsedOn(formatDayMonthYear(context, _wib(c.usedAt!)), c.usedOrderNo!),
        style: caption.copyWith(
          color: c.usedOrderToken != null ? ShopColors.purple : ShopColors.text3,
        ),
      );
      final token = c.usedOrderToken;
      lines.add(Padding(
        padding: const EdgeInsets.only(top: 2),
        child: token == null
            ? KeyedSubtree(key: const ValueKey('couponUsedLine'), child: text)
            : GestureDetector(
                key: ValueKey('couponUsedOrder_$token'),
                behavior: HitTestBehavior.opaque,
                onTap: () => context.push('/shop/orders/$token'),
                child: text,
              ),
      ));
    }
    return KeyedSubtree(
      key: ValueKey('myCoupon_${c.userCouponToken}'),
      child: CouponCard(
        coupon: c.toOption(),
        disabled: c.isLocked,
        reasonText: c.isLocked ? l10n.couponLockedByPendingOrder : null,
        dimmed: used,
        tag: isNew && !used ? l10n.couponNewTag : null,
        validityText: validity,
        extraLines: lines,
      ),
    );
  }
}

class _Empty extends StatelessWidget {
  const _Empty({required this.title, this.hint});

  final String title;
  final String? hint;

  @override
  Widget build(BuildContext context) => Padding(
        key: const ValueKey('couponEmpty'),
        padding: const EdgeInsets.fromLTRB(32, 64, 32, 32),
        child: Column(
          children: [
            Container(
              width: 72,
              height: 72,
              decoration: BoxDecoration(
                color: ShopColors.accentBg,
                borderRadius: BorderRadius.circular(24),
              ),
              child: const Icon(Icons.confirmation_number_outlined,
                  size: 32, color: ShopColors.purple),
            ),
            const SizedBox(height: 16),
            Text(title,
                textAlign: TextAlign.center,
                style: AppTypography.body
                    .copyWith(fontWeight: FontWeight.w600, color: ShopColors.text)),
            if (hint != null) ...[
              const SizedBox(height: 6),
              Text(hint!,
                  textAlign: TextAlign.center,
                  style: AppTypography.caption.copyWith(color: ShopColors.text3)),
            ],
          ],
        ),
      );
}

class _Failed extends StatelessWidget {
  const _Failed({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      key: const ValueKey('couponLoadFailed'),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.cloud_off, size: 40, color: ShopColors.text4),
          const SizedBox(height: 12),
          Text(l10n.couponLoadFailed,
              style: AppTypography.body.copyWith(color: ShopColors.text2)),
          const SizedBox(height: 8),
          TextButton(onPressed: onRetry, child: Text(l10n.commonRetry)),
        ],
      ),
    );
  }
}
