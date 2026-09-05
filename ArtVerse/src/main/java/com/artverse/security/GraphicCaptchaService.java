package com.artverse.security;

import com.artverse.config.ArtVerseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphicCaptchaService implements HumanVerificationService {

    private static final String CAPTCHA_KEY_PREFIX = "auth:captcha:";
    private static final char[] CHARS = "abcdefghjkmnpqrstuvwxy23456789".toCharArray();

    private final ArtVerseProperties properties;
    private final StringRedisTemplate redisTemplate;

    @Override
    public VerificationResult verify(String action, String token, String remoteIp) {
        if (!isEnabled()) {
            return VerificationResult.unavailable("challenge-disabled");
        }
        if (token == null || token.isBlank()) {
            return VerificationResult.failure(List.of("missing-captcha"));
        }

        int separatorIndex = token.indexOf(':');
        if (separatorIndex <= 0 || separatorIndex >= token.length() - 1) {
            return VerificationResult.failure(List.of("invalid-captcha-format"));
        }

        String captchaId = token.substring(0, separatorIndex);
        String captchaCode = token.substring(separatorIndex + 1);

        String redisKey = CAPTCHA_KEY_PREFIX + captchaId;
        String storedCode = redisTemplate.opsForValue().get(redisKey);
        redisTemplate.delete(redisKey);

        if (storedCode == null) {
            return VerificationResult.failure(List.of("captcha-expired-or-not-found"));
        }
        if (!storedCode.equalsIgnoreCase(captchaCode.trim())) {
            return VerificationResult.failure(List.of("captcha-mismatch"));
        }
        return VerificationResult.success(action, null);
    }

    @Override
    public boolean isEnabled() {
        return properties.getAuth().getChallenge().getMode() != ArtVerseProperties.ChallengeMode.DISABLED;
    }

    @Override
    public String provider() {
        return properties.getAuth().getChallenge().getProvider();
    }

    @Override
    public String siteKey() {
        return "";
    }

    public CaptchaImage generate() {
        ArtVerseProperties.Challenge challenge = properties.getAuth().getChallenge();
        int length = challenge.getCaptchaLength();
        int width = challenge.getCaptchaImageWidth();
        int height = challenge.getCaptchaImageHeight();
        int fontSize = challenge.getCaptchaFontSize();
        int interferenceLines = challenge.getCaptchaInterferenceLines();

        Random random = new Random();
        StringBuilder codeBuilder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            codeBuilder.append(CHARS[random.nextInt(CHARS.length)]);
        }
        String captchaCode = codeBuilder.toString();

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(245, 245, 245));
            g.fillRect(0, 0, width, height);

            g.setFont(new Font("SansSerif", Font.BOLD, fontSize));
            FontMetrics fm = g.getFontMetrics();
            int charWidth = (width - 20) / length;

            for (int i = 0; i < length; i++) {
                AffineTransform transform = new AffineTransform();
                double angle = (random.nextDouble() - 0.5) * Math.PI / 3;
                transform.translate(10 + i * charWidth + (double) charWidth / 2, (double) height / 2 + (double) fm.getAscent() / 4);
                transform.rotate(angle);
                g.setTransform(transform);
                g.setColor(new Color(random.nextInt(80), random.nextInt(80), random.nextInt(120) + 40));
                g.drawString(String.valueOf(captchaCode.charAt(i)), -fm.stringWidth(String.valueOf(captchaCode.charAt(i))) / 2, 0);
            }
            g.setTransform(new AffineTransform());

            g.setStroke(new BasicStroke(1.5f));
            for (int i = 0; i < interferenceLines; i++) {
                g.setColor(new Color(random.nextInt(150) + 80, random.nextInt(150) + 80, random.nextInt(150) + 80));
                int x1 = random.nextInt(width);
                int y1 = random.nextInt(height);
                int x2 = random.nextInt(width);
                int y2 = random.nextInt(height);
                g.drawLine(x1, y1, x2, y2);
            }

            for (int i = 0; i < 30; i++) {
                g.setColor(new Color(random.nextInt(200) + 50, random.nextInt(200) + 50, random.nextInt(200) + 50));
                int x = random.nextInt(width);
                int y = random.nextInt(height);
                g.fillOval(x, y, 2, 2);
            }
        } finally {
            g.dispose();
        }

        String captchaId = UUID.randomUUID().toString();
        String redisKey = CAPTCHA_KEY_PREFIX + captchaId;
        redisTemplate.opsForValue().set(redisKey, captchaCode.toLowerCase(), challenge.getCaptchaExpireSeconds(), TimeUnit.SECONDS);

        String base64Image;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            base64Image = "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate captcha image", e);
        }

        return new CaptchaImage(captchaId, base64Image);
    }

    public record CaptchaImage(String captchaId, String image) {
    }
}
