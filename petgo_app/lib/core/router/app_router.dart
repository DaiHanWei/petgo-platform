import 'package:go_router/go_router.dart';
import 'package:petgo/shared/widgets/home_page.dart';

/// 应用路由表（脚手架：仅一个空白首页占位）。
/// 业务路由从后续 Story 起按 feature 追加。
final GoRouter appRouter = GoRouter(
  initialLocation: '/',
  routes: <RouteBase>[
    GoRoute(
      path: '/',
      builder: (context, state) => const HomePage(),
    ),
  ],
);
