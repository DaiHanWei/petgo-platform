/// Toko 商品搜索页（2026-09-02 产品定形）。
///
/// ## 为什么是独立页，而不是顶栏里的输入框
/// C-18 当初把搜索定成「就地过滤商品流、无独立结果页」，实现上是在
/// [ShopAppBar.bottom] 放一个常驻输入框。但 R-4 要把**分类**也挪进同一个吸顶槽 ——
/// 两者争这一个位置：叠两行要吃掉约 170px 首屏，挤一行则输入框和分类都不好用。
///
/// 产品 2026-09-02 定的解法是**把搜索收成一个放大镜**：吸顶行 = 放大镜 + 分类依次排开，
/// 点放大镜进本页。搜索的完整形态（键盘、输入、结果）搬到这里，
/// 吸顶行因此只花掉一个图标的宽度，剩下的全给分类。
///
/// ## 刻意不做的
/// 🔴 **无搜索历史、无热搜词、无页内分类筛选**（2026-09-02 拍板「最小形态」）。
/// C-18 收窄形态的原意保留 —— 变的只是「就地过滤」换成「独立页」，不是把搜索做大。
/// ⚠️ 后端 `q` 与 `category` 本就是**与**关系，日后要加页内品类筛选不用改接口。
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../data/shop_repository.dart';
import 'widgets/shop_pressable.dart';
import 'widgets/shop_product_masonry.dart';
import 'widgets/shop_surface.dart';

class ShopSearchPage extends ConsumerStatefulWidget {
  const ShopSearchPage({super.key});

  @override
  ConsumerState<ShopSearchPage> createState() => _ShopSearchPageState();
}

class _ShopSearchPageState extends ConsumerState<ShopSearchPage> {
  /// 已**生效**的关键词（已防抖）。输入框里正在敲的那一份在 [_controller] 里，
  /// 两者故意分开：每敲一个字母就换一次 provider 族键 = 每个字母一次网络请求。
  String? _keyword;

  final TextEditingController _controller = TextEditingController();
  Timer? _debounce;

  /// 防抖窗口。300ms 是「打完一个词的停顿」与「感觉不到延迟」之间的常用折中；
  /// 再短会把连续输入拆成多次请求，再长会让用户以为搜索框没反应。
  static const Duration _debounceWindow = Duration(milliseconds: 300);

  @override
  void dispose() {
    // 🔴 两个都要收：Timer 不取消会在页面销毁后回调进 setState（"setState after dispose"），
    //    controller 不 dispose 会泄漏监听。
    _debounce?.cancel();
    _controller.dispose();
    super.dispose();
  }

  void _onChanged(String raw) {
    _debounce?.cancel();
    // 清空是**立即**生效的，不等防抖 —— 用户点 × 是想马上回到空态，
    // 让它等 300ms 会显得没点上。
    if (raw.trim().isEmpty) {
      if (_keyword != null) setState(() => _keyword = null);
      return;
    }
    _debounce = Timer(_debounceWindow, () {
      if (!mounted) return;
      final next = raw.trim();
      if (next == _keyword) return; // 敲了又删回原样：不必重建族键
      setState(() => _keyword = next);
    });
  }

