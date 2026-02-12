package ticketing.domain.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ticketing.domain.user.entity.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findById(Long userId);
}
