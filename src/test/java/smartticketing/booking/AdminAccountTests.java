package smartticketing.booking;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import smartticketing.auth.*;
import smartticketing.dto.auth.SignupRequest;
import smartticketing.entity.Users;
import smartticketing.repository.*;
import java.time.LocalDate;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminAccountTests {
    @Test void publicSignupAndPasswordResetCannotTakeOverAdmin() {
        var users = mock(UsersRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var access = new AdminAccess(users, "admin");
        var auth = new AuthService(users, mock(UserSocialAccountRepository.class), encoder, mock(JwtService.class), access);
        assertThatThrownBy(() -> auth.signup(new SignupRequest("name", LocalDate.of(2000, 1, 1), "ADMIN", "password1234")))
                .isInstanceOf(IllegalArgumentException.class);
        var admin = new Users(); admin.setLoginId("admin");
        when(users.findByNameAndLoginId("name", "admin")).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> auth.resetPassword("name", "admin", "password1234"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).save(any());
        verifyNoInteractions(encoder);
    }

    @Test void initialAccountUsesEncodedPasswordAndNeverOverwritesExistingAccount() {
        var users = mock(UsersRepository.class);
        var encoder = mock(PasswordEncoder.class);
        when(encoder.encode("test")).thenReturn("encoded-password");
        var initializer = new AdminAccountInitializer(users, encoder, "admin", "test");
        initializer.run(null);
        var saved = ArgumentCaptor.forClass(Users.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getLoginId()).isEqualTo("admin");
        assertThat(saved.getValue().getPassword()).isEqualTo("encoded-password");
        when(users.existsByLoginId("admin")).thenReturn(true);
        initializer.run(null);
        verify(users, times(1)).save(any());
    }

    @Test void blankOrWeakInitialPasswordDoesNotCreateAccount() {
        var users = mock(UsersRepository.class);
        var encoder = mock(PasswordEncoder.class);
        new AdminAccountInitializer(users, encoder, "admin", "").run(null);
        assertThatThrownBy(() -> new AdminAccountInitializer(users, encoder, "admin", "tes").run(null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).save(any());
        verifyNoInteractions(encoder);
    }
}
