package com.tailtopia.shared.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** L0：对外 URL 拼接 + E4 服务端 EXIF 兜底样式（纯函数）。 */
class AliyunOssClientTest {

    private AliyunOssClient client() {
        MediaProperties props = new MediaProperties();
        props.getOss().setCdnBaseUrl("https://cdn.petgo.example");
        props.getOss().setPublicBucket("petgo-public");
        props.getOss().setPrivateBucket("petgo-private");
        return new AliyunOssClient(props);
    }

    @Test
    void publicUrlJoinsCdnBaseAndKey() {
        assertThat(client().publicUrl("public/42/x.jpg"))
                .isEqualTo("https://cdn.petgo.example/public/42/x.jpg");
    }

    @Test
    void publicUrlHandlesLeadingSlash() {
        assertThat(client().publicUrl("/public/42/x.jpg"))
                .isEqualTo("https://cdn.petgo.example/public/42/x.jpg");
    }

    @Test
    void exifStrippedUrlAppendsProcessStyle() {
        String url = client().publicExifStrippedUrl("public/42/x.jpg");
        assertThat(url).startsWith("https://cdn.petgo.example/public/42/x.jpg");
        assertThat(url).contains("x-oss-process=image/");
    }

    // ------------------------------------------------------------ D-2：公开桶对象 ACL

    /**
     * 🔴 公开桶对象**必须**带 {@code x-oss-object-acl: public-read}（D-2，2026-09-02 stag）。
     *
     * <p>桶级并非公开读（BPA 关闭 + 桶 ACL 私有）⇒ 不带这个标记的对象<b>上传成功、公网 403</b>。
     * 表现是 App 里商品图空占位、后台素材墙裂图，而上传链路一声不响 ——
     * stag 上 93 个商品里 10 张就是这么挂的。
     */
    @Test
    void publicObjectMetadataAlwaysCarriesPublicReadAcl() {
        var meta = AliyunOssClient.publicObjectMetadata("image/png", 88);

        assertThat(meta.getRawMetadata().get(AliyunOssClient.PUBLIC_READ_HEADER))
                .as("🔴 少了这个头 = 上传成功但公网 403，且没有任何一处会报错")
                .isEqualTo(AliyunOssClient.PUBLIC_READ_VALUE);
        assertThat(meta.getContentType()).isEqualTo("image/png");
        assertThat(meta.getContentLength()).isEqualTo(88);
    }

    /**
     * 🔴 公开桶只能有**一条**上传原语（D-2）。
     *
     * <h2>这条守的是 D-2 的成因</h2>
     * 原先并存两个方法：{@code putPublicObject}（不带 ACL）与 {@code putPublicObjectWithAcl}（带）。
     * 于是四条服务端上传线里<b>三条挑中了不带 ACL 的那个</b>（商品图/banner/种子图、兽医头像、
     * 标签图标、OG 图），只有 Lark 转存是对的。而预签名直传把 ACL 签进了头 ——
     * 同一个前缀下于是有的 200、有的 403，看起来像「偶发」，其实是按上传路径分的。
     *
     * <p>⚠️ 判据钉在**元数据只有一处构造**上：只要还能 `new ObjectMetadata()` 另起一份，
     * 下一条上传线就能再绕过去一次，而且同样**上传成功、测试全绿**，只有公网取图才 403。
     */
    @Test
    void publicBucketHasExactlyOneUploadPrimitive() throws IOException {
        String code = codeOf(Path.of("src", "main", "java", "com", "tailtopia", "shared",
                "media", "AliyunOssClient.java"));

        assertThat(count(code, "new com.aliyun.oss.model.ObjectMetadata()"))
                .as("🔴 元数据出现了第二处构造 —— 那一处很可能又忘了带 ACL（D-2 的原形）。"
                        + "统一走 publicObjectMetadata()")
                .isEqualTo(1);
        assertThat(code)
                .as("🔴 往公开桶 putObject 时没有走 publicObjectMetadata() ⇒ 产出的对象公网 403")
                .contains("publicObjectMetadata(");
    }

    /**
     * 🔴 **除了这个薄封装，谁也不许直接调 OSS SDK 的 putObject**（D-2）。
     *
     * <p>ACL 这类「漏了也不报错、只有公网取图才 403」的约定，只有收口在唯一出口上才守得住。
     * 绕过封装自己 putObject 的那一次，必然又是一批 403 对象，而且照例全绿。
     */
    @Test
    void nobodyElseCallsOssPutObjectDirectly() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src", "main", "java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.getFileName().toString().equals("AliyunOssClient.java")) {
                    continue; // 唯一出口
                }
                if (codeOf(f).contains(".putObject(")) {
                    offenders.add(root.relativize(f).toString());
                }
            }
        }
        assertThat(offenders)
                .as("🔴 绕过 AliyunOssClient 直接调 SDK putObject —— 对象级 ACL 必然漏，"
                        + "而上传会「成功」，只有公网取图才 403（D-2）")
                .isEmpty();
    }

    /** 源码正文（剥掉注释与 import）—— 注释里逐字提到被废弃的写法是常事，连注释一起扫会误报。 */
    private static String codeOf(Path f) throws IOException {
        return Files.readString(f, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .lines()
                .filter(l -> !l.strip().startsWith("//") && !l.strip().startsWith("import "))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
