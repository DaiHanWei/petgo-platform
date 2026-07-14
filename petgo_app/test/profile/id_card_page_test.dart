import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/id_card_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/features/profile/presentation/id_card/id_card_placeholder.dart';
import 'package:tailtopia/features/profile/presentation/id_card/ktp_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/ktp_card_back.dart';
import 'package:tailtopia/features/profile/presentation/id_card_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 有状态假 repo：getMyIdCard 返当前态；generate() 记次数并翻成 generated。
class _FakeIdCardRepo implements IdCardRepository {
  _FakeIdCardRepo(this._data);
  IdCardData? _data;
  int generateCalls = 0;

  @override
  Future<IdCardData?> getMyIdCard() async => _data;

  @override
  Future<IdCardData> generate() async {
    generateCalls++;
    _data = IdCardData(
      generated: true,
      serialId: 123,
      name: _data?.name,
      petType: _data?.petType,
      breed: _data?.breed,
      birthday: _data?.birthday,
    );
    return _data!;
  }

  int purchaseCalls = 0;
  HdPayChannel? lastChannel;

  @override
  Future<HdPurchaseResult> purchaseHd(HdPayChannel channel) async {
    purchaseCalls++;
    lastChannel = channel;
    return const HdPurchaseResult(unlocked: true);
  }
}

Future<_FakeIdCardRepo> _pump(WidgetTester tester, IdCardData? initial,
    {PetProfile? profile, void Function(String)? onShare}) async {
  final repo = _FakeIdCardRepo(initial);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      idCardRepositoryProvider.overrideWithValue(repo),
      // 默认无档案 → 无分享按钮、不触网络（页面 build 会 watch petProfileProvider）。
      petProfileProvider.overrideWith((ref) async => profile),
      shareServiceProvider.overrideWithValue(
          (text, {sharePositionOrigin}) async => onShare?.call(text)),
    ],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: IdCardPage(),
    ),
  ));
  await tester.pumpAndSettle();
  return repo;
}

const _generatedCat = IdCardData(
  generated: true,
  serialId: 123,
  name: 'Mochi',
  petType: 'CAT',
  breed: 'British',
);

void main() {
  testWidgets('无档案 → 空态引导（无生成 CTA）', (tester) async {
    await _pump(tester, null);
    expect(find.text('No pet profile yet'), findsOneWidget);
    expect(find.text('Create ID Card'), findsNothing);
  });

  testWidgets('老用户未生成 → 引导态 + 生成 CTA 调 generate 后渲染卡', (tester) async {
    final repo = await _pump(
        tester, const IdCardData(generated: false, name: 'Mochi', petType: 'CAT'));
    expect(find.text('Create ID Card'), findsOneWidget);
    expect(find.byType(KtpCardFront), findsNothing);

    await tester.tap(find.text('Create ID Card'));
    await tester.pumpAndSettle();

    expect(repo.generateCalls, 1);
    expect(find.byType(KtpCardFront), findsOneWidget); // 生成后渲染 KTP
  });

  testWidgets('已生成 → 渲染 KTP + 三风格切换器；切 Paspor 显占位', (tester) async {
    await _pump(tester, _generatedCat);
    expect(find.byType(KtpCardFront), findsOneWidget);
    expect(find.text('KTP'), findsWidgets);
    expect(find.text('Passport'), findsOneWidget);

    await tester.tap(find.text('Passport'));
    await tester.pumpAndSettle();
    expect(find.byType(IdCardComingSoon), findsOneWidget);
    expect(find.byType(KtpCardFront), findsNothing);
  });

  testWidgets('翻面 → KTP 背面含娱乐仿制免责', (tester) async {
    await _pump(tester, _generatedCat);
    await tester.tap(find.text('Flip'));
    await tester.pumpAndSettle();
    expect(find.byType(KtpCardBack), findsOneWidget);
    expect(find.text('For Entertainment Only'), findsOneWidget);
  });

  testWidgets('未解锁 HD → 点解锁弹 paywall → 选 PawCoin 调 purchaseHd', (tester) async {
    final repo = await _pump(tester, _generatedCat); // hdUnlocked 默认 false
    expect(find.text('Unlock HD download'), findsOneWidget);

    await tester.tap(find.text('Unlock HD download'));
    await tester.pumpAndSettle();
    expect(find.text('Pay with PawCoin'), findsOneWidget); // paywall 弹出

    await tester.tap(find.text('Pay with PawCoin'));
    // 不 pumpAndSettle：购买成功后自动导出的 toImage 在测试无 runAsync 不结算；
    // purchaseHd 在导出前已完成，pump 一帧即可断言其被调用。
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(repo.purchaseCalls, 1);
    expect(repo.lastChannel, HdPayChannel.pawcoin);
  });

  testWidgets('已解锁 HD → 按钮显「下载」直接导出（不弹 paywall）', (tester) async {
    final repo = await _pump(
        tester,
        const IdCardData(
            generated: true, serialId: 1, name: 'Mochi', petType: 'CAT', hdUnlocked: true));
    expect(find.text('Download HD'), findsOneWidget);
    await tester.tap(find.text('Download HD'));
    await tester.pump();
    expect(repo.purchaseCalls, 0); // 已解锁不再购买
    expect(find.text('Pay with PawCoin'), findsNothing); // 不弹 paywall
  });

  testWidgets('有 cardToken → 分享按钮渲染，点击分享 /p/ 拉新链接', (tester) async {
    String? shared;
    await _pump(tester, _generatedCat,
        profile: const PetProfile(id: 1, name: 'Mochi', cardToken: 'tok-abc', petType: 'CAT'),
        onShare: (t) => shared = t);
    expect(find.byKey(const ValueKey('idCardShareButton')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('idCardShareButton')));
    await tester.pump();
    expect(shared, isNotNull);
    expect(shared!.contains('/p/tok-abc'), isTrue); // 分享名片落地页拉新链接
  });

  testWidgets('无 cardToken → 无分享按钮', (tester) async {
    await _pump(tester, _generatedCat); // profile 默认 null
    expect(find.byKey(const ValueKey('idCardShareButton')), findsNothing);
  });

  testWidgets('AC3：会话编辑改预览、绝不重新生成/写档案', (tester) async {
    final repo = await _pump(tester, _generatedCat);

    await tester.tap(find.text('Edit'));
    await tester.pumpAndSettle();
    // 编辑 Nama → Momo
    await tester.enterText(find.byType(TextField).first, 'Momo');
    await tester.tap(find.text('Done'));
    await tester.pumpAndSettle();

    expect(find.text('MOMO'), findsOneWidget); // 预览反映编辑（大写）
    expect(repo.generateCalls, 0); // 编辑绝不触发生成/任何写
  });
}
