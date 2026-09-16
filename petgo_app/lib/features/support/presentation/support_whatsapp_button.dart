import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/analytics/button_ids.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/config/support_contact.dart';
import '../../../shared/config/support_contact_repository.dart';
import '../../../shared/widgets/app_toast.dart';
import '../domain/support_whatsapp.dart';

/// 客服 WhatsApp 深链入口（Story 3-3）。订单详情页与工单详情页**共用同一份实现** ——
/// 两份实现早晚会漂移，而漂移的那一处一定是 PII 红线那一处。
///
/// 🔴 <b>不预判「有没有装 WhatsApp」、不调 `canLaunchUrl`</b>，两条理由：
/// ① `wa.me` 本来就是浏览器落地页 —— 没装 WhatsApp 时系统打开网页版引导页，
///    用户照样看得到号码、照样能点「Continue to Chat」。这是可接受的降级，不是故障。
/// ② `canLaunchUrl` 在本仓会**假阴性**：`AndroidManifest.xml` 的 `<queries>` 只声明了
///    `PROCESS_TEXT`，Android 11+ 的包可见性规则会让它对 `https` 意图返回 false，
///    哪怕浏览器明明装着。仓内既有 5 个 `launchUrl` 调用点全都不调它。
/// ⇒ 顺序是「先尝试打开 → 失败了才提示」，不是「先检测 → 再决定显不显示按钮」。
///
/// 🔴 <b>`await` 返回值 + `try/catch` 都要有</b>：跳转类动作在 iOS 上历史吞过错，
/// 写成裸调用的话失败时页面毫无反应，用户会反复点。
class SupportWhatsAppButton extends ConsumerWidget {
  const SupportWhatsAppButton({
    super.key,
    required this.orderNo,
    required this.screen,
  });

  /// 预填进对话框的订单号。
  ///
  /// 🔴 传的是**该页面此刻展示给用户的那个号**：用户拿去跟客服核对的就是他屏幕上
  /// 看得见的那串字符，深链填另一个号只会让客服拿到一个用户那儿找不到的号。
  final String orderNo;

  /// 埋点的 `screen` 属性。🔒 事件只带 `button_id` + `screen`，**不带订单号、不带号码**。
  final String screen;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    // 🔴 **必须在 build 里 watch**：本 provider 是 autoDispose 的，
    //    只在点击回调里 `ref.read` 的话，那一刻它才刚被创建、恒为 AsyncLoading，
    //    于是深链永远用编译进包的兜底号码 —— 3-1 的「改后台配置即生效」在这条路径上
    //    完全失效，而且失效得毫无征兆（号码是对的，只是永远不会更新）。
    //    watch 让它在按钮一出现时就开始拉，用户点下去时通常已经拿到了。
    ref.watch(supportContactProvider);
    return SizedBox(
      width: double.infinity,
      child: OutlinedButton.icon(
        key: const ValueKey('supportWhatsappCta'),
        onPressed: () => _open(context, ref, l10n),
        icon: const Icon(Icons.chat_outlined, size: 18),
        label: Text(l10n.supportWhatsappCta),
      ),
    );
  }

  Future<void> _open(BuildContext context, WidgetRef ref, AppLocalizations l10n) async {
    Analytics.buttonTapped(ButtonId.supportWhatsapp, screen: screen);

    // 🔴 `await ...future` 而不是读同步快照：build 里 watch 过了，通常已就绪即刻返回；
    //    万一还在飞（点得快 / 网慢），这里会等它落地，而不是拿一个 Loading 当"没有"。
    //    provider 内部已吞掉全部异常（永不进 error 态），catch 只是最后一道保险。
    SupportContact contact;
    try {
      contact = await ref.read(supportContactProvider.future);
    } catch (_) {
      contact = kFallbackSupportContact;
    }
    if (!context.mounted) return;
    final uri = buildSupportWhatsAppUri(
      whatsappE164: contact.whatsappE164,
      text: buildSupportWhatsAppText(orderNo: orderNo, l10n: l10n),
    );

    bool ok = false;
    try {
      ok = await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      ok = false;
    }
    if (ok || !context.mounted) return;

    // 打不开时给一条**能自己走下去**的路：把号码复制走，手动去 WhatsApp 找。
    // 🔴 复制的是展示形态 081290906953，不是 E.164 —— 印尼人往通讯录里粘的是前者。
    showAppToast(
      context,
      l10n.supportWhatsappOpenFailed,
      actionLabel: l10n.supportWhatsappCopyNumber,
      onAction: () async {
        await Clipboard.setData(ClipboardData(text: contact.whatsappNumber));
        if (context.mounted) {
          showAppToast(context, l10n.supportWhatsappNumberCopied);
        }
      },
    );
  }
}
