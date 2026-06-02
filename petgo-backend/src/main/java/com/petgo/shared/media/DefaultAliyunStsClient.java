package com.petgo.shared.media;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import com.aliyuncs.sts.model.v20150401.AssumeRoleRequest;
import com.aliyuncs.sts.model.v20150401.AssumeRoleResponse;
import com.petgo.shared.error.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link AliyunStsClient} 的真实实现（阿里云 STS SDK）。仅 L2 真凭证下闭环；
 * L0/L1 通过 mock {@link AliyunStsClient} 覆盖业务逻辑。
 *
 * <p>护栏：accessKeySecret/securityToken 绝不进 INFO 日志；异常仅记录上游 code，不外泄堆栈。
 */
@Component
public class DefaultAliyunStsClient implements AliyunStsClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultAliyunStsClient.class);

    private final MediaProperties props;

    public DefaultAliyunStsClient(MediaProperties props) {
        this.props = props;
    }

    @Override
    public AssumedCredential assumeRole(String policy, long durationSeconds, String sessionName) {
        try {
            IClientProfile profile = DefaultProfile.getProfile(
                    props.getOss().getRegion(),
                    props.getAccessKeyId(),
                    props.getAccessKeySecret());
            IAcsClient client = new DefaultAcsClient(profile);

            AssumeRoleRequest request = new AssumeRoleRequest();
            request.setSysMethod(MethodType.POST);
            request.setRoleArn(props.getSts().getRoleArn());
            request.setRoleSessionName(sessionName);
            request.setPolicy(policy);
            request.setDurationSeconds(durationSeconds);

            AssumeRoleResponse response = client.getAcsResponse(request);
            AssumeRoleResponse.Credentials c = response.getCredentials();
            return new AssumedCredential(
                    c.getAccessKeyId(),
                    c.getAccessKeySecret(),
                    c.getSecurityToken(),
                    c.getExpiration());
        } catch (Exception e) {
            // 仅记录类型，绝不外泄凭证/堆栈给客户端。
            log.warn("STS AssumeRole failed: {}", e.getClass().getSimpleName());
            throw AppException.mediaCredential("媒体上传凭证暂不可用，请稍后再试");
        }
    }
}
