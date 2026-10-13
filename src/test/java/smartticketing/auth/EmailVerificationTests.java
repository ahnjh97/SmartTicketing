package smartticketing.auth;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import smartticketing.entity.EmailChallenge;
import smartticketing.repository.EmailChallengeRepository;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("core")
@SpringJUnitConfig(EmailVerificationTests.Config.class)
class EmailVerificationTests {
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource("jdbc:h2:mem:email-tests;DB_CLOSE_DELAY=-1", "sa", ""); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setManagedTypes(PersistenceManagedTypes.of(EmailChallenge.class.getName()));
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean EmailChallengeRepository repository(EntityManagerFactory factory) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(EmailChallengeRepository.class);
        }
        @Bean VerificationMailer mailer() { return mock(VerificationMailer.class); }
        @Bean EmailVerificationService verification(EmailChallengeRepository repository, VerificationMailer mailer) {
            return new EmailVerificationService(repository, mailer, "email-test-secret-at-least-32-bytes");
        }
    }
    @Autowired EmailVerificationService service;
    @Autowired EmailChallengeRepository repository;
    @Autowired VerificationMailer mailer;
    @Autowired PlatformTransactionManager manager;
    private TransactionTemplate tx;
    private final AtomicReference<String> sentCode = new AtomicReference<>();
    private static final String EMAIL = "member@example.com";

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> repository.deleteAll());
        reset(mailer);
        doAnswer(call -> { sentCode.set(call.getArgument(1)); return null; }).when(mailer).send(anyString(), anyString(), any());
    }
    private EmailChallenge read() { return tx.execute(status -> repository.findAll().getFirst()); }
    private void age() { tx.executeWithoutResult(status -> repository.findAll().forEach(c -> c.setSentAt(Instant.now().minusSeconds(61)))); }
    private String proof() {
        service.request(EMAIL, EmailPurpose.SIGNUP);
        return service.verify(EMAIL, EmailPurpose.SIGNUP, sentCode.get());
    }

    @Test void codeIsNotStoredAndProofIsScopedAndSingleUse() {
        String proof = proof();
        assertNotEquals(proof, read().getGrantHash());
        assertNull(read().getCodeHash());
        assertThrows(IllegalArgumentException.class, () -> service.consume("other@example.com", EmailPurpose.SIGNUP, proof));
        assertThrows(IllegalArgumentException.class, () -> service.consume(EMAIL, EmailPurpose.RESET_PASSWORD, proof));
        service.consume(EMAIL, EmailPurpose.SIGNUP, proof);
        assertThrows(IllegalArgumentException.class, () -> service.consume(EMAIL, EmailPurpose.SIGNUP, proof));
    }
    @Test void clientLimitPersistsAcrossFailedOperationsAndSeparatesClientsAndActions() {
        for (int i = 0; i < 20; i++) service.limitClient("127.0.0.1", false);
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.limitClient("127.0.0.1", false));
        assertEquals(429, error.getStatusCode().value());
        service.limitClient("127.0.0.2", false);
        service.limitClient("127.0.0.1", true);
        tx.executeWithoutResult(status -> repository.findAll().forEach(c -> c.setWindowStart(Instant.now().minusSeconds(3601))));
        service.limitClient("127.0.0.1", false);
    }
    @Test void wrongAttemptsCommitEvenThoughVerificationThrows() {
        service.request(EMAIL, EmailPurpose.SIGNUP);
        String wrong = sentCode.get().equals("000000") ? "111111" : "000000";
        for (int i=0; i<5; i++) assertThrows(IllegalArgumentException.class, () -> service.verify(EMAIL, EmailPurpose.SIGNUP, wrong));
        assertEquals(5, read().getAttempts());
        assertThrows(IllegalArgumentException.class, () -> service.verify(EMAIL, EmailPurpose.SIGNUP, sentCode.get()));
    }
    @Test void resendThrottlesAndInvalidatesAnEarlierProof() {
        String proof = proof();
        assertThrows(IllegalArgumentException.class, () -> service.request(EMAIL, EmailPurpose.SIGNUP));
        age();
        service.request(EMAIL, EmailPurpose.SIGNUP);
        assertThrows(IllegalArgumentException.class, () -> service.consume(EMAIL, EmailPurpose.SIGNUP, proof));
        for (int i=0; i<3; i++) { age(); service.request(EMAIL, EmailPurpose.SIGNUP); }
        age();
        assertThrows(IllegalArgumentException.class, () -> service.request(EMAIL, EmailPurpose.SIGNUP));
    }
    @Test void expiredCodesAndProofsCannotBeUsed() {
        service.request(EMAIL, EmailPurpose.SIGNUP);
        tx.executeWithoutResult(status -> repository.findAll().forEach(c -> c.setExpiresAt(Instant.now().minusSeconds(1))));
        assertThrows(IllegalArgumentException.class, () -> service.verify(EMAIL, EmailPurpose.SIGNUP, sentCode.get()));
        age();
        service.request(EMAIL, EmailPurpose.SIGNUP);
        String proof = service.verify(EMAIL, EmailPurpose.SIGNUP, sentCode.get());
        tx.executeWithoutResult(status -> repository.findAll().forEach(c -> c.setExpiresAt(Instant.now().minusSeconds(1))));
        assertThrows(IllegalArgumentException.class, () -> service.consume(EMAIL, EmailPurpose.SIGNUP, proof));
    }
    @Test void failedAccountOperationDoesNotConsumeProof() {
        String proof = proof();
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            service.consume(EMAIL, EmailPurpose.SIGNUP, proof);
            throw new IllegalStateException("account update failed");
        }));
        service.consume(EMAIL, EmailPurpose.SIGNUP, proof);
    }
    @Test void concurrentConsumersCannotReuseTheSameProof() throws Exception {
        String proof = proof();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> consume = () -> {
                start.await();
                try { service.consume(EMAIL, EmailPurpose.SIGNUP, proof); return true; }
                catch (IllegalArgumentException e) { return false; }
            };
            var a=pool.submit(consume); var b=pool.submit(consume); start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }
    @Test void emailIsNormalizedAndMailFailureRollsBackChallenge() {
        doThrow(new IllegalStateException("smtp unavailable")).when(mailer).send(anyString(), anyString(), any());
        assertThrows(IllegalStateException.class, () -> service.request(" Member@Example.com ", EmailPurpose.SIGNUP));
        assertEquals(0L, tx.<Long>execute(status -> repository.count()).longValue());
        verify(mailer).send(eq(EMAIL), anyString(), eq(EmailPurpose.SIGNUP));
    }
}
