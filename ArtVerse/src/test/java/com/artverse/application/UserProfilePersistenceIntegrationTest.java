package com.artverse.application;

import com.artverse.config.BCryptPasswordEncoder;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import com.artverse.security.PasswordHashService;
import com.artverse.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Local PostgreSQL fixture only; the account is removed after verification. */
@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UserProfileService.class, PasswordHashService.class, BCryptPasswordEncoder.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserProfilePersistenceIntegrationTest {
    @Autowired UserRepository users;
    @Autowired UserProfileService profiles;
    @Autowired PasswordHashService passwords;
    @Autowired PlatformTransactionManager transactions;
    @MockBean ObjectStorageService storage;
    @MockBean UserSessionService sessions;

    @Test void migratedSchemaPersistsProfileAndPasswordRollbackProtectsAccount() {
        var tx = new TransactionTemplate(transactions);
        String name = "profile_it_" + UUID.randomUUID().toString().replace("-", "");
        Long id = tx.execute(status -> {
            var user = new User(); user.setUsername(name); user.setEmail(name + "@example.test");
            user.setPasswordHash(passwords.encode("fixture-old"));
            return users.saveAndFlush(user).getId();
        });
        try {
            profiles.updateName(id, "  " + name + "_v2  ");
            String savedName = tx.execute(status -> users.findById(id).orElseThrow().getUsername());
            assertThat(savedName).isEqualTo(name + "_v2");
            doThrow(new IllegalStateException("test revocation failure")).when(sessions).invalidateAll(id);
            assertThatThrownBy(() -> profiles.changePassword(id, "fixture-old", "fixture-new", "fixture-new"))
                    .isInstanceOf(IllegalStateException.class);
            String unchanged = tx.execute(status -> users.findById(id).orElseThrow().getPasswordHash());
            assertThat(passwords.matches("fixture-old", unchanged)).isTrue();
            assertThat(passwords.matches("fixture-new", unchanged)).isFalse();
            reset(sessions);
            profiles.changePassword(id, "fixture-old", "fixture-new", "fixture-new");
            String updated = tx.execute(status -> users.findById(id).orElseThrow().getPasswordHash());
            assertThat(updated).startsWith("$argon2");
            assertThat(passwords.matches("fixture-new", updated)).isTrue();
            verify(sessions).invalidateAll(id);
        } finally { tx.executeWithoutResult(status -> users.deleteById(id)); }
    }
}
