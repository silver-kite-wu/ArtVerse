package com.artverse.application;

import com.artverse.config.BCryptPasswordEncoder;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import com.artverse.security.PasswordHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Opt-in smoke test against the already running local app, Redis and MinIO. */
@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PasswordHashService.class, BCryptPasswordEncoder.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfSystemProperty(named = "artverse.profile.http-smoke", matches = "true")
class UserProfileHttpIntegrationTest {
    @Autowired UserRepository users;
    @Autowired PasswordHashService passwords;
    @Autowired PlatformTransactionManager transactions;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build(); }
    private HttpResponse<byte[]> request(HttpClient client, String method, String path, String contentType, byte[] body, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:8080" + path)).timeout(Duration.ofSeconds(20));
        if (csrf) request.header("Origin", "http://localhost:5173").header("X-ArtVerse-Client", "web");
        if (contentType != null) request.header("Content-Type", contentType);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private HttpResponse<byte[]> json(HttpClient client, String path, Map<String, String> body) throws Exception {
        return request(client, "POST", path, "application/json", mapper.writeValueAsBytes(body), true);
    }
    private void login(HttpClient client, String email, String password) throws Exception {
        assertThat(json(client, "/api/auth/login", Map.of("email", email, "password", password)).statusCode()).isEqualTo(200);
    }
    @Test void realHttpStorageAndSessionLifecycle() throws Exception {
        var tx = new TransactionTemplate(transactions);
        String name = "profile_http_" + UUID.randomUUID().toString().replace("-", "");
        String email = name + "@example.test";
        Long id = tx.execute(status -> {
            var user = new User(); user.setUsername(name); user.setEmail(email); user.setPasswordHash(passwords.encode("fixture-old"));
            return users.saveAndFlush(user).getId();
        });
        var first = client(); var second = client(); var cleanup = client();
        boolean changed = false;
        try {
            assertThat(request(client(), "GET", "/api/user/me", null, null, false).statusCode()).isEqualTo(401);
            login(first, email, "fixture-old"); login(second, email, "fixture-old");
            assertThat(request(first, "PUT", "/api/user/me", "application/json", mapper.writeValueAsBytes(Map.of("username", name)), false).statusCode()).isEqualTo(403);
            var renamed = request(first, "PUT", "/api/user/me", "application/json", mapper.writeValueAsBytes(Map.of("username", name + "_2")), true);
            assertThat(renamed.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(renamed.body()).at("/data/username").asText()).isEqualTo(name + "_2");
            var png = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", png);
            String boundary = "avatar-" + UUID.randomUUID(); var multipart = new ByteArrayOutputStream();
            multipart.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"avatar.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            multipart.write(png.toByteArray()); multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var uploaded = request(first, "POST", "/api/user/me/avatar", "multipart/form-data; boundary=" + boundary, multipart.toByteArray(), true);
            assertThat(uploaded.statusCode()).isEqualTo(200);
            String avatarUrl = mapper.readTree(uploaded.body()).at("/data/avatar_url").asText();
            assertThat(avatarUrl).startsWith("/api/user/me/avatar?v=");
            var image = request(first, "GET", avatarUrl, null, null, false);
            assertThat(image.statusCode()).isEqualTo(200); assertThat(image.body()).isEqualTo(png.toByteArray());
            assertThat(image.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(request(first, "DELETE", "/api/user/me/avatar", null, null, true).statusCode()).isEqualTo(200);
            var wrong = json(first, "/api/user/me/password", Map.of("current_password", "incorrect", "new_password", "fixture-new", "confirm_password", "fixture-new"));
            assertThat(wrong.statusCode()).isEqualTo(400);
            assertThat(request(first, "GET", "/api/user/me", null, null, false).statusCode()).isEqualTo(200);
            var password = json(first, "/api/user/me/password", Map.of("current_password", "fixture-old", "new_password", "fixture-new", "confirm_password", "fixture-new"));
            assertThat(password.statusCode()).isEqualTo(204); changed = true;
            assertThat(request(first, "GET", "/api/user/me", null, null, false).statusCode()).isEqualTo(401);
            assertThat(request(second, "GET", "/api/user/me", null, null, false).statusCode()).isEqualTo(401);
            assertThat(json(second, "/api/auth/refresh", Map.of()).statusCode()).isEqualTo(401);
            login(cleanup, email, "fixture-new");
        } finally {
            try {
                login(cleanup, email, changed ? "fixture-new" : "fixture-old");
                request(cleanup, "DELETE", "/api/user/me/avatar", null, null, true);
                json(cleanup, "/api/auth/logout", Map.of());
            } finally { tx.executeWithoutResult(status -> users.deleteById(id)); }
        }
    }
}
