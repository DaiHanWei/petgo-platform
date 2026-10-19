import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/content/domain/content_pet_ref.dart';
import 'package:tailtopia/features/gift/data/gift_repository.dart';
import 'package:tailtopia/features/gift/domain/gift_catalog.dart';
import 'package:tailtopia/features/gift/domain/gift_showcase.dart';
import 'package:tailtopia/features/gift/presentation/widgets/gift_entry.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'gift_fakes.dart';

/// V1.4.0 gift Story 4.1 · AC4 / AC5（L0）：详情页礼物区 A7「选择送给哪只」与多宠行。
void main() {
  late _PerPetRepo repo;
  late List<(String, Map<String, Object>?)> events;

  setUp(() {
    repo = _PerPetRepo();
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  const a = GiftPetSummary(petId: 11, name: 'Aaa', giftCount: 3, kindCount: 1);
  const b = GiftPetSummary(petId: 22, name: 'Bbb');
  const linked = [
    ContentPetRef(petId: 11, name: 'Aaa', petType: 'CAT'),
    ContentPetRef(petId: 22, name: 'Bbb', petType: 'DOG'),
  ];

  Future<void> pump(WidgetTester tester, List<GiftPetSummary> pets,
      {bool showSend = true, VoidCallback? onDelivered}) async {
    tester.view.physicalSize = const Size(1600, 2600);
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        authControllerProvider.overrideWith(() => _Auth()),
        giftRepositoryProvider.overrideWithValue(repo),
        pawCoinProvider.overrideWith(() => FakePawCoin(() => 100000, [])),
      ],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: SingleChildScrollView(
            child: GiftDetailZone(
                postId: 77,
                pets: pets,
                linkedPets: linked,
                showSendButton: showSend,
                onDelivered: onDelivered),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  Finder sendButton() => find.byKey(const ValueKey('giftDetailSendButton'));
  Finder picker() => find.byKey(const ValueKey('petPickerTitle'));
  Finder panel() => find.byKey(const ValueKey('giftPanelLoaded'));

  bool radioOn(WidgetTester tester, int id) =>
      tester.widget<Icon>(find.byKey(ValueKey('petPickerRadio-$id'))).icon == Icons.check_circle;

  testWidgets('两只 → 每只一行（giftPets 顺序）+ 只有一个「Kirim Hadiah」', (tester) async {
    await pump(tester, const [a, b]);
    final ya = tester.getTopLeft(find.byKey(const ValueKey('giftPetRow_11'))).dy;
    final yb = tester.getTopLeft(find.byKey(const ValueKey('giftPetRow_22'))).dy;
    expect(ya, lessThan(yb));
    expect(find.text('3 hadiah'), findsOneWidget);
    expect(find.text('Belum ada hadiah. Jadi yang pertama!'), findsOneWidget);
    expect(sendButton(), findsOneWidget);
  });

  testWidgets('两只 → A7 弹出、默认第一只、副标题物种；选 B「Lanjut」→ A7 关、B1 开且送给 B', (tester) async {
    await pump(tester, const [a, b]);
    await tester.tap(sendButton());
    await tester.pumpAndSettle();
    expect(picker(), findsOneWidget);
    expect(find.text('Kirim hadiah untuk siapa?'), findsOneWidget);
    expect(find.text('Pilih satu hewan.'), findsOneWidget);
    expect(find.text('Kucing'), findsOneWidget);
    expect(find.text('Anjing'), findsOneWidget);
    expect(radioOn(tester, 11), isTrue, reason: '默认选中第一只');
    expect(radioOn(tester, 22), isFalse);
    expect(panel(), findsNothing, reason: '选宠物时不叠送礼面板');
    expect(repo.catalogArgs, isEmpty);

    await tester.tap(find.byKey(const ValueKey('petPickerRow-22')));
    await tester.pump();
    expect(radioOn(tester, 22), isTrue);
    await tester.tap(find.byKey(const ValueKey('petPickerConfirm')));
    await tester.pumpAndSettle();

    expect(picker(), findsNothing, reason: '先关 A7');
    expect(panel(), findsOneWidget, reason: '再开 B1');
    expect(find.text('Kirim hadiah untuk Bbb'), findsOneWidget);
    expect(repo.catalogArgs.single, (petId: 22, postId: null), reason: '按所选 petId 取目录');
    expect(events.where((e) => e.$1 == 'gift_panel_opened').single.$2,
        {'source': 'content_detail', 'pet_id': 22, 'is_multi_pet_post': true});
  });

  testWidgets('A7 下滑关闭 = 不送（不开面板、不取目录）', (tester) async {
    await pump(tester, const [a, b]);
    await tester.tap(sendButton());
    await tester.pumpAndSettle();
    await tester.tapAt(const Offset(400, 20));
    await tester.pumpAndSettle();
    expect(picker(), findsNothing);
    expect(panel(), findsNothing);
    expect(repo.catalogArgs, isEmpty);
  });

  testWidgets('只读宠物照常可选（不置灰、不带锁）', (tester) async {
    await pump(tester, const [a, b]);
    await tester.tap(sendButton());
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.block), findsNothing);
    expect(find.byKey(const ValueKey('petCardRowLock')), findsNothing);
  });

  testWidgets('一只 → 直接 B1（不弹 A7），按该只 petId；埋点 is_multi_pet_post=false', (tester) async {
    await pump(tester, const [a]);
    await tester.tap(sendButton());
    await tester.pumpAndSettle();
    expect(picker(), findsNothing);
    expect(panel(), findsOneWidget);
    expect(find.text('Kirim hadiah untuk Aaa'), findsOneWidget);
    expect(repo.catalogArgs.single, (petId: 11, postId: null));
    expect(events.where((e) => e.$1 == 'gift_panel_opened').single.$2,
        {'source': 'content_detail', 'pet_id': 11, 'is_multi_pet_post': false});
  });

  testWidgets('L2 r2 P1-1：送达 → 回调 onDelivered（详情页据此重拉、礼物区刷新）', (tester) async {
    var delivered = 0;
    await pump(tester, const [a], onDelivered: () => delivered++);
    await tester.tap(sendButton());
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('giftSendButton')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
    await tester.pumpAndSettle();
    await tester.pump(const Duration(seconds: 3));
    expect(delivered, 1);
  });

  testWidgets('自己的 Diary（A6）→ 无「Kirim Hadiah」，宠物行照常', (tester) async {
    await pump(tester, const [a, b], showSend: false);
    expect(sendButton(), findsNothing);
    expect(find.byKey(const ValueKey('giftPetRow_22')), findsOneWidget);
  });
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');
}

/// 按 petId 返回对应收礼宠物的目录（面板标题取 `recipient.name`）。
class _PerPetRepo extends FakeGiftRepository {
  _PerPetRepo() : super();

  static const _names = {11: 'Aaa', 22: 'Bbb'};

  @override
  Future<GiftCatalog> fetchCatalog({int? petId, int? postId}) async {
    await super.fetchCatalog(petId: petId, postId: postId);
    final base = sampleCatalog(petName: _names[petId] ?? 'Mochi');
    return GiftCatalog(
      recipient: GiftRecipient(petId: petId ?? 0, name: base.recipient.name, petType: 'CAT'),
      qrisMinAmount: base.qrisMinAmount,
      tiers: base.tiers,
      exclusiveGroups: base.exclusiveGroups,
      lockBlock: base.lockBlock,
    );
  }
}
