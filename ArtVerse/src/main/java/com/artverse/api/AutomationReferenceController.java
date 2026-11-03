package com.artverse.api;

import com.artverse.application.CurrentUserService;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.media.MediaStorageService;
import com.artverse.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/automations/references")
@RequiredArgsConstructor
public class AutomationReferenceController {
    private final CurrentUserService currentUser;
    private final MediaStorageService media;
    private final ObjectStorageService storage;
    private final ArtVerseProperties properties;
    @PostMapping public Map<String,String> upload(@RequestParam("file") MultipartFile file) throws java.io.IOException {
        long userId=currentUser.requireCurrentUser().getId();
        if(file.isEmpty()||file.getSize()>properties.getUpload().getMaxImageBytes())throw new BusinessException(400,"参考图片为空或超过大小限制");
        byte[] data=file.getBytes();media.validateImageBytes(data,properties.getUpload().getMaxImageBytes());
        Path path=Files.createTempFile("automation-upload-",".png");
        try {
            media.savePng(data,path);
            String key="automation_reference/"+userId+"/"+UUID.randomUUID()+".png";
            storage.putPng(key,path,"image/png");return Map.of("objectKey",key);
        } finally { Files.deleteIfExists(path); }
    }
}
