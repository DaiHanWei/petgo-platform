import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';

/// 电商 token 层护栏（V1.4.0 · `design_handoff_ecommerce/04_tokens_and_states.md`）。
///
/// 这组测试守两件容易被静默破坏的事：
/// 1. **字体随包分发** —— 字体是最容易被后人当「没用的大文件」清理掉的资产，
///    而 Flutter 对自定义字体**不做字重合成**：`Poppins w800` 一旦缺失，价格会静默回落
///    到 w700，页面不报错、不掉帧，只是全站价格比设计稿轻一档，肉眼几乎查不出根因。
/// 2. **三色分工不被「顺手统一」** —— 电商玫红与全局 popRed 是两个不同的红，
///    合并即破坏设计稿的信息骨架。
void main() {
  group('字体随包分发（缺失即静默降级，必须挡在 CI）', () {
    late final String pubspec = File('pubspec.yaml').readAsStringSync();

    test('Poppins w800 已声明且 ttf 存在', () {
      // Flutter 不合成字重：声明缺失 → w800 静默回落 w700，全站价格轻一档。
      expect(pubspec, contains('assets/fonts/Poppins-ExtraBold.ttf'),
          reason: 'pubspec 未声明 Poppins ExtraBold —— 设计稿所有价格与页面大标题都是 w800');
      expect(File('assets/fonts/Poppins-ExtraBold.ttf').existsSync(), isTrue,
          reason: 'ttf 文件不存在：声明了却没打包，运行期同样静默降级');
    });

    test('IBMPlexMono 两个字重已声明且 ttf 存在', () {
      // 等宽是硬要求：倒计时逐秒刷新时，比例字体下数字宽度不等会让整行左右抖动。
      for (final w in ['Regular', 'Medium']) {
        expect(pubspec, contains('assets/fonts/IBMPlexMono-subset-$w.ttf'),
            reason: 'pubspec 未声明 IBMPlexMono $w —— 单号与倒计时会退回比例字体并抖动');
        expect(File('assets/fonts/IBMPlexMono-subset-$w.ttf').existsSync(), isTrue);
      }
      expect(pubspec, contains('family: IBMPlexMono'));
    });

    test('ShopText 里所有等宽样式都指向已声明的 family', () {
      // 防笔误：family 名写错不会报错，只会回落默认字体 —— 同样是静默降级。
      const monoStyles = <TextStyle>[
        ShopText.countdownHero,
        ShopText.countdownInline,
        ShopText.serialNo,
      ];
      for (final s in monoStyles) {
        expect(s.fontFamily, ShopText.mono);
      }
      expect(ShopText.mono, 'IBMPlexMono');
    });

    test('OFL 许可证覆盖每一个随包字体（OFL §2 要求逐个声明版权）', () {
      final ofl = File('assets/fonts/OFL.txt').readAsStringSync();
      for (final family in ['Fraunces', 'Poppins', 'Rubik', 'Plex']) {
        expect(ofl, contains(family),
            reason: '$family 随包分发但 OFL.txt 里没有它的版权声明');
      }
      expect(pubspec, contains('assets/fonts/OFL.txt'),
          reason: 'OFL.txt 必须在 assets 列表里，否则只存在于 git、从不随 app 分发');
    });
  });

  group('三色分工（README「Color Semantics」定为信息骨架，不可混用）', () {
    // ⚠️ 本条于 2026-08-21 随「Toko 主题色改为品牌紫」翻转。
    //    原断言：电商玫红 #E1485F 与全局 popRed 是两个不同的红，不许合并。
    //    产品指定换色后玫红已不存在，但**那条断言真正在守的东西没变** ——
    //    「价格/转化」与「危险/错误」必须是两个能分开的颜色。故守门对象改为 accent vs error。
    test('商业强调色与错误红是两个色，不许合并', () {
      expect(ShopColors.accent, isNot(ShopColors.error),
          reason: '价格与错误态同色 → 用户分不出「要付钱」和「填错了」');
      // 强调色 = 品牌紫，别名回全局（不是复制字面量，避免日后全局调色时这里脱节）
      expect(identical(ShopColors.accent, AppColors.mint), isTrue);
      expect(identical(ShopColors.accentDark, AppColors.mint600), isTrue);
      // 错误红同样别名回全局，电商侧不自造第二个红
      expect(identical(ShopColors.error, AppColors.popRed), isTrue);
    });

    test('强调色与紫当前同值，但仍是两个独立旋钮', () {
      // 二者语义不同（accent 管钱与转化，purple 管平台能力），当前同色是产品选择，
      // 不是可以合并的信号。合并后日后想重新拉开，就得回头逐个辨认调用点的原意。
      expect(ShopColors.accent, ShopColors.purple, reason: '当前同值');
      final src = File('lib/core/theme/shop_tokens.dart').readAsStringSync();
      expect(src, contains('static const Color accent'));
      expect(src, contains('static const Color purple'),
          reason: '两个 token 必须各自独立声明，不得其一别名到另一个');
    });

    test('紫与 text2 是对既有 token 的别名，不是复制的字面量', () {
      // 值一致的必须别名回全局，避免日后全局调色时这里悄悄脱节。
      expect(identical(ShopColors.purple, AppColors.mint), isTrue);
      expect(identical(ShopColors.text2, AppColors.ink2), isTrue);
    });

    test('设计稿 ink-2 已改名为 inkLight，与 AppColors.ink2 不同值', () {
      // 命名陷阱：两个 ink2 同名不同义，改名是刻意的。这条测试锁住改名不被「还原」。
      expect(ShopColors.inkLight, const Color(0xFF3A3154));
      expect(ShopColors.inkLight, isNot(AppColors.ink2));
    });
  });

  group('密度设计的两个不可退让常量', () {
    test('灰缝是 3px —— 全局 AppSpacing 没有 3，不许就近取 2 或 4', () {
      expect(kShopGutter, 3.0);
    });

    test('灰缝色 == 页面底色（区块靠露出底色分隔，不靠边距）', () {
      expect(ShopColors.bg, const Color(0xFFF3F1F8));
    });
  });
}
