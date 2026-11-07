package com.aittest.auth;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 无状态 token 的签发与验签（HMAC-SHA256，见架构图 {@code TokenService}）。
 *
 * <p>为什么不用 JWT 库：本项目只需"服务端自签自验 + 无法伪造 + 能过期"三个能力，
 * 一个 HMAC 摘要足够；引 jjwt 反而多一层依赖与算法选择面（{@code alg=none} 类攻击面）。
 * 格式：{@code base64url(payloadJson) + "." + base64url(hmacSha256(payload))}。</p>
 *
 * <p><b>密钥纪律</b>：{@code auth.token.secret} 缺省为空串，此时启动期用
 * {@link SecureRandom} 生成一次性密钥并打 WARN —— 这样"克隆即跑"不破功，
 * 但重启后旧 token 全部失效；生产必须通过环境变量注入。</p>
 *
 * @author 寇豆码
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    /** 摘要算法。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 密钥随机字节数（256 bit）。 */
    private static final int SECRET_BYTES = 32;

    /** URL 安全 Base64（不带 padding）。 */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** 实际使用的 HMAC 密钥。 */
    private final byte[] secret;

    /** 有效期（秒）。 */
    private final long ttlSeconds;

    public TokenService(@Value("${auth.token.secret:}") String configuredSecret,
                        @Value("${auth.token.ttl-hours:168}") long ttlHours) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] randomSecret = new byte[SECRET_BYTES];
            new SecureRandom().nextBytes(randomSecret);
            this.secret = randomSecret;
            log.warn("未配置 auth.token.secret，已生成一次性随机密钥：服务重启后所有 token 失效。"
                    + "生产环境请通过环境变量 AUTH_TOKEN_SECRET 注入。");
        } else {
            // 允许任意长度口令：先做一次 SHA-256 归一到固定长度密钥
            this.secret = sha256(configuredSecret.getBytes(StandardCharsets.UTF_8));
        }
        this.ttlSeconds = Math.max(ttlHours, 1L) * 3600L;
    }

    /**
     * 签发 token。
     *
     * @param user 登录用户
     * @return token + 过期时间
     */
    public IssuedToken issue(LoginUser user) {
        Instant now = Instant.now();
        long expiresAt = now.getEpochSecond() + ttlSeconds;
        TokenPayload payload = new TokenPayload();
        payload.uid = user.id();
        payload.uname = user.username();
        payload.name = user.displayName();
        payload.iat = now.getEpochSecond();
        payload.exp = expiresAt;

        String payloadJson = JsonUtils.toJson(payload);
        String encodedPayload = ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signature = ENCODER.encodeToString(hmac(encodedPayload));
        return new IssuedToken(encodedPayload + "." + signature, expiresAt);
    }

    /**
     * 验签并解析 token。
     *
     * @param token 客户端传入的 token
     * @return 登录用户
     * @throws BizException 缺失/格式错/签名不符/已过期，统一 40102
     */
    public LoginUser verify(String token) {
        if (token == null || token.isBlank()) {
            throw BizException.unauthorized();
        }
        int dotIndex = token.indexOf('.');
        if (dotIndex <= 0 || dotIndex == token.length() - 1) {
            throw new BizException(ErrorCode.E_TOKEN_INVALID);
        }
        String encodedPayload = token.substring(0, dotIndex);
        String signature = token.substring(dotIndex + 1);

        byte[] expected = hmac(encodedPayload);
        byte[] actual;
        try {
            actual = DECODER.decode(signature);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.E_TOKEN_INVALID);
        }
        // 定长比较，防时序侧信道
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BizException(ErrorCode.E_TOKEN_INVALID);
        }

        TokenPayload payload;
        try {
            String payloadJson = new String(DECODER.decode(encodedPayload), StandardCharsets.UTF_8);
            payload = JsonUtils.parse(payloadJson, TokenPayload.class);
        } catch (RuntimeException ex) {
            throw new BizException(ErrorCode.E_TOKEN_INVALID);
        }
        if (payload == null || payload.uid == null || payload.exp == null) {
            throw new BizException(ErrorCode.E_TOKEN_INVALID);
        }
        if (payload.exp <= Instant.now().getEpochSecond()) {
            throw new BizException(ErrorCode.E_TOKEN_INVALID, "登录已过期，请重新登录");
        }
        return new LoginUser(payload.uid, payload.uname == null ? "" : payload.uname,
                payload.name == null ? "" : payload.name);
    }

    /**
     * 计算 HMAC-SHA256。
     *
     * @param data 待签名内容
     * @return 摘要字节
     */
    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException ex) {
            // 环境不支持 HmacSHA256 属于致命配置错误，直接失败而非降级
            throw new IllegalStateException("初始化 HMAC 失败", ex);
        }
    }

    /**
     * SHA-256 归一化。
     *
     * @param input 输入字节
     * @return 32 字节摘要
     */
    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("初始化 SHA-256 失败", ex);
        }
    }

    /**
     * 签发结果。
     *
     * @param token     token 字符串
     * @param expiresAt 过期时间（epoch 秒）
     */
    public record IssuedToken(String token, long expiresAt) {
    }

    /**
     * token 载荷（字段名短、JSON 小；Jackson 直接读写 public 字段）。
     */
    static class TokenPayload {

        /** 用户 ID。 */
        public Long uid;

        /** 登录名。 */
        public String uname;

        /** 显示名。 */
        public String name;

        /** 签发时间（epoch 秒）。 */
        public Long iat;

        /** 过期时间（epoch 秒）。 */
        public Long exp;
    }
}
