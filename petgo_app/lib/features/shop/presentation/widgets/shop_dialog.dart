/// 电商板块的确认弹窗。
///
/// ## 为什么收口成一个函数（2026-08-27）
///
/// 此前四处各写各的 `AlertDialog`，结果是同一个后果等级的动作长得不一样：
/// - 取消订单的「是」用了 [ShopButtonVariant.pay]（token 文档定义为「未完成的付款动作」），
///   确认收货的「是」用的是 `ink` —— 两个不可逆动作，两种主按钮；
/// - 结算页「部分商品不可购」弹窗**只有一个按钮**，退出全靠点遮罩；
/// - 那个弹窗的内容是裸 [Column]，行数一多就溢出。
///
/// 收口之后：破坏性动作一律 [ShopButtonVariant.ink]（中性但明确），永远有取消，
/// 内容永远可滚。
///
/// 🔴 <b>不用 [ShopButtonVariant.pay] 做破坏性确认</b>：那个变体是留给「还需要付钱」的
/// 转化动作的。用它确认「取消订单」既和语义相反，也让最该让人停一拍的按钮长得最像 CTA。
library;

import 'package:flutter/material.dart';

import '../../../../core/theme/shop_tokens.dart';
import 'shop_buttons.dart';

/// 显示一个确认弹窗，返回用户是否确认。点遮罩/返回键关闭一律视为**未确认**。
Future<bool> showShopConfirm(
  BuildContext context, {
  required String title,
  required String confirmLabel,
  required String cancelLabel,
  String? body,
  Widget? content,
  Key? dialogKey,
  Key? confirmKey,
  ShopButtonVariant confirmVariant = ShopButtonVariant.ink,
}) async {
  assert(body != null || content != null, '弹窗要么给一句 body，要么给一段 content');
  final result = await showDialog<bool>(
    context: context,
    builder: (dlgCtx) => AlertDialog(
      key: dialogKey,
      backgroundColor: ShopColors.surface,
      // 设计稿明令产品 UI 内不使用阴影；M3 的 elevation 与 surfaceTint 都要显式关掉，
      // 否则弹窗会带一层浅紫染色和一圈投影，和扁平的页面语言对不上。
      elevation: 0,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(ShopShape.radiusField + 4),
      ),
      titlePadding: const EdgeInsets.fromLTRB(20, 20, 20, 0),
      contentPadding: const EdgeInsets.fromLTRB(20, 10, 20, 4),
      actionsPadding: const EdgeInsets.fromLTRB(14, 4, 14, 14),
      title: Text(title, style: ShopText.sectionTitle),
      // 🔴 内容必须可滚：不可购商品可能有很多行，裸 Column 在小屏上直接溢出。
      content: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * .45,
        ),
        child: SingleChildScrollView(
          child: content ?? Text(body!, style: ShopText.body),
        ),
      ),
      actions: [
        ShopButton(
          label: cancelLabel,
          variant: ShopButtonVariant.outlineMuted,
          dense: true,
          onTap: () => Navigator.of(dlgCtx).pop(false),
        ),
        ShopButton(
          key: confirmKey,
          label: confirmLabel,
          variant: confirmVariant,
          dense: true,
          onTap: () => Navigator.of(dlgCtx).pop(true),
        ),
      ],
    ),
  );
  return result ?? false;
}
