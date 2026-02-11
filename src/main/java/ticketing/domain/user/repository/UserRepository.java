package ticketing.domain.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ticketing.domain.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
}
