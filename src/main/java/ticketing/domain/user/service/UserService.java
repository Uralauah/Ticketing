package ticketing.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.exception.UserExceptionCode;
import ticketing.domain.user.repository.UserRepository;
import ticketing.global.exception.CustomException;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    /** 예약 트랜잭션 안에서만 호출 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deductPoint(Long userId, int amount) {
        if (userRepository.deductPoint(userId, amount) == 1) {
            return;
        }
        if (!userRepository.existsById(userId)) {
            throw new CustomException(UserExceptionCode.USER_NOT_FOUND);
        }
        throw new CustomException(UserExceptionCode.USER_CANT_PAY);
    }

    /** 연관관계 설정용 (존재 여부는 deductPoint에서 확인) */
    public User getReference(Long userId) {
        return userRepository.getReferenceById(userId);
    }
}
