package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import com.artverse.security.PasswordHashService;
import com.artverse.storage.ObjectStorageService;
import com.artverse.storage.StoredObject;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserProfileService {
    public static final int MAX_AVATAR_BYTES = 20 * 1024;
    private final UserRepository userRepository;
    private final PasswordHashService passwordHashService;
    private final ObjectStorageService objectStorageService;
    private final UserSessionService userSessionService;

    @Transactional
    public User updateName(long userId, String username) {
        String normalized = username == null ? "" : username.trim();
        if (normalized.length() < 2 || normalized.length() > 50) {
            throw new BusinessException(400, "用户名长度需在 2 到 50 个字符之间");
        }
        User user = lockedUser(userId);
        if (!normalized.equals(user.getUsername()) && userRepository.existsByUsername(normalized)) {
            throw new BusinessException(409, "用户名已被占用，请换一个用户名");
        }
        user.setUsername(normalized);
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(409, "用户名已被占用，请换一个用户名");
        }
    }

    @Transactional
    public User saveAvatar(long userId, MultipartFile image) {
        if (image == null || image.isEmpty() || image.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(400, "头像不能为空且不能超过 20KB");
        }
        byte[] bytes;
        try (var input = image.getInputStream()) {
            bytes = input.readNBytes(MAX_AVATAR_BYTES + 1);
        } catch (IOException ex) {
            throw new BusinessException(400, "无法读取头像图片");
        }
        if (bytes.length == 0 || bytes.length > MAX_AVATAR_BYTES) {
            throw new BusinessException(400, "头像不能为空且不能超过 20KB");
        }
        String format = validateImage(bytes);
        User user = lockedUser(userId);
        String previousBucket = user.getAvatarBucket();
        String previousKey = user.getAvatarKey();
        String contentType = "image/" + format;
        StoredObject uploaded = objectStorageService.put("avatars/" + userId + "/" + UUID.randomUUID() + "." + format,
                new ByteArrayInputStream(bytes), bytes.length, contentType);
        boolean synchronizedTransaction = TransactionSynchronizationManager.isSynchronizationActive();
        if (synchronizedTransaction) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { deleteObject(previousBucket, previousKey); }
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) deleteObject(uploaded.bucket(), uploaded.objectKey());
                }
            });
        }
        try {
            user.setAvatarBucket(uploaded.bucket());
            user.setAvatarKey(uploaded.objectKey());
            user.setAvatarContentType(contentType);
            User saved = userRepository.saveAndFlush(user);
            if (!synchronizedTransaction) deleteObject(previousBucket, previousKey);
            return saved;
        } catch (RuntimeException ex) {
            if (!synchronizedTransaction) deleteObject(uploaded.bucket(), uploaded.objectKey());
            throw ex;
        }
    }

    @Transactional
    public User deleteAvatar(long userId) {
        User user = lockedUser(userId);
        String bucket = user.getAvatarBucket(), key = user.getAvatarKey();
        user.setAvatarBucket(null);
        user.setAvatarKey(null);
        user.setAvatarContentType(null);
        User saved = userRepository.saveAndFlush(user);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { deleteObject(bucket, key); }
            });
        } else deleteObject(bucket, key);
        return saved;
    }

    @Transactional
    public void changePassword(long userId, String currentPassword, String newPassword, String confirmation) {
        if (newPassword == null || newPassword.isBlank()
                || newPassword.codePointCount(0, newPassword.length()) < 8
                || newPassword.codePointCount(0, newPassword.length()) > 16) {
            throw new BusinessException(400, "新密码需要 8–16 个字符");
        }
        if (!newPassword.equals(confirmation)) throw new BusinessException(400, "两次输入的新密码不一致");
        if (newPassword.equals(currentPassword)) throw new BusinessException(400, "新密码不能与当前密码相同");
        User user = lockedUser(userId);
        if (!passwordHashService.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(400, "当前密码不正确");
        }
        user.setPasswordHash(passwordHashService.encode(newPassword));
        userRepository.saveAndFlush(user);
        // A failure revoking sessions aborts the database change as well.
        userSessionService.invalidateAll(userId);
    }

    private User lockedUser(long userId) {
        return userRepository.findByIdForProfileUpdate(userId)
                .orElseThrow(() -> new BusinessException(404, "账号不存在"));
    }

    private void deleteObject(String bucket, String key) {
        if (bucket != null && key != null) objectStorageService.deleteBestEffort(bucket, key);
    }

    private String validateImage(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessException(400, "头像图片格式无效");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg") && !format.equals("gif")) {
                    throw new BusinessException(400, "请上传 JPG、PNG 或 GIF 头像");
                }
                reader.setInput(input);
                int frames = reader.getNumImages(true);
                if (frames < 1 || frames > 100) throw new BusinessException(400, "头像动画帧数过多");
                long pixels = 0;
                for (int i = 0; i < frames; i++) {
                    int width = reader.getWidth(i), height = reader.getHeight(i);
                    pixels += (long) width * height;
                    if (width < 1 || height < 1 || width > 512 || height > 512 || pixels > 4_194_304) {
                        throw new BusinessException(400, "头像尺寸过大，请使用 512×512 以内的图片");
                    }
                    if (reader.read(i) == null) throw new BusinessException(400, "头像图片无法解码");
                }
                return format;
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException ex) {
            throw new BusinessException(400, "头像图片无法解码，请更换图片");
        }
    }
}
