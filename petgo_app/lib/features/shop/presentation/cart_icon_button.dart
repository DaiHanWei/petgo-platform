import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../data/cart_repository.dart';

/// 带角标的购物车入口（Story 3.6）。
///
/// 🔴 **角标是「商品件数」不是「种类数」**（FR-96）：车里 3 种商品共 7 件时角标显示 7。
/// 显示种类数会让用户以为自己漏加了。件数由后端 `CartView.itemCount` 给（只累计有效行）。
///
/// 🔴 **暂时挂在页面 AppBar 上，不是底部 Tab**：Tab 位序归 DEP-1、图标归 DEP-2，两者
/// 都未闭合，本版本不动 `bottom_tab_bar.dart`（并行契约 §三.5）。DEP-1 闭合后 Tab 角标
/// 直接 watch 同一个 [cartItemCountProvider] 即可，本组件与那次改动不冲突。
class CartIconButton extends ConsumerWidget {
  const CartIconButton({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final count = ref.watch(cartItemCountProvider);

    return IconButton(
      tooltip: l10n.cartOpen,
      // 游客也放进去：购物车页自己渲染「登录后查看」空态并给软性引导入口，
      // 在这里拦一道只会让点击毫无反应（FR-93A：浏览路径上不设登录墙）。
      onPressed: () => context.push('/shop/cart'),
      icon: Stack(
        clipBehavior: Clip.none,
        children: [
          const Icon(Icons.shopping_cart_outlined),
          if (count > 0)
            Positioned(
              right: -6,
              top: -4,
              child: Container(
                key: const ValueKey('cartBadge'),
                padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                constraints: const BoxConstraints(minWidth: 16),
                decoration: BoxDecoration(
                  color: AppColors.mint,
                  borderRadius: BorderRadius.circular(9),
                ),
                child: Text(
                  // 99+ 封顶：角标位放不下四位数，而「很多」这一信息量已经够了。
                  count > 99 ? '99+' : '$count',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                      color: Colors.white, fontSize: 10, fontWeight: FontWeight.w700),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
