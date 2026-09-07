/// 🔴🔴 问诊与商品之间**唯一被允许的关联**（Story 9.1 · FR-110 · **安全攸关**）。
///
/// FR-110 只开了一个口子：
/// > 仅允许「按结论中的结构化健康记录类型（如驱虫）跳转对应品类」这一种形式，**且由系统生成**。
/// > 🔴 兽医无法选择具体 SKU，无任何界面入口。
///
/// 所以这里的输入是**健康记录类型 code**、输出是**品类 code**，两头都是受控枚举串。
/// 🔴 **整条路径上不存在任何能携带商品/SKU 的形参** —— 这就是「能力缺席」：
/// 不是「兽医没权限选 SKU」，而是**根本没有一个地方能把 SKU 传进来**。
/// 权限能改、能绕；一个不存在的参数改不了也绕不过。
///
/// 后端同名守卫见 `com.tailtopia.shared.boundary.TriageCategoryJump`。
///
/// ⚠️ **刻意用 String 而不是 `ShopCategory`**：一旦这里 import `features/shop`，
/// 调用方就有了一条通往商品域的路。桥只让一个 code 通过，比让一个类型通过更难被撑大。
library;

class TriageCategoryJump {
  const TriageCategoryJump._();

  /// 品类 code（与后端 `ProductCategory` / `ShopCategory.api` 同一套字面量）。
  static const String _obatVitamin = 'OBAT_VITAMIN';

  /// 健康记录类型 code → 品类 code。**不在表里的一律返回 null（不跳）**。
  ///
  /// 🔴 只有驱虫与疫苗有对应品类。其余类型（生理期 / 绝育 / 自定义）
  /// **刻意不给跳转** —— 「每条结论都配一个购物入口」正是 FR-110 要防的形态。
  static String? categoryFor(String? recordType) => switch (recordType) {
        'DEWORM' || 'VACCINE' => _obatVitamin,
        _ => null,
      };

  static bool allowsJump(String? recordType) => categoryFor(recordType) != null;
}
