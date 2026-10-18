import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_purchase_result.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/features/pet_slot/data/pet_slot_repository.dart';
import 'package:tailtopia/features/pet_slot/domain/pet_slot_purchase.dart';
import 'package:tailtopia/features/pet_slot/presentation/add_pet_flow.dart';
import 'package:tailtopia/features/pet_slot/presentation/pet_slot_page.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 multi-pet Story 2.4 测试共用：名额摘要 / 宠物 / 假仓库 / 页面挂载。

PetListItem pet(int id, String name, {bool readOnly = false, String petType = 'CAT'}) => PetListItem(
      id: id,
      name: name,
      petType: petType,
      birthday: DateTime(2025, 1, 1),
      status: readOnly ? PetListItem.statusReadOnly : PetListItem.statusActive,
      isDefault: id == 1,
    );

PetSlotRental rental(int index, DateTime expiresAt, {int daysLeft = 20}) => PetSlotRental(
      token: 'r$index',
      index: index,
      expiresAt: expiresAt,
      daysLeft: daysLeft,
    );

PetSlotOverview overview({
  int defaultSlots = 1,
  int levelSlots = 1,
  List<PetSlotRental> rentals = const [],
  required int petCount,
  int readOnlyCount = 0,
  int unitPrice = 5000,
  int days = 30,
  int? renewableMax,
}) {
  final total = defaultSlots + levelSlots + rentals.length;
  return PetSlotOverview(
    total: total,
    cap: 5,
    defaultSlots: defaultSlots,
    levelSlots: levelSlots,
    rentalSlots: rentals.length,
    petCount: petCount,
    rentable: 5 - total,
    canRent: total < 5,
    unitPrice: unitPrice,
    days: days,
    readOnlyCount: readOnlyCount,
    shortBy: petCount > total ? petCount - total : 0,
    renewableMax: renewableMax ?? (5 - 1 - levelSlots),
    rentals: rentals,
  );
}

class FakePetSlotRepo extends PetSlotRepository {
  FakePetSlotRepo() : super(Dio());

  final List<({int quantity, String channel, String key})> rents = [];
  final List<({List<String> tokens, String channel, String key})> renews = [];
  final List<List<int>> selections = [];
  final List<String> statusQueries = [];

  KeepsakePurchaseResult rentResult = const KeepsakePurchaseResult(unlocked: true, purchaseToken: 'p1');
  Object? rentError;
  Object? selectionError;

  /// 购买状态序列（每次查询取下一个，取完重复最后一个）。
  List<PetSlotPurchaseStatus> statuses = [
    PetSlotPurchaseStatus(status: PetSlotPurchaseStatus.paid, expiresAt: DateTime.utc(2026, 11, 12, 5)),
  ];

  @override
  Future<KeepsakePurchaseResult> rent({
    required int quantity,
    required HdPayChannel channel,
    required String idempotencyKey,
  }) async {
    rents.add((quantity: quantity, channel: channel.wire, key: idempotencyKey));
    if (rentError != null) throw rentError!;
    return rentResult;
  }

  @override
  Future<KeepsakePurchaseResult> renew({
    required List<String> rentalTokens,
    required HdPayChannel channel,
    required String idempotencyKey,
  }) async {
    renews.add((tokens: rentalTokens, channel: channel.wire, key: idempotencyKey));
    if (rentError != null) throw rentError!;
    return rentResult;
  }

  @override
  Future<PetSlotPurchaseStatus> purchaseStatus(String purchaseToken) async {
    statusQueries.add(purchaseToken);
    final i = statusQueries.length - 1;
    return statuses[i < statuses.length ? i : statuses.length - 1];
  }

  @override
  Future<List<PetListItem>> changeSelection(List<int> petIds) async {
    selections.add(petIds);
    if (selectionError != null) throw selectionError!;
    return const [];
  }
}

class FakePaw extends PawCoinController {
  FakePaw(this.balance);

  final int balance;

  @override
  Future<PawCoinState> build() async => PawCoinState(balance: balance, items: const []);
}

/// 409 ProblemDetail 形态的 DioException。
DioException problem(String slug, {int status = 409, Map<String, dynamic> extra = const {}}) {
  final req = RequestOptions(path: '/x');
  return DioException(
    requestOptions: req,
    type: DioExceptionType.badResponse,
    response: Response<dynamic>(
      requestOptions: req,
      statusCode: status,
      data: <String, dynamic>{'type': 'https://petgo/errors/$slug', 'status': status, ...extra},
    ),
  );
}

/// 挂名额管理页（`/me/pet-slots[?action=]`）。[overviews] 依次作为每次取摘要的结果（Exception 则抛），
/// 取完重复最后一个。返回 provider 容器。
Future<ProviderContainer> pumpSlotPage(
  WidgetTester tester, {
  required List<Object> overviews,
  required List<PetListItem> pets,
  FakePetSlotRepo? repo,
  int balance = 100000,
  String? action,
  Locale locale = const Locale('id'),
}) async {
  tester.view.physicalSize = const Size(420, 1600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  var loads = 0;
  final c = ProviderContainer(
    retry: (_, _) => null,
    overrides: [
      petSlotOverviewProvider.overrideWith((ref) async {
        final o = overviews[loads < overviews.length ? loads : overviews.length - 1];
        loads++;
        if (o is Exception) throw o;
        return o as PetSlotOverview;
      }),
      petListProvider.overrideWith((ref) async => pets),
      petSlotRepositoryProvider.overrideWithValue(repo ?? FakePetSlotRepo()),
      pawCoinProvider.overrideWith(() => FakePaw(balance)),
    ],
  );
  addTearDown(c.dispose);
  final router = GoRouter(
    initialLocation: action == null ? '/' : '/?action=$action',
    routes: [
      GoRoute(path: '/', builder: (_, s) => PetSlotPage(action: s.uri.queryParameters['action'])),
      GoRoute(path: '/me/pawcoin/recharge', builder: (_, _) => const Scaffold(body: Text('recharge'))),
      GoRoute(path: kPetSlotsRoute, builder: (_, _) => const Scaffold(body: Text('slots'))),
    ],
  );
  addTearDown(router.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: c,
    child: MaterialApp.router(
      routerConfig: router,
      locale: locale,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
  return c;
}
