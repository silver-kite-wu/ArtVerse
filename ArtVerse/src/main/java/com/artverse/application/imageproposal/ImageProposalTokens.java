package com.artverse.application.imageproposal;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** References bind a read snapshot; their signature is never author approval. */
@Component
public final class ImageProposalTokens {
    private static final String DOMAIN="artverse:image-proposal:v1";
    private final byte[] key;
    private final ObjectMapper mapper;
    public ImageProposalTokens(ArtVerseProperties properties,ObjectMapper mapper) {
        this.mapper=mapper;
        String secret=properties.getSecrets().getEncryptionKey();
        try {
            if(secret==null||secret.isBlank()){key=new byte[32];new SecureRandom().nextBytes(key);}
            else key=MessageDigest.getInstance("SHA-256").digest((DOMAIN+":"+secret).getBytes(StandardCharsets.UTF_8));
        }catch(GeneralSecurityException error){throw new IllegalStateException(error);}
    }
    String sign(Map<String,Object> claims) {
        try {
            String data=Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(claims));
            return data+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signature(data));
        }catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    JsonNode verify(String token) {
        try {
            if(token==null||token.length()>16384)throw new IllegalArgumentException();
            String[] parts=token.split("\\.",-1);
            if(parts.length!=2||!MessageDigest.isEqual(signature(parts[0]),Base64.getUrlDecoder().decode(parts[1])))throw new IllegalArgumentException();
            JsonNode value=mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if(!value.isObject()||value.path("version").asInt()!=1)throw new IllegalArgumentException();
            return value;
        }catch(Exception invalid){throw new BusinessException(400,"图片预览或分页引用无效，请重新读取");}
    }
    private byte[] signature(String value) {
        try {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal((DOMAIN+":"+value).getBytes(StandardCharsets.UTF_8));}
        catch(GeneralSecurityException error){throw new IllegalStateException(error);}
    }
}