  void _clear() {
    _debounce?.cancel();
    _controller.clear();
    if (_keyword != null) setState(() => _keyword = null);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final kw = _keyword;
    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(
        // 标题槽整个让给输入框 —— 本页除了搜索没有别的事，不需要页名占一行。
        title: '',
        tone: ShopAppBarTone.light,
        titleWidget: _SearchField(
          controller: _controller,
          hint: l10n.tokoSearchHint,
          clearLabel: l10n.tokoSearchClear,
          onChanged: _onChanged,
          onClear: _clear,
        ),
      ),
      body: kw == null
          // 🔴 未输入 = **一片留白**，不画热词也不画历史（拍板的最小形态）。
          //    这里放任何东西都会变成「需要维护的内容位」。
          ? const SizedBox.shrink()
          : ref.watch(shopProductsProvider((category: null, keyword: kw))).when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (_, _) => ShopRetryState(
                  message: l10n.tokoLoadFailed,
                  retryLabel: l10n.commonRetry,
                  onRetry: () => ref.invalidate(
                      shopProductsProvider((category: null, keyword: kw))),
                ),
                data: (items) => items.isEmpty
                    ? _noResult(l10n, kw)
                    : ListView(
                        padding: const EdgeInsets.only(bottom: kShopGutter),
                        children: [
                          ShopProductMasonry(
                            items: items,
                            // 行级归因：服务端加购时把它记在购物车行上，
                            // 之后能回答「搜出来的商品到底转化如何」。
                            entrySource: 'TOKO_SEARCH',
                          ),
                        ],
                      ),
              ),
    );
  }

  /// 🔴 「搜不到」与「目录是空的」必须分开说 —— 都用 tokoEmpty 会让用户以为整个店没货。
  /// 第二行给下一步动作，否则这一屏是个死胡同。
  Widget _noResult(AppLocalizations l10n, String keyword) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: kShopScreenEdge, vertical: 40),
        child: Column(
          children: [
            Text(l10n.tokoSearchEmpty(keyword),
                key: const ValueKey('shopSearchNoResultV2'),
                textAlign: TextAlign.center,
                style: ShopText.body),
            const SizedBox(height: 6),
            Text(l10n.tokoSearchNoResultHint,
                textAlign: TextAlign.center,
                style: ShopText.meta.copyWith(color: ShopColors.text4)),
          ],
        ),
      );
}

/// 顶栏里的搜索输入框。药丸形，自动聚焦 —— 用户是**点着放大镜进来的**，
/// 落地还要再点一次输入框才能打字，等于把刚省下的那一步又还回去。
class _SearchField extends StatelessWidget {
  const _SearchField({
    required this.controller,
    required this.hint,
    required this.clearLabel,
    required this.onChanged,
    required this.onClear,
  });

  final TextEditingController controller;
  final String hint;
  final String clearLabel;
  final ValueChanged<String> onChanged;
  final VoidCallback onClear;

  static const double _pillHeight = 36;

  @override
  Widget build(BuildContext context) => SizedBox(
        height: _pillHeight,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: ShopColors.bg,
            borderRadius: BorderRadius.circular(_pillHeight / 2),
          ),
          child: Row(
            children: [
              const SizedBox(width: 12),
              const Icon(Icons.search, size: 18, color: ShopColors.text4),
              const SizedBox(width: 6),
              Expanded(
                child: TextField(
                  key: const ValueKey('shopSearchFieldV2'),
                  controller: controller,
                  onChanged: onChanged,
                  autofocus: true,
                  textInputAction: TextInputAction.search,
                  style: ShopText.body,
                  // 🔴 collapsed + 自绘 Row：默认 InputDecoration 会带上
                  //    自己的 48 高约束与下划线，塞进 36 的药丸里必然溢出。
                  decoration: InputDecoration.collapsed(
                    hintText: hint,
                    hintStyle: ShopText.body.copyWith(color: ShopColors.text4),
                  ),
                ),
              ),
              // 有内容才出现清除按钮 —— 常驻一个 × 会让空输入框看起来像有内容。
              ValueListenableBuilder<TextEditingValue>(
                valueListenable: controller,
                builder: (context, value, _) => value.text.isEmpty
                    ? const SizedBox(width: 12)
                    : Semantics(
                        button: true,
                        label: clearLabel,
                        child: ShopPressable(
                          key: const ValueKey('shopSearchClearV2'),
                          onTap: onClear,
                          child: const Padding(
                            padding: EdgeInsets.symmetric(horizontal: 10),
                            child: Icon(Icons.close, size: 16, color: ShopColors.text4),
                          ),
                        ),
                      ),
              ),
            ],
          ),
        ),
      );
}
