package com.tailtopia.shared.media;

import com.aliyuncs.CommonRequest;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;

/**
 * 阿里云 CDN 缓存刷新（专题线 video Story 1.4 · AD-11）：{@code aliyun-java-sdk-core} 通用请求调
 * {@code RefreshObjectCaches}。RAM 只需 {@code cdn:RefreshObjectCaches}。
 *
 * <p>CDN 刷新本身是异步的（「10 分钟内失效」在 Story 3.4 的 L2 实测）。异常向上抛，由对账器按单条失败重试。
 * 凭证复用 {@code media.access-key-*}（env 注入），不进日志。
 * 不加 {@code @Component}：由 {@link MediaConfig#cdnCacheRefresher} 在配了 CDN 时装配。
 */
public class AliyunCdnCacheRefresher implements CdnCacheRefresher {

    /** CDN 是全局服务，region 固定。 */
    static final String REGION = "cn-hangzhou";
    static final String DOMAIN = "cdn.aliyuncs.com";
    static final String VERSION = "2018-05-10";

    private final MediaProperties props;
    private volatile IAcsClient client;

    public AliyunCdnCacheRefresher(MediaProperties props) {
        this.props = props;
    }

    /** 测试用（专题线 video Story 3.4 · L0 打桩）：直接注入 SDK 客户端，不懒建。 */
    AliyunCdnCacheRefresher(MediaProperties props, IAcsClient client) {
        this.props = props;
        this.client = client;
    }

    /**
     * 刷新单个视频 URL 的 CDN 缓存。失败（含服务端返回的 {@code ServerException}，它是 {@code ClientException}
     * 的子类）一律抛出，由对账器不回写 applied、记漂移、下一轮重试。异常信息只带 errorCode，不带 URL / 凭证。
     */
    @Override
    public void refresh(String url) {
        try {
            acsClient().getCommonResponse(request(url));
        } catch (ClientException e) {
            throw new IllegalStateException("CDN 刷新失败 errorCode=" + e.getErrCode(), e);
        }
    }

    /** 请求形状（抽出来供 L0 断言）。 */
    static CommonRequest request(String url) {
        CommonRequest req = new CommonRequest();
        req.setSysMethod(MethodType.POST);
        req.setSysDomain(DOMAIN);
        req.setSysVersion(VERSION);
        req.setSysAction("RefreshObjectCaches");
        req.putQueryParameter("ObjectPath", url);
        req.putQueryParameter("ObjectType", "File");
        return req;
    }

    private IAcsClient acsClient() {
        IAcsClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = new DefaultAcsClient(DefaultProfile.getProfile(
                            REGION, props.getAccessKeyId(), props.getAccessKeySecret()));
                    client = c;
                }
            }
        }
        return c;
    }
}
