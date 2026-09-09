# static/admin/vendor

后台自托管的第三方前端库（无 CDN 外链；静态资源走内容指纹，模板里一律 `th:src="@{/admin/vendor/…}"`）。

| 文件 | 版本 | 许可 | 来源 | 引入 |
|---|---|---|---|---|
| `htmx.min.js` | 1.9.12（D-31 不升级） | BSD-2 | https://unpkg.com/htmx.org@1.9.12/dist/htmx.min.js | `layout.html` 全站 |
| `chart.umd.min.js` | Chart.js 4.5.1 | MIT | npm `chart.js@4.5.1` → `dist/chart.umd.min.js`（https://registry.npmjs.org/chart.js/-/chart.js-4.5.1.tgz，sha256 `48444a82d4edcb5bec0f1965faacdde18d9c17db3063d042abada2f705c9f54a`） | 仅看板页 `dashboard.html`（V1.3.0 Story 3.4） |

升级时整文件替换并更新本表；不要手改压缩文件。
