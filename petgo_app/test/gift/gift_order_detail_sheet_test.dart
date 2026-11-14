import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/gift/data/gift_repository.dart';
import 'package:tailtopia/features/gift/domain/gift_order_detail.dart';
import 'package:tailtopia/features/gift/presentation/gift_order_detail_sheet.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：礼物详情抽屉 H1 ~ H4 + 举报 H5 / H6（V1.5.0 gift-pack Story 2-6）。结构断言，不写 golden。
class _Repo extends GiftRepository {
  _Repo() : super(dio: Dio());

  Object detail = const GiftOrderDetail(orderToken: 't', petId: 1, petName: 'Mochi', giftNameEn: 'A', giftNameId: 'A');
  int detailCalls = 0;
  final List<(String, String)> reports = [];
  Object? reportError;

  @override
  Future<GiftOrderDetail> fetchGiftOrderDetail(String orderToken) async {
    detailCalls++;
    final d = detail;
    if (d is GiftOrderDetail) return d;
    throw d;
  }

  @override
  Future<void> reportGiftMessage(String orderToken, String reasonWire) async {
    reports.add((orderToken, reasonWire));
    final e = reportError;
    if (e != null) throw e;
  }
}

class _LoggedInAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(nickname: 'Aurel', petStatus: 'HAS_PET', hasPetProfile: true, onboardingCompleted: true),
      );

  @override
  Future<void> ensureRestored() => Future<void>.value();
}

GiftOrderDetail _detail({
  String? message = 'Happy birthday Mochi',
  bool hidden = false,
  bool canReport = true,
  bool deleted = false,
  List<String> pets = const ['Chiko', 'Shiro'],
  bool? opened = true,
  int qty = 1,
}) =>
    GiftOrderDetail(
      orderToken: 'tok-1',
      petId: 42,
      petName: 'Mochi',
      giftNameEn: 'Strawberry',
      giftNameId: 'Stroberi',
      quantity: qty,
      receivedAt: DateTime(2026, 10, 28, 12),
      opened: opened,
      message: message,
      messageHidden: hidden,
      canReport: canReport,
      sender: deleted
          ? const GiftDetailSender(deleted: true)
          : GiftDetailSender(userId: 7, nickname: 'Michelle', petNames: pets),
    );

void main() {
  late _Repo repo;
  setUpAll(() => initializeDateFormatting('id'));
  setUp(() => repo = _Repo());

  Future<void> pump(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        giftRepositoryProvider.overrideWithValue(repo),
        authControllerProvider.overrideWith(_LoggedInAuth.new),
      ],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(onPressed: () => showGiftOrderDetailSheet(context, 'tok-1'), child: const Text('go')),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('go'));
    await tester.pumpAndSettle();
  }

  testWidgets('H1 / H2：标题、日期、数量、祝福语、送礼人 + 宠物、Lihat profil、⋯；无「未拆」字样', (tester) async {
    repo.detail = _detail(qty: 10, opened: null);
    await pump(tester);
    expect(find.text('Stroberi untuk Mochi'), findsOneWidget);
    expect(find.text('Diterima 28 Oktober 2026'), findsOneWidget);
    expect(find.text('×10'), findsOneWidget);
    expect(find.text('Happy birthday Mochi'), findsOneWidget);
    expect(find.text('dari Michelle · bersama Chiko, Shiro', findRichText: true), findsOneWidget);
    expect(find.text('Lihat profil Michelle'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftDetailMore')), findsOneWidget);
    expect(find.textContaining('terbungkus'), findsNothing);
    expect(find.textContaining('Belum dibuka'), findsNothing);
  });

  testWidgets('数量 1 不显示 ×1；送礼人无宠物 → 不显示「bersama」段', (tester) async {
    repo.detail = _detail(pets: const []);
    await pump(tester);
    expect(find.byKey(const ValueKey('giftDetailQuantity')), findsNothing);
    expect(find.text('dari Michelle', findRichText: true), findsOneWidget);
    expect(find.textContaining('bersama', findRichText: true), findsNothing);
  });

  testWidgets('H3：祝福语已隐藏 → 隐藏文案，礼物照常', (tester) async {
    repo.detail = _detail(message: null, hidden: true, canReport: false);
    await pump(tester);
    expect(find.text('Ucapan ini disembunyikan karena melanggar pedoman komunitas.'), findsOneWidget);
    expect(find.text('Stroberi untuk Mochi'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftDetailMore')), findsNothing);
  });

  testWidgets('H4：送礼人已注销 → dari Pengguna terhapus，无宠物行、无 Lihat profil', (tester) async {
    repo.detail = _detail(deleted: true);
    await pump(tester);
    expect(find.text('dari Pengguna terhapus', findRichText: true), findsOneWidget);
    expect(find.textContaining('bersama', findRichText: true), findsNothing);
    expect(find.byKey(const ValueKey('giftDetailSenderProfile')), findsNothing);
  });

  testWidgets('无祝福语 / canReport = false → 无祝福语区、无 ⋯', (tester) async {
    repo.detail = _detail(message: null, canReport: false);
    await pump(tester);
    expect(find.byKey(const ValueKey('giftDetailMessage')), findsNothing);
    expect(find.byKey(const ValueKey('giftDetailMessageHidden')), findsNothing);
    expect(find.byKey(const ValueKey('giftDetailMore')), findsNothing);
  });

  testWidgets('加载失败 → Gagal memuat hadiah + Coba lagi，重试成功', (tester) async {
    repo.detail = DioException(requestOptions: RequestOptions(path: '/'));
    await pump(tester);
    expect(find.text('Gagal memuat hadiah'), findsOneWidget);
    repo.detail = _detail();
    await tester.tap(find.text('Coba lagi'));
    await tester.pumpAndSettle();
    expect(find.text('Stroberi untuk Mochi'), findsOneWidget);
    expect(repo.detailCalls, 2);
  });

  testWidgets('H5 / H6：⋯ → Laporkan ucapan → 原举报抽屉（文案不改）→ 提交调到祝福语举报接口', (tester) async {
    repo.detail = _detail();
    await pump(tester);
    await tester.tap(find.byKey(const ValueKey('giftDetailMore')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Laporkan ucapan'));
    await tester.pumpAndSettle();
    expect(find.text('Laporkan konten ini'), findsOneWidget);
    await tester.tap(find.text('Lainnya'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Kirim Laporan'));
    await tester.pumpAndSettle();
    expect(repo.reports, [('tok-1', 'OTHER')]);
    expect(find.text('Laporan diterima'), findsOneWidget);
  });

  testWidgets('举报失败（如 422）→ 通用失败 toast，抽屉留在表单态', (tester) async {
    repo.detail = _detail();
    final ro = RequestOptions(path: '/');
    repo.reportError =
        DioException(requestOptions: ro, response: Response<dynamic>(requestOptions: ro, statusCode: 422));
    await pump(tester);
    await tester.tap(find.byKey(const ValueKey('giftDetailMore')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Laporkan ucapan'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Lainnya'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Kirim Laporan'));
    await tester.pump();
    expect(find.text('Terjadi kesalahan, coba lagi'), findsOneWidget);
    await tester.pumpAndSettle(const Duration(seconds: 3));
    expect(find.text('Laporan diterima'), findsNothing);
    expect(find.text('Kirim Laporan'), findsOneWidget);
  });
}
