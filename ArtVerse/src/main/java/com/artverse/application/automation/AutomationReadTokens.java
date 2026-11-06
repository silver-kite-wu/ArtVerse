package com.artverse.application.automation;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/** Short-lived read references. A valid signature never grants permission to write or execute. */
@Component
public final class AutomationReadTokens {
    private static final String DOMAIN="artverse:automation-read:v1";
    private final byte[] key;
    private final ObjectMapper mapper;

    public AutomationReadTokens(ArtVerseProperties properties,ObjectMapper mapper) {
        this.mapper=mapper;
        String secret=properties.getSecrets().getEncryptionKey();
        try {
            if(secret==null||secret.isBlank()) {key=new byte[32];new SecureRandom().nextBytes(key);}
            else key=MessageDigest.getInstance("SHA-256").digest((DOMAIN+":"+secret).getBytes(StandardCharsets.UTF_8));
        } catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }

    String sign(Map<String,Object> claims) {
        try {
            String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(claims));
            return encoded+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signature(encoded));
        } catch(java.io.IOException error){throw new IllegalStateException("Cannot encode automation read reference",error);}
    }

    JsonNode verify(String token) {
        try {
            if(token==null||token.length()>8192)throw new IllegalArgumentException();
            String[] parts=token.split("\\.",-1);
            if(parts.length!=2||!MessageDigest.isEqual(signature(parts[0]),Base64.getUrlDecoder().decode(parts[1])))throw new IllegalArgumentException();
            JsonNode claims=mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if(!claims.isObject()||claims.path("version").asInt()!=1)throw new IllegalArgumentException();
            return claims;
        } catch(Exception invalid){throw new BusinessException(400,"计划只读引用无效，请重新预览或读取第一页");}
    }

    private byte[] signature(String encoded) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return mac.doFinal((DOMAIN+":"+encoded).getBytes(StandardCharsets.UTF_8));
        } catch(java.security.GeneralSecurityException error){throw new IllegalStateException(error);}
    }
}
