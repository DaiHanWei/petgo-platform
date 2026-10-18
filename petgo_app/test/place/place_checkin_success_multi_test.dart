import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/pet_passport/data/pet_passport_repository.dart';
import 'package:tailtopia/features/pet_passport/domain/pet_passport.dart';
import 'package:tailtopia/features/place/domain/place_checkin_result.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';
import 'package:tailtopia/features/place/presentation/place_checkin_success_page.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/presentation/milestone_instant_celebration.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 multi-pet Story 3.3 · AC6：打卡成功页多只盖章（G3）。
void main() {
  PlaceCheckinResult result({bool isNew = true, int count = 1}) => PlaceCheckinResult(
        checkinToken: 'c' * 32,
        placeToken: 'p' * 32,
        placeName: 'Kopi Kucing',
        placeType: PlaceType.cafe,
        visitDate: DateTime(2026, 10, 18),
        isNewStamp: isNew,
        visitCount: count,
      );

  PlaceCheckinPetArgs pet(int id, String name, {bool isNew = true, int count = 1}) =>
      PlaceCheckinPetArgs(petId: id, name: name, isNewStamp: isNew, visitCount: count);

  Future<void> pump(WidgetTester tester, PlaceCheckinSuccessArgs args) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(400, 1400);
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        petPassportProvider.overrideWith((ref) => Future<PetPassport>.error(StateError('offline'))),
        milestoneInstantPollDelaysProvider.overrideWithValue(const []),
        petListProvider.overrideWith((ref) async => const [
              PetListItem(id: 1, name: 'Orenji'),
              PetListItem(id: 2, name: 'Kopi'),
              PetListItem(id: 3, name: 'Mochi'),
            ]),
        publishControllerProvider.overrideWithValue(
            PublishController(repository: _NoRepo(), uploadOne: (b) async => 'x')),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        home: PlaceCheckinSuccessPage(args: args),
      ),
    ));
    await tester.pumpAndSettle(const Duration(seconds: 2));
  }

  testWidgets('2 只都新章 → 两枚章 + 名字；副标题「Orenji & Kopi dapat cap baru」；不出护照单章块', (tester) async {
    await pump(
        tester,
        PlaceCheckinSuccessArgs(
            result: result(), petName: 'Orenji', pets: [pet(1, 'Orenji'), pet(2, 'Kopi')]));
    expect(find.byKey(const ValueKey('placeCheckinMultiStamps')), findsOneWidget);
    expect(find.byKey(const ValueKey('placeCheckinPetStamp-1')), findsOneWidget);
    expect(find.byKey(const ValueKey('placeCheckinPetStamp-2')), findsOneWidget);
    expect(find.text('Orenji & Kopi dapat cap baru'), findsOneWidget);
    expect(find.byKey(const ValueKey('placeCheckinStampDetail')), findsNothing);
    expect(find.byKey(const ValueKey('placeCheckinBookPlaceholder')), findsNothing);
    // 第一枚在左（创建顺序）。
    expect(tester.getCenter(find.byKey(const ValueKey('placeCheckinPetStamp-1'))).dx,
        lessThan(tester.getCenter(find.byKey(const ValueKey('placeCheckinPetStamp-2'))).dx));
    expect(find.byKey(const ValueKey('placeCheckinViewPassport')), findsOneWidget);
  });

  testWidgets('混合：新章的只拼新章宠物名；再访的名字后标 ×n', (tester) async {
    await pump(
        tester,
        PlaceCheckinSuccessArgs(
            result: result(isNew: false, count: 4),
            petName: 'Orenji',
            pets: [pet(1, 'Orenji', isNew: false, count: 4), pet(2, 'Kopi')]));
    expect(find.text('Kopi dapat cap baru'), findsOneWidget);
    expect(find.text('Orenji ×4'), findsOneWidget);
    expect(find.text('Kopi'), findsOneWidget);
  });

  testWidgets('全部再访 → 副标题 placeCheckinRepeatStamp(场所, 第一只的次数)；不出「Lihat Paspor」', (tester) async {
    await pump(
        tester,
        PlaceCheckinSuccessArgs(
            result: result(isNew: false, count: 2),
            petName: 'Orenji',
            pets: [pet(1, 'Orenji', isNew: false, count: 2), pet(3, 'Mochi', isNew: false, count: 5)]));
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(find.text(l10n.placeCheckinRepeatStamp('Kopi Kucing', 2)), findsOneWidget);
    expect(find.byKey(const ValueKey('placeCheckinViewPassport')), findsNothing);
  });

  testWidgets('3 只 → 横向可滚、不换行', (tester) async {
    await pump(
        tester,
        PlaceCheckinSuccessArgs(
            result: result(), petName: 'Orenji', pets: [pet(1, 'Orenji'), pet(2, 'Kopi'), pet(3, 'Mochi')]));
    final scroll = tester.widget<SingleChildScrollView>(find.byKey(const ValueKey('placeCheckinMultiStamps')));
    expect(scroll.scrollDirection, Axis.horizontal);
    final y = {
      for (final id in [1, 2, 3]) tester.getTopLeft(find.byKey(ValueKey('placeCheckinPetStamp-$id'))).dy,
    };
    expect(y, hasLength(1)); // 同一行
  });

  testWidgets('1 只：与改造前同一路径（简版章面 / 无多章行）', (tester) async {
    await pump(tester, PlaceCheckinSuccessArgs(result: result(), petName: 'Orenji', pets: [pet(1, 'Orenji')]));
    expect(find.byKey(const ValueKey('placeCheckinMultiStamps')), findsNothing);
    expect(find.byKey(const ValueKey('placeCheckinNewStamp')), findsOneWidget);
    expect(find.text('Orenji dapat cap baru'), findsOneWidget);
  });

  testWidgets('Rekam Momen Ini → 发布页 initialPetIds = 本次打卡全部宠物', (tester) async {
    await pump(
        tester,
        PlaceCheckinSuccessArgs(
            result: result(), petName: 'Orenji', pets: [pet(1, 'Orenji'), pet(3, 'Mochi')]));
    await tester.tap(find.byKey(const ValueKey('placeCheckinRecordMoment')));
    await tester.pumpAndSettle();
    final page = tester.widget<PublishComposePage>(find.byType(PublishComposePage));
    expect(page.initialPetIds, [1, 3]);
    expect(page.placeCheckinToken, 'c' * 32);
  });

  test('anyNewStamp：逐宠物任一新章即真；无逐宠物信息按顶层', () {
    expect(
        PlaceCheckinSuccessArgs(
                result: result(isNew: false), petName: 'a', pets: [pet(1, 'a', isNew: false), pet(2, 'b')])
            .anyNewStamp,
        isTrue);
    expect(PlaceCheckinSuccessArgs(result: result(isNew: false), petName: 'a').anyNewStamp, isFalse);
    expect(PlaceCheckinSuccessArgs(result: result(), petName: 'a').anyNewStamp, isTrue);
  });
}

class _NoRepo implements ContentRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
