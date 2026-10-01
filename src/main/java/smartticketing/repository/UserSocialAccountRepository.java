package smartticketing.repository;

import smartticketing.entity.UserSocialAccount;
import smartticketing.entity.enums.SocialProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserSocialAccountRepository extends JpaRepository<UserSocialAccount, Long> {
    Optional<UserSocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);
    Optional<UserSocialAccount> findByUserIdAndProvider(Long userId, SocialProvider provider);
    List<UserSocialAccount> findByUserId(Long userId);
    boolean existsByEmailIgnoreCase(String email);
}
