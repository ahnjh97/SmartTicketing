package smartticketing.auth;

import jakarta.persistence.EntityManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.entity.enums.UserStatus;

/** Releases identifiers left behind by the previous withdrawal policy. */
@Component
public class WithdrawnAccountCleanup implements ApplicationRunner {
    private final EntityManager em;

    public WithdrawnAccountCleanup(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        em.createQuery("delete from UserSocialAccount a where a.user.id in "
                        + "(select u.id from Users u where u.status = :status)")
                .setParameter("status", UserStatus.WITHDRAWN)
                .executeUpdate();
        em.createQuery("update Users u set u.email = null, u.emailVerified = false, "
                        + "u.loginId = null, u.password = null, u.tokenVersion = u.tokenVersion + 1 "
                        + "where u.status = :status and "
                        + "(u.email is not null or u.loginId is not null or u.password is not null or u.emailVerified = true)")
                .setParameter("status", UserStatus.WITHDRAWN)
                .executeUpdate();
    }
}
