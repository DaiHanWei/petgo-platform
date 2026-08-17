import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_cart.dart';
import 'package:tailtopia/features/shop/data/shop_review_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_product_detail.dart';
import 'package:tailtopia/features/shop/domain/shop_review.dart';
import 'package:tailtopia/features/shop/presentation/product_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 7.3 · L0：详情页评价列表与空态（FR-106 / UX-DR9）。
///
/// 🔴 本组用例守的是**空态如实为空**。「不伪造或预填评价」不是防御性措辞：
/// 一个刚上架的商品就有五星好评，是最快毁掉整个评价区可信度的做法 ——
/// 而评价区的全部价值就在于它可信。
void main() {
  late _FakeReviewRepo reviewRepo;
  late _FakeShopRepo shopRepo;

  setUp(() {
    reviewRepo = _FakeReviewRepo();
    shopRepo = _FakeShopRepo();
  });

  Widget host() => ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(
                const AuthState(status: AuthStatus.authenticated, role: 'USER'),
              )),
          shopReviewRepositoryProvider.overrideWithValue(reviewRepo),
          // 详情页顶部有购物车角标，会拉 /me/cart —— 用假仓库挡住，
          // 否则测试会卡在一个真实 dio 请求上（表现为 Pending timers）
          cartRepositoryProvider.overrideWithValue(_FakeCartRepo()),
          // 直接 override 详情 provider（照既有 product_detail_page_test 的范式），
          // 避免页面里其它 provider 走真实 dio
          shopProductDetailProvider.overrideWith((ref, token) async => shopRepo.fixture),
          productReviewsProvider.overrideWith((ref, token) async {
            if (reviewRepo.shouldFail) throw Exception('boom');
            return reviewRepo.data;
          }),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: const ProductDetailPage(token: 'p1'),
        ),
      );

  Future<void> open(WidgetTester t) async {
    // 🔴 ListView 懒加载：评价区在页面很下面，画布不放大就根本没 build
    t.view.physicalSize = const Size(1200, 4000);
    t.view.devicePixelRatio = 1.0;
    addTearDown(() {
      t.view.resetPhysicalSize();
      t.view.resetDevicePixelRatio();
    });
    await t.pumpWidget(host());
    await t.pump();
    await t.pump();
  }

  testWidgets('🔴 无评价 → 展示空态，且【不伪造、不预填】任何评价内容', (t) async {
    reviewRepo.data = ProductReviews.empty;
    await open(t);

    expect(find.byKey(const ValueKey('tokoReviewsTitle')), findsOneWidget);
    expect(find.byKey(const ValueKey('tokoReviewsEmpty')), findsOneWidget);
    // 一条评价都不该有
    expect(find.byKey(const ValueKey('tokoReviewsSummary')), findsNothing);
    expect(find.byType(Icon).evaluate().where((e) {
      final icon = e.widget as Icon;
      return icon.icon == Icons.star || icon.icon == Icons.star_border;
    }), isEmpty, reason: '🔴 空态下不该出现任何星级 —— 那看起来就像预填了一条评价');
  });

  testWidgets('有评价 → 展示汇总（平均分 + 条数）与逐条内容', (t) async {
    reviewRepo.data = ProductReviews(
      total: 2,
      averageRating: 4.5,
      items: [
        ShopReviewItem(
          id: 1,
          rating: 5,
          content: 'Bagus banget',
          imageUrls: const [],
          createdAt: DateTime(2026, 8, 10),
        ),
        ShopReviewItem(id: 2, rating: 4, content: 'Lumayan', imageUrls: const []),
      ],
    );
    await open(t);

    expect(find.byKey(const ValueKey('tokoReviewsEmpty')), findsNothing);
    expect(find.byKey(const ValueKey('tokoReviewsSummary')), findsOneWidget);
    expect(find.byKey(const ValueKey('tokoReview_1')), findsOneWidget);
    expect(find.byKey(const ValueKey('tokoReview_2')), findsOneWidget);
    expect(find.text('Bagus banget'), findsOneWidget);
  });

  testWidgets('🔴 平均分缺失时只报条数 —— 绝不显示成「0 分」', (t) async {
    reviewRepo.data = ProductReviews(
      total: 1,
      items: [ShopReviewItem(id: 1, rating: 5, imageUrls: const [])],
    );
    await open(t);

    final summary = t.widget<Text>(find.byKey(const ValueKey('tokoReviewsSummary')));
    expect(summary.data!.contains('0'), isFalse,
        reason: '🔴 平均分为 null 时不能落成 0 —— 0 会被读成「零分」');
  });

  testWidgets('🔴 首版不做追评、不做商家回复：列表里没有任何回复结构', (t) async {
    reviewRepo.data = ProductReviews(
      total: 1,
      averageRating: 5,
      items: [ShopReviewItem(id: 1, rating: 5, content: 'Bagus', imageUrls: const [])],
    );
    await open(t);

    // 一条评价只渲染成一个 tile，没有嵌套的回复块
    expect(find.byKey(const ValueKey('tokoReview_1')), findsOneWidget);
    expect(find.textContaining('Balasan'), findsNothing);
  });

  testWidgets('评价接口失败 → 评价区不渲染，商品详情本身照常可用', (t) async {
    reviewRepo.shouldFail = true;
    await open(t);

    expect(find.byKey(const ValueKey('tokoReviewsTitle')), findsNothing);
    // 商品主体还在
    expect(find.text('Royal Canin Medium Adult'), findsOneWidget);
  });

  group('域模型', () {
    test('🔴 averageRating 解析成 null 而不是 0', () {
      final r = ProductReviews.fromJson(const {'total': 0, 'items': []});
      expect(r.averageRating, isNull);
      expect(r.isEmpty, isTrue);
    });

    test('缺字段不炸：图片列表缺省为空、内容可空', () {
      final item = ShopReviewItem.fromJson(const {'id': 7, 'rating': 3});
      expect(item.imageUrls, isEmpty);
      expect(item.content, isNull);
    });
  });
}

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);

  final AuthState _initial;

  @override
  AuthState build() => _initial;
}

