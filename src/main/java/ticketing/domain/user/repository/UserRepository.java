package ticketing.domain.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ticketing.domain.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
    /** 잔액이 충분할 때만 차감 (lost update 방지), 0이면 사용자 없음 또는 잔액 부족 */
    @Modifying
    @Query("update User u set u.point = u.point - :amount where u.id = :userId and u.point >= :amount")
    int deductPoint(@Param("userId") Long userId, @Param("amount") int amount);
}
