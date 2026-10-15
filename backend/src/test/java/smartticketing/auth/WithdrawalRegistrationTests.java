package smartticketing.auth;

import org.junit.jupiter.api.*;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.repository.*;
import smartticketing.service.UserService;
import smartticketing.dto.auth.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.LocalDate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("core")
class WithdrawalRegistrationTests {
    @Test void normalAndLegacyWithdrawalReleaseUniqueIdentifiersForNewAccounts() {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:withdrawal;DB_CLOSE_DELAY=-1", "sa", ""));
        factory.setManagedTypes(PersistenceManagedTypes.of(Users.class.getName(), UserSocialAccount.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        try (var em = factory.getObject().createEntityManager()) {
            var repos = new JpaRepositoryFactory(em);
            var users = repos.getRepository(UsersRepository.class);
            var social = repos.getRepository(UserSocialAccountRepository.class);
            var admin = new AdminAccess(users, "admin");
            var service = new UserService(users, social, mock(UserPreferredTheaterRepository.class),
                    mock(UserPreferredSeatRepository.class), mock(TheaterRepository.class),
                    mock(UserNearbyTheaterRepository.class), admin);
            var auth = new AuthService(users, social, mock(PasswordEncoder.class), mock(JwtService.class),
                    admin, mock(EmailVerificationService.class));
            for (boolean legacy : new boolean[]{false, true}) {
                em.getTransaction().begin();
                var old = new Users();
                old.setName("member"); old.setNickname("member");
                old.setLoginId("member@example.com"); old.setEmail("member@example.com");
                old.setPassword("hash"); old.setEmailVerified(true);
                em.persist(old);
                var account = new UserSocialAccount();
                account.setUser(old); account.setProvider(SocialProvider.GOOGLE);
                account.setProviderUserId("google-id"); account.setEmail(old.getEmail());
                em.persist(account);
                if (legacy) {
                    old.setStatus(UserStatus.WITHDRAWN);
                    em.flush();
                    new WithdrawnAccountCleanup(em).run(null);
                    em.clear();
                } else {
                    service.withdraw(old.getId());
                }
                em.getTransaction().commit();
                em.clear();
                assertFalse(auth.isLoginIdTaken("member@example.com"));
                assertNull(auth.socialLogin(SocialProvider.GOOGLE, "google-id"));
                assertNull(users.findById(old.getId()).orElseThrow().getEmail());

                em.getTransaction().begin();
                if (legacy) {
                    auth.completeSocialSignup("GOOGLE", "google-id", "member", "member@example.com",
                            new SocialSignupRequest("member@example.com", "password123", LocalDate.of(2000, 1, 1), "member@example.com", "proof"));
                } else {
                    auth.signup(new SignupRequest("member", LocalDate.of(2000, 1, 1), "member@example.com", "password123", "member@example.com", "proof"));
                }
                em.flush();
                var replacement = users.findByEmailIgnoreCase("member@example.com").orElseThrow();
                assertNotEquals(old.getId(), replacement.getId());
                assertEquals(UserStatus.ACTIVE, replacement.getStatus());
                new WithdrawnAccountCleanup(em).run(null);
                new WithdrawnAccountCleanup(em).run(null);
                em.clear();
                assertEquals(replacement.getId(), users.findByEmailIgnoreCase("member@example.com").orElseThrow().getId());
                social.deleteAll(); users.deleteAll();
                em.getTransaction().commit();
            }
        } finally { factory.destroy(); }
    }
}
