# static/admin/vendor

后台自托管的第三方前端库（无 CDN 外链；静态资源走内容指纹，模板里一律 `th:src="@{/admin/vendor/…}"`）。

| 文件 | 版本 | 许可 | 来源 | 引入 |
|---|---|---|---|---|
| `htmx.min.js` | 1.9.12（D-31 不升级） | BSD-2 | https://unpkg.com/htmx.org@1.9.12/dist/htmx.min.js | `layout.html` 全站 |
| `chart.umd.min.js` | Chart.js 4.5.1 | MIT | npm `chart.js@4.5.1` → `dist/chart.umd.min.js`（https://registry.npmjs.org/chart.js/-/chart.js-4.5.1.tgz，sha256 `48444a82d4edcb5bec0f1965faacdde18d9c17db3063d042abada2f705c9f54a`） | 仅看板页 `dashboard.html`（V1.3.0 Story 3.4） |
| `marked.umd.js` | marked 15.0.12 | MIT | npm `marked@15.0.12` → `lib/marked.umd.js`（https://registry.npmjs.org/marked/-/marked-15.0.12.tgz，sha256 `39a847a896af15b2846f927da55d444619ea55a55c236e10333056474b793aaa`；仅删去末行 sourceMappingURL） | 仅 ops AI 查数 `ai-query.html`（主题 ops-ai-query Story 1.7） |
| `purify.min.js` | DOMPurify 3.2.6 | Apache-2.0 / MPL-2.0 | npm `dompurify@3.2.6` → `dist/purify.min.js`（https://registry.npmjs.org/dompurify/-/dompurify-3.2.6.tgz，sha256 `fe734a97f5938a5ff7f99aedf348c53b8d47c897859fcc57ac4feaa70582c0b6`；仅删去末行 sourceMappingURL） | 同上 |

升级时整文件替换并更新本表；不要手改压缩文件。marked / DOMPurify 升级后须重跑 `petgo-backend/src/test/js` 的净化测试（NFR13）。
