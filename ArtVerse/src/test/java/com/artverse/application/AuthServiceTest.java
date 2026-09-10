package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.Role;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import com.artverse.security.PasswordHashService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordHashService passwordHashService;
    @InjectMocks
    private AuthService authService;

    private static final String PASSWORD = "SecurePass123";

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("normalizes username and email before saving")
        void normalizesAndSaves() {
            when(userRepository.existsByUsername("Alice")).thenReturn(false);
            when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
            when(passwordHashService.encode(PASSWORD)).thenReturn("argon-hash");

            User saved = new User();
            saved.setId(1L);
            saved.setUsername("Alice");
            saved.setEmail("test@example.com");
            saved.setRole(Role.USER);
            when(userRepository.saveAndFlush(any(User.class))).thenReturn(saved);

            User result = authService.register("  Alice  ", " Test@Example.com ", PASSWORD);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).saveAndFlush(userCaptor.capture());
            assertThat(userCaptor.getValue().getUsername()).isEqualTo("Alice");
            assertThat(userCaptor.getValue().getEmail()).isEqualTo("test@example.com");
            assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("argon-hash");
            assertThat(result.getRole()).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("rejects duplicate username or email")
        void rejectsDuplicateIdentity() {
            when(userRepository.existsByUsername("Alice")).thenReturn(true);

            assertThatThrownBy(() -> authService.register("Alice", "test@example.com", PASSWORD))
                    .isInstanceOf(BusinessException.class)
                    .matches(ex -> ((BusinessException) ex).getStatus() == 409);
        }

        @Test
        @DisplayName("maps concurrent unique constraint conflict to 409")
        void mapsUniqueConstraintRace() {
            when(userRepository.existsByUsername("Alice")).thenReturn(false);
            when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
            when(passwordHashService.encode(PASSWORD)).thenReturn("argon-hash");
            when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

            assertThatThrownBy(() -> authService.register("Alice", "test@example.com", PASSWORD))
                    .isInstanceOf(BusinessException.class)
                    .matches(ex -> ((BusinessException) ex).getStatus() == 409);
        }

        @Test
        @DisplayName("rejects password shorter than 8 characters")
        void rejectsShortPassword() {
            assertThatThrownBy(() -> authService.register("Alice", "test@example.com", "short"))
                    .isInstanceOf(BusinessException.class)
                    .matches(ex -> ((BusinessException) ex).getStatus() == 400);
        }

        @Test
        @DisplayName("rejects password longer than 16 characters")
        void rejectsLongPassword() {
            assertThatThrownBy(() -> authService.register("Alice", "test@example.com", "12345678901234567"))
                    .isInstanceOf(BusinessException.class)
                    .matches(ex -> ((BusinessException) ex).getStatus() == 400);
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        private User user;

        @BeforeEach
        void setUp() {
            user = new User();
            user.setId(7L);
            user.setUsername("Alice");
            user.setPasswordHash("$2a$10$legacy");
        }

        @Test
        @DisplayName("upgrades legacy password hash after successful login")
        void upgradesLegacyHash() {
            when(userRepository.findByUsername("Alice")).thenReturn(Optional.of(user));
            when(passwordHashService.matches(PASSWORD, "$2a$10$legacy")).thenReturn(true);
            when(passwordHashService.requiresUpgrade("$2a$10$legacy")).thenReturn(true);
            when(passwordHashService.encode(PASSWORD)).thenReturn("argon-hash");

            User result = authService.login(" Alice ", PASSWORD);

            assertThat(result.getPasswordHash()).isEqualTo("argon-hash");
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("does dummy verification for nonexistent users")
        void consumesDummyHashForMissingUser() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login("ghost", PASSWORD))
                    .isInstanceOf(BusinessException.class)
                    .matches(ex -> ((BusinessException) ex).getStatus() == 401);

            verify(passwordHashService).consumeDummyCheck(PASSWORD);
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("returns generic 401 for wrong password")
        void wrongPassword() {
            when(userRepository.findByUsername("Alice")).thenReturn(Optional.of(user));
            when(passwordHashService.matches("wrong-password-value", "$2a$10$legacy")).thenReturn(false);

            assertThatThrownBy(() -> authService.login("Alice", "wrong-password-value"))
                    .isInstanceOf(BusinessException.class)
                    .matches(ex -> ((BusinessException) ex).getStatus() == 401)
                    .hasMessageContaining("用户名或密码错误");
        }
    }
}