class _FakeReviewRepo implements ShopReviewRepository {
  ProductReviews data = ProductReviews.empty;
  bool shouldFail = false;

  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<ProductReviews> forProduct(String productToken) async {
    if (shouldFail) throw Exception('boom');
    return data;
  }

  @override
  Future<ShopReviewItem> submit({
    required String orderToken,
    required int orderLineId,
    required int rating,
    String? content,
    List<String>? imageKeys,
  }) async =>
      ShopReviewItem(id: 1, rating: rating, imageUrls: const []);

  @override
  Future<ShopReviewItem> resubmit({
    required int reviewId,
    required int rating,
    String? content,
    List<String>? imageKeys,
  }) async =>
      ShopReviewItem(id: reviewId, rating: rating, imageUrls: const []);
}

class _FakeCartRepo implements CartRepository {
  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<CartView> view() async => CartView.empty;

  @override
  Future<CartView> add(String skuToken,
          {int qty = 1, String? entrySource, String? triggerType}) async =>
      CartView.empty;

  @override
  Future<CartView> setQty(String skuToken, int qty) async => CartView.empty;

  @override
  Future<CartView> remove(String skuToken) async => CartView.empty;

  @override
  Future<CartView> clearInvalid() async => CartView.empty;
}

class _FakeShopRepo {
  final ShopProductDetail fixture = const ShopProductDetail(
        token: 'p1',
        name: 'Royal Canin Medium Adult',
        brand: 'Royal Canin',
        returnPolicy: ReturnPolicy.returnable,
        skus: [
          ShopSku(
            token: 's1',
            specName: '3 kg',
            price: 285000,
            returnPolicy: ReturnPolicy.returnable,
            stockStatus: StockStatus.inStock,
          ),
        ],
        shelfLifeNote: 'Simpan di tempat kering',
      );
}
