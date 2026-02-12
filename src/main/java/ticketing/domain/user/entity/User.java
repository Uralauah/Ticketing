package ticketing.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import ticketing.domain.user.exception.UserExceptionCode;
import ticketing.global.exception.CustomException;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy =  GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int point;

    public User(String name, int point){
        this.name = name;
        this.point = point;
    }

    public void reserve(int price) {
        if(point<price){
            throw new CustomException(UserExceptionCode.USER_CANT_PAY);
        }
        point-=price;
    }
}
