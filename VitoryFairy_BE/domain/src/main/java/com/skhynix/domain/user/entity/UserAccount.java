package com.skhynix.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "users_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * 외부 노출용 식별자. {@code id}(순차 PK)는 내부 전용이며, API·URL에는 이 값을 노출해 PK 열거를 막는다.
     * 생성자 본문에서만 채워지고 {@code updatable = false}로 고정 — 회원가입 이후 변경되지 않는다.
     */
    @Column(name = "uid", length = 36, nullable = false, unique = true, updatable = false,
            columnDefinition = "VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin")
    private String uid;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "nickname", length = 100, nullable = false)
    private String nickname;

    @Column(name = "password", length = 255, nullable = false)
    private String password;

    @Column(name = "exit_at")
    private LocalDateTime exitAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private UserAccount(User user, String nickname, String password, LocalDateTime exitAt) {
        // uid는 호출자가 지정할 수 없도록 @Builder 파라미터로 받지 않고 여기서 생성한다.
        this.uid = UUID.randomUUID().toString();
        this.user = user;
        this.nickname = nickname;
        this.password = password;
        this.exitAt = exitAt;
    }

    /**
     * 이 계정을 탈퇴 처리한다. {@code exitAt}은 "탈퇴 예정 시각"이 아니라 <b>탈퇴 완료 시각</b>이다
     * (유예 기간·취소 없음).
     *
     * <p>이미 탈퇴한 계정이면 아무것도 하지 않고 최초 탈퇴 시각을 그대로 보존한다 — 한 번 기록된
     * {@code exit_at}은 이후 변경되지 않아야 한다. 상태 전이를 엔티티가 직접 책임지므로
     * {@code @Setter}를 두지 않는다.
     *
     * @param exitAt 탈퇴 시각. 같은 트랜잭션의 다른 작업(refresh 토큰 만료)과 시각을 정확히 맞출 수
     *               있도록 엔티티가 {@code now()}를 직접 읽지 않고 호출자에게서 받는다.
     */
    public void withdraw(LocalDateTime exitAt) {
        if (isWithdrawn()) {
            return;
        }
        this.exitAt = exitAt;
    }

    /**
     * 탈퇴한 계정인지 여부. {@code exit_at}이 채워져 있으면 탈퇴가 완료된 계정이다.
     */
    public boolean isWithdrawn() {
        return exitAt != null;
    }
}
