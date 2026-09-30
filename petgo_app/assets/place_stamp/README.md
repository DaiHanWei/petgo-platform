# assets/place_stamp —— 场所默认章（7 款类型兜底）

V1.3.2 batch-a Story 1.2（FR-120 · AD-5 · D-10 / D-12）。场所没有后台上传专属章时，按场所类型用这里的默认章。

**设计素材暂不入库**（决策日志 D-21）：素材到货后**只往本目录放文件、不改代码**即生效；
文件缺失时 App 画代码绘制的占位章（浅紫底 + 双圈 + 类型图标），不会崩。

| 文件名 | 场所类型（后端 `place_type`） |
|---|---|
| `cafe.png` | CAFE 咖啡店 |
| `restaurant.png` | RESTAURANT 餐厅 |
| `park.png` | PARK 公园 |
| `mall.png` | MALL 商场 |
| `hotel.png` | HOTEL 酒店民宿 |
| `pet_service.png` | PET_SERVICE 宠物服务 |
| `other.png` | OTHER 其他 |

规格：**透明底 PNG、正方形 1:1、建议 512×512**；按原色展示，客户端不着色、不做圆形裁切（D-10）。
同一张源文件要撑住 30–148 px 全部渲染尺寸（见设计资产清单 §4）。

映射代码：`lib/features/pet_passport/presentation/default_stamp_assets.dart`（文件名改动须同步那里）。
