import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/features/profile/data/id_card_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/domain/id_card_style.dart';
import 'package:tailtopia/features/profile/domain/pet_breeds.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/id_card/hd_paywall_sheet.dart';
import 'package:tailtopia/features/profile/presentation/id_card/ktp_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/passport_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/student_variant_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card_create_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 ktp Story 1.7：建卡页去 Tab、按卡款填表、身份证专用物种 / 品种下拉、必填置灰、提交口径与埋点。
class _Repo implements IdCardRepository {
  _Repo({this.fail = false, this.numberReused = false, this.purchaseFails = false});
  final bool fail;
  final bool numberReused;
  final bool purchaseFails;
  CreateIdCardRequest? last;

  @override
  Future<IdCard> createCard(CreateIdCardRequest req) async {
    last = req;
    if (fail) throw Exception('boom');
    return IdCard.fromJson({
      'id': 9,
      'serialId': 9,
      'name': req.name,
      'cardType': req.cardType,
      'cardVariant': req.cardVariant,
      'numberReused': numberReused,
    });
  }

  @override
  Future<HdPurchaseResult> purchaseHdForCard(int cardId, HdPayChannel channel) async {
    if (purchaseFails) throw Exception('boom');
    return const HdPurchaseResult(unlocked: true);
  }

  @override
  Future<IdCard> getCard(int cardId) async => IdCard(id: cardId, hdUnlocked: !purchaseFails);

  @override
  Future<int> hdPrice() async => 5000;
  @override
  Future<List<IdCard>> listCards() async => const [];
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Paw extends PawCoinController {
  @override
  Future<PawCoinState> build() async => const PawCoinState(balance: 50000, items: []);
}

PetProfile _profile({String? sex = 'FEMALE', String? petType = 'CAT', String? breed = 'Persia'}) => PetProfile(
      id: 1,
      name: 'Oyen',
      cardToken: 'tok',
      petType: petType,
      breed: breed,
      birthday: DateTime(2024, 3, 10),
      sex: sex,
      intro: 'manja',
    );

Future<void> _pump(WidgetTester tester, IdCardStyle style, {required _Repo repo, PetProfile? profile}) async {
  tester.view.physicalSize = const Size(420, 900);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final router = GoRouter(routes: [
    GoRoute(path: '/', builder: (c, s) => const Scaffold(body: Text('list'))),
    GoRoute(
        path: '/profile/id-cards/create',
        builder: (c, s) => IdCardCreatePage(initialStyle: IdCardCreatePage.styleFromQuery(s.uri.queryParameters['style']))),
    GoRoute(
        path: '/profile/id-cards/:id',
        builder: (c, s) => Scaffold(
            body: TextButton(
                onPressed: () => c.pop(),
                child: Text('detail:${s.pathParameters['id']}:${s.uri.queryParameters['export'] ?? ''}')))),
  ]);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      idCardRepositoryProvider.overrideWithValue(repo),
      pawCoinProvider.overrideWith(_Paw.new),
      petProfileProvider.overrideWith((ref) async => profile ?? _profile()),
    ],
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
    ),
  ));
  router.push(IdCardCreatePage.locationFor(style));
  await tester.pumpAndSettle();
}

Future<void> _openForm(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('idCardEditInfo')));
  await tester.pumpAndSettle();
}

/// 点「Buat Kartu」。成功后付费抽屉打开期间按钮转圈持续动画（现状），不能 pumpAndSettle，按固定时长推进。
Future<void> _submit(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('idCardCreateSubmit')));
  for (var i = 0; i < 10; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<void> _done(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('idCardEditDone')));
  await tester.pumpAndSettle();
}

