package ticketing.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.exception.UserExceptionCode;
import ticketing.domain.user.repository.UserRepository;
import ticketing.global.exception.CustomException;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    public User findById(Long userId){
         return userRepository.findById(userId)
                 .orElseThrow(()-> new CustomException(UserExceptionCode.USER_NOT_FOUND));
    }
}
