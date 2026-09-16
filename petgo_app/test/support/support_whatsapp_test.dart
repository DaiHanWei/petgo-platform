import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/analytics/button_ids.dart';
import 'package:tailtopia/features/support/domain/support_whatsapp.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/config/support_contact.dart';

/// L0：客服 WhatsApp 深链（Story 3-3 · AC6 / AC7 / AC8）。纯函数，不 `pumpWidget`。
///
/// 🔴🔴 **本类的核心是 SHOP-NFR-05：预填串里不许有任何个人信息。**
/// 深链的 `text` 会落进用户的 WhatsApp 输入框 —— 也就落进了对话记录、
/// 落进了他可能转发给别人的截图。
///
/// 🎯 **变异靶子**：标了 🎯 的那条用例必须在 `buildSupportWhatsAppText`
/// 拼上收件人姓名后变红。
void main() {
  late AppLocalizations en;
  late AppLocalizations id;

  setUpAll(() async {
    en = await AppLocalizations.delegate.load(const Locale('en'));
    id = await AppLocalizations.delegate.load(const Locale('id'));
  });

  group('URL 形态', () {
    test('🔴 wa.me 的路径段**不带 +**', () {
      final uri = buildSupportWhatsAppUri(
          whatsappE164: '+6281290906953', text: 'halo');

      expect(uri.scheme, 'https');
      expect(uri.host, 'wa.me');
      expect(uri.path, '/6281290906953', reason: 'wa.me 的路径要的是纯数字');
      expect(uri.toString(), isNot(contains('+6281290906953')));
    });

    test('已经不带 + 的号码原样用（防御性，provider 给的恒带 +）', () {
      final uri =
          buildSupportWhatsAppUri(whatsappE164: '6281290906953', text: 'halo');
      expect(uri.path, '/6281290906953');
    });

    test('text 走 query 参数，空格与特殊字符编码正确', () {
      final uri = buildSupportWhatsAppUri(
          whatsappE164: '+6281290906953',
          text: 'Halo, saya butuh bantuan untuk pesanan TOKO-20260916-000042');

      // Uri 自己会编码；反解回来必须逐字相等（没有双重编码）。
      expect(uri.queryParameters['text'],
          'Halo, saya butuh bantuan untuk pesanan TOKO-20260916-000042');
      expect(uri.toString(), contains('text='));
      expect(uri.toString(), isNot(contains(' ')), reason: '空格必须被编码');
    });

    test('兜底号码拼出来的链接是可用的', () {
      final uri = buildSupportWhatsAppUri(
          whatsappE164: kFallbackSupportContact.whatsappE164, text: 'x');
      expect(uri.toString(), startsWith('https://wa.me/6281290906953?'));
    });
  });

  group('🔒 SHOP-NFR-05：预填只含订单号', () {
    // 一个「装满了收件人信息」的订单 —— 这些值一个都不许出现在预填串里。
    const orderNo = 'TOKO-20260916-000042';
    const pii = <String, String>{
      'receiverName': 'Budi Santoso',
      'receiverPhone': '081234567890',
      'addressLine': 'Jl. Melati No. 1 RT 05',
      'kecamatan': 'Kebayoran Baru',
      'kotaKabupaten': 'Jakarta Selatan',
      'kodePos': '12110',
      'email': 'budi@example.com',
      'userId': '90210',
    };

    // 🎯 **变异靶子**（AC6）：把 buildSupportWhatsAppText 改成
    //    `l10n.supportWhatsappPrefill(orderNo) + ' ' + receiverName`，下面这条必须变红。
    test('🎯 输出含订单号，且**不含任何收件人信息**（en + id 两包都验）', () {
      for (final l10n in [en, id]) {
        final text = buildSupportWhatsAppText(orderNo: orderNo, l10n: l10n);

        expect(text, contains(orderNo), reason: '客服要的就是这个号');
        for (final entry in pii.entries) {
          expect(text.contains(entry.value), isFalse,
              reason: '预填串里出现了 ${entry.key} = "${entry.value}"'
                  ' —— 它会进用户的聊天记录与截图（SHOP-NFR-05）');
        }
      }
    });

    test('函数签名本身就是护栏：只收一个订单号字符串', () {
      // 收整个订单对象的话，往引导语里加一句「送到 {address}」就只是一行代码的事。
      // 这条用例护的是那个设计决定 —— 改签名会让它编译不过。
      final text = buildSupportWhatsAppText(orderNo: 'X-1', l10n: en);
      expect(text, contains('X-1'));
    });

    test('订单号里的连字符不被吃掉（用户要逐位核对）', () {
      final text = buildSupportWhatsAppText(orderNo: orderNo, l10n: id);
      expect(text, contains('-'));
      expect(text, contains('000042'));
    });
  });

  group('埋点白名单（AC8：两处都要改）', () {
    test('🔴 supportWhatsapp 已登记 —— 只加常量不加白名单会在 release 静默丢弃', () {
      expect(ButtonId.supportWhatsapp, 'support.whatsapp');
      expect(Analytics.isRegisteredButtonId(ButtonId.supportWhatsapp), isTrue,
          reason: '没进白名单的话开发机上有数据、线上永远零，发版后才发现');
    });
  });
}