void main() {
  final events = <(String, Map<String, Object>?)>[];
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  testWidgets('无样式 Tab；预览按卡款渲染（KTP / Paspor / Bintang）', (tester) async {
    for (final (style, front) in [
      (IdCardStyle.ktp, KtpCardFront),
      (IdCardStyle.passport, PassportCardFront),
      (IdCardStyle.studentBintang, StudentVariantCardFront),
    ]) {
      await _pump(tester, style, repo: _Repo());
      expect(find.byKey(const ValueKey('idCardStyleTab_0')), findsNothing);
      expect(find.byType(front), findsOneWidget, reason: '$style');
    }
  });

  testWidgets('五套表单字段：出现 / 不出现；任何卡款都没有一句话介绍', (tester) async {
    final cases = <IdCardStyle, (List<String>, List<String>)>{
      IdCardStyle.ktp: (
        ['idCardEditName', 'idCardSpeciesField', 'idCardBreedField', 'idCardEditBirthday', 'idCardEditBirthCity',
          'idCardEditAddress', 'idCardEditOccupation', 'idCardEditMarital'],
        ['idCardEditSchool', 'idCardEditFaculty']
      ),
      IdCardStyle.passport: (
        ['idCardEditName', 'idCardSpeciesField', 'idCardEditBirthday', 'idCardEditBirthCity'],
        ['idCardBreedField', 'idCardEditAddress', 'idCardEditOccupation', 'idCardEditMarital', 'idCardEditSchool']
      ),
      IdCardStyle.kitas: (
        ['idCardEditName', 'idCardSpeciesField', 'idCardBreedField', 'idCardEditBirthday'],
        ['idCardEditBirthCity', 'idCardEditAddress', 'idCardEditSchool']
      ),
      IdCardStyle.sim: (
        ['idCardEditName', 'idCardSpeciesField', 'idCardEditBirthday', 'idCardEditBirthCity', 'idCardEditAddress',
          'idCardEditOccupation'],
        ['idCardBreedField', 'idCardEditMarital', 'idCardEditSchool']
      ),
      IdCardStyle.studentMalam: (
        ['idCardEditName', 'idCardSpeciesField', 'idCardEditBirthday', 'idCardEditSchool', 'idCardEditFaculty'],
        ['idCardBreedField', 'idCardEditBirthCity', 'idCardEditAddress']
      ),
    };
    for (final MapEntry(key: style, value: (present, absent)) in cases.entries) {
      await _pump(tester, style, repo: _Repo());
      await _openForm(tester);
      for (final k in present) {
        expect(find.byKey(ValueKey(k)), findsOneWidget, reason: '$style 应有 $k');
      }
      for (final k in [...absent, 'idCardEditIntro']) {
        expect(find.byKey(ValueKey(k)), findsNothing, reason: '$style 不应有 $k');
      }
      expect(find.byKey(const ValueKey('idCardGender_MALE')), findsOneWidget);
      await _done(tester);
    }
  });

  testWidgets('档案无 sex → 三个性别都不选、表单红字、建卡按钮置灰 + 缺项提示', (tester) async {
    await _pump(tester, IdCardStyle.passport, repo: _Repo(), profile: _profile(sex: null));
    final submit = tester.widget<FilledButton>(find.byKey(const ValueKey('idCardCreateSubmit')));
    expect(submit.onPressed, isNull);
    expect(find.text('Fill in gender first'), findsOneWidget);
    expect(find.byKey(const ValueKey('idCardCreateMissingHint')), findsOneWidget);
    await _openForm(tester);
    expect(find.text('Please choose a gender'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('idCardGender_MALE')));
    await tester.pumpAndSettle();
    expect(find.text('Please choose a gender'), findsNothing);
    await _done(tester);
    expect(find.byKey(const ValueKey('idCardCreateMissingHint')), findsNothing);
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('idCardCreateSubmit'))).onPressed, isNotNull);
  });

  testWidgets('档案 sex = FEMALE → 预选；档案无物种 → 物种红字 + 提示含 species', (tester) async {
    await _pump(tester, IdCardStyle.passport, repo: _Repo(), profile: _profile(petType: null));
    expect(find.text('Fill in species first'), findsOneWidget);
    await _openForm(tester);
    expect(find.text('Please choose a species'), findsOneWidget);
    expect(find.text('Select type'), findsOneWidget);
    // 档案 FEMALE → FEMALE chip 选中（紫底），其余未选。
    Color chipColor(String wire) => (tester
            .widget<Container>(find.descendant(of: find.byKey(ValueKey('idCardGender_$wire')), matching: find.byType(Container)).first)
            .decoration! as BoxDecoration)
        .color!;
    expect(chipColor('FEMALE'), AppColors.mint);
    expect(chipColor('MALE'), isNot(AppColors.mint));
    expect(chipColor('UNKNOWN'), isNot(AppColors.mint));
    expect(find.text('Please choose a gender'), findsNothing);
  });

  testWidgets('物种下拉在页面内展开、当前项打勾；选 DOG 清空品种', (tester) async {
    await _pump(tester, IdCardStyle.ktp, repo: _Repo());
    await _openForm(tester);
    await tester.tap(find.byKey(const ValueKey('idCardSpeciesField')));
    await tester.pumpAndSettle();
    for (final w in ['CAT', 'DOG', 'OTHER']) {
      expect(find.byKey(ValueKey('idCardSpeciesOption_$w')), findsOneWidget);
    }
    expect(find.byType(BottomSheet), findsOneWidget, reason: '只有编辑抽屉本身，没有再弹一层');
    expect(find.descendant(of: find.byKey(const ValueKey('idCardSpeciesOption_CAT')), matching: find.byIcon(Icons.check_rounded)),
        findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('idCardSpeciesOption_DOG')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('idCardSpeciesOption_CAT')), findsNothing, reason: '选中即收起');
    await _done(tester);
    // KTP 品种被清空 → 置灰并提示 breed。
    expect(find.text('Fill in breed first'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('idCardCreateSubmit'))).onPressed, isNull);
  });

  testWidgets('品种下拉：Lainnya 原位展开输入框，输入后 Selesai → 预览品种更新', (tester) async {
    await _pump(tester, IdCardStyle.ktp, repo: _Repo(), profile: _profile(breed: kCatBreeds.first));
    expect(find.byKey(const ValueKey('idCardBreedCustomInput')), findsNothing);
    await _openForm(tester);
    await tester.tap(find.byKey(const ValueKey('idCardBreedField')));
    await tester.pumpAndSettle();
    // 菜单约 5 项高、超出在菜单内滚动：「Lainnya」是末项，滚到它。
    final menu = find.descendant(of: find.byKey(const ValueKey('idCardBreedMenu')), matching: find.byType(Scrollable));
    expect(tester.getSize(menu).height, lessThanOrEqualTo(44 * 5 + 0.5));
    await tester.scrollUntilVisible(find.byKey(const ValueKey('idCardBreedOption_OTHER')), 100, scrollable: menu);
    expect(find.byKey(const ValueKey('idCardBreedOption_OTHER')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('idCardBreedOption_OTHER')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('idCardBreedCustomInput')), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('idCardBreedCustomInput')), 'Kampung');
    await tester.pumpAndSettle();
    await _done(tester);
    expect(find.text('KAMPUNG'), findsOneWidget, reason: 'KTP 卡面「Ras」显示新品种（卡面统一大写）');
  });

  testWidgets('档案自定义品种（不在清单）→ Lainnya 打勾、输入框预填', (tester) async {
    await _pump(tester, IdCardStyle.kitas, repo: _Repo(), profile: _profile(breed: 'Oren Liar'));
    await _openForm(tester);
    expect(find.byKey(const ValueKey('idCardBreedCustomInput')), findsOneWidget);
    expect(find.widgetWithText(TextField, 'Oren Liar'), findsOneWidget);
  });

  testWidgets('KTP 品种为空置灰；Paspor 品种为空不影响', (tester) async {
    await _pump(tester, IdCardStyle.ktp, repo: _Repo(), profile: _profile(breed: null));
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('idCardCreateSubmit'))).onPressed, isNull);
    await _pump(tester, IdCardStyle.passport, repo: _Repo(), profile: _profile(breed: null));
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('idCardCreateSubmit'))).onPressed, isNotNull);
  });

  testWidgets('提交：学生卡四款带 cardVariant、无 intro、字段集外为 null；form_opened / created 埋点', (tester) async {
    for (final style in [
      IdCardStyle.studentBintang,
      IdCardStyle.studentMalam,
      IdCardStyle.studentCeria,
      IdCardStyle.studentKlasik,
    ]) {
      events.clear();
      final repo = _Repo(numberReused: style == IdCardStyle.studentCeria);
      await _pump(tester, style, repo: repo);
      await _openForm(tester);
      await _done(tester);
      await _submit(tester);
      final req = repo.last!;
      expect(req.cardType, 'STUDENT');
      expect(req.cardVariant, style.cardVariant);
      final json = req.toJson();
      expect(json.containsKey('intro'), isFalse);
      expect(json.containsKey('breed'), isFalse, reason: '学生卡不采集品种（档案品种不提交）');
      expect(json.containsKey('birthCity'), isFalse);
      expect(json['gender'], 'FEMALE');
      expect(json['petType'], 'CAT');
      expect(events.firstWhere((e) => e.$1 == 'id_card_form_opened').$2, {'card_type': 'STUDENT'});
      expect(events.firstWhere((e) => e.$1 == 'id_card_created').$2, {
        'card_type': 'STUDENT',
        'card_variant': style.cardVariant!,
        'number_reused': style == IdCardStyle.studentCeria,
      });
      // Story 1.8（AD-10 / D-15）：建卡后弹付费抽屉 → 关闭 → 替换到新卡详情（未解锁，不自动导出）；返回 → 列表。
      expect(find.byType(HdPaywallSheet), findsOneWidget);
      await tester.tapAt(const Offset(10, 10));
      await tester.pumpAndSettle();
      expect(find.text('detail:9:'), findsOneWidget);
      expect(find.byType(IdCardCreatePage), findsNothing, reason: '建卡页已出栈');
      await tester.tap(find.text('detail:9:'));
      await tester.pumpAndSettle();
      expect(find.text('list'), findsOneWidget, reason: '详情返回直接回列表，不回建卡页');
      expect(events.where((e) => e.$1 == 'id_card_paywall_dismissed').single.$2,
          {'card_type': 'STUDENT', 'entry': 'create'});
      expect(tester.takeException(), isNull);
    }
  });

  testWidgets('提交 KTP：card_variant 不带键；字段集内选填照传', (tester) async {
    final repo = _Repo();
    await _pump(tester, IdCardStyle.ktp, repo: repo);
    await _openForm(tester);
    await tester.enterText(find.byKey(const ValueKey('idCardEditAddress')), 'Jl. Kenanga 1');
    await _done(tester);
    await _submit(tester);
    expect(repo.last!.toJson()['address'], 'Jl. Kenanga 1');
    expect(repo.last!.toJson()['breed'], 'Persia');
    expect(repo.last!.toJson().containsKey('cardVariant'), isFalse);
    expect(events.firstWhere((e) => e.$1 == 'id_card_created').$2,
        {'card_type': 'KTP', 'number_reused': false});
  });

  testWidgets('提交失败：SnackBar + 预览仍在、按钮恢复', (tester) async {
    await _pump(tester, IdCardStyle.passport, repo: _Repo(fail: true));
    await tester.tap(find.byKey(const ValueKey('idCardCreateSubmit')));
    await tester.pumpAndSettle();
    expect(find.text('Could not create the card. Please try again.'), findsOneWidget);
    expect(find.byType(PassportCardFront), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('idCardCreateSubmit'))).onPressed, isNotNull);
    expect(events.where((e) => e.$1 == 'id_card_created'), isEmpty);
  });

  testWidgets('迟到的档案预填不覆盖用户已确认的编辑', (tester) async {
    final gate = Completer<PetProfile?>();
    tester.view.physicalSize = const Size(420, 900);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (c, s) => const IdCardCreatePage(initialStyle: IdCardStyle.passport)),
    ]);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        idCardRepositoryProvider.overrideWithValue(_Repo()),
        pawCoinProvider.overrideWith(_Paw.new),
        petProfileProvider.overrideWith((ref) => gate.future),
      ],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
      ),
    ));
    await tester.pumpAndSettle();
    await _openForm(tester);
    await tester.enterText(find.byKey(const ValueKey('idCardEditName')), 'Belang');
    await _done(tester);
    gate.complete(_profile());
    await tester.pumpAndSettle();
    await _openForm(tester);
    expect(find.widgetWithText(TextField, 'Belang'), findsOneWidget);
  });

  testWidgets('建卡后 PawCoin 付费成功 → 替换到新卡详情并带 export=1（D-16），toast 可见', (tester) async {
    final repo = _Repo();
    await _pump(tester, IdCardStyle.sim, repo: repo, profile: _profile());
    await _submit(tester);
    expect(find.byType(HdPaywallSheet), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
    await tester.pumpAndSettle();
    expect(find.text('detail:9:1'), findsOneWidget);
    expect(find.text('HD download unlocked!'), findsOneWidget);
    expect(events.where((e) => e.$1 == 'id_card_buka_hd_tapped'), isEmpty, reason: 'create 入口不报 buka_hd_tapped');
  });

  testWidgets('建卡后付费失败 → 先提示失败，再替换到新卡详情（D-15）', (tester) async {
    final repo = _Repo(purchaseFails: true);
    await _pump(tester, IdCardStyle.passport, repo: repo, profile: _profile());
    await _submit(tester);
    await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
    await tester.pumpAndSettle();
    expect(find.text('detail:9:'), findsOneWidget);
    expect(find.text('Payment could not be completed. Please try again.'), findsOneWidget);
  });
}
