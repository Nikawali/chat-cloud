package org.example.auth.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RSA 工具：用私钥解密前端传来的密文，并把公钥下发给前端。
 *
 * <p><b>公钥是从私钥推导出来的</b>，不需要额外维护一个公钥文件——
 * 这样只有一处密钥来源，永远不会出现"前后端密钥不匹配"的问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RsaUtils {

    private static final String PRIVATE_KEY_LOCATION = "classpath:rsa/private_key.pem";

    private final ResourceLoader resourceLoader;

    /** 私钥只存在后端，绝不外泄 */
    private PrivateKey privateKey;

    /** 推导出来的公钥（PEM 格式），可以公开 */
    private String publicKeyPem;

    @PostConstruct
    void init() throws Exception {
        Resource resource = resourceLoader.getResource(PRIVATE_KEY_LOCATION);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "找不到 RSA 私钥文件：" + PRIVATE_KEY_LOCATION
                            + "。请先执行 chat-cloud/scripts/generate-rsa-keypair.cjs 生成密钥对。");
        }

        String pem;
        try (InputStream in = resource.getInputStream()) {
            pem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        this.privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));

        this.publicKeyPem = derivePublicKeyPem(this.privateKey);

        log.info("RSA 私钥加载完成，公钥已由私钥推导出来（可访问 /auth/public-key 获取）");
    }

    /**
     * 解密前端用公钥加密的密文。
     *
     * <p>算法用 {@code "RSA"}（即 RSA/ECB/PKCS1Padding），
     * 正好对应前端 jsencrypt 默认的填充方式，两边必须一致。
     *
     * @param cipherBase64 Base64 形式的密文
     * @return 解密后的明文字符串
     */
    public String decrypt(String cipherBase64) {
        try {
            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(cipherBase64));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("RSA 解密失败", e);
        }
    }

    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    /** 从私钥推导出公钥，并输出成 PEM（X.509 SubjectPublicKeyInfo，和前端 jsencrypt 兼容） */
    private static String derivePublicKeyPem(PrivateKey privateKey) throws Exception {
        RSAPrivateCrtKey crtKey = (RSAPrivateCrtKey) privateKey;
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));

        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(publicKey.getEncoded());

        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }
}
