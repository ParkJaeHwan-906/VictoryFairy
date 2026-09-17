package com.skhynix.domain.chat.entity;

import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "chatrooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Chatroom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // 외부 노출용 식별자 — SSE 구독 URL 등에는 이 값을 노출해 PK 열거를 막는다.
    @Column(name = "uid", length = 36, nullable = false, unique = true, updatable = false,
            columnDefinition = "VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin")
    private String uid;

    // 채팅방은 팀 종속 데이터라 CASCADE
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Team team;

    // 소유자 계정이 사라져도 방은 공용 자원으로 보존 — @OnDelete 없음(FK 는 NO ACTION).
    // ⚠ 종전 근거였던 "계정 삭제는 소프트 삭제라 고아 FK 가 안 생긴다"는 더 이상 사실이 아니다.
    //   탈퇴 30일이 지난 계정은 실제로 지워진다(만료 데이터 정리). 이 컬럼은 NOT NULL 이라 비울 수도
    //   없으므로, 계정을 지우기 전에 소유권을 (알수없음) 더미 계정으로 넘기지 않으면 그 DELETE 자체가
    //   FK 위반으로 실패한다 — ChatroomRepository.reassignOwner 참고.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_account_id", nullable = false)
    private UserAccount owner;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "participants", nullable = false)
    private int participants;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Chatroom(Team team, UserAccount owner, String name) {
        // uid·participants·deletedAt은 @Builder 파라미터로 받지 않는다 — 생성 시점부터 정해진 초기값에서 시작
        this.uid = UUID.randomUUID().toString();
        this.team = team;
        this.owner = owner;
        this.name = name;
        this.participants = 0;
    }

    public void join() {
        this.participants++;
    }

    // 0 하한 — 음수 인원은 존재할 수 없다
    public void leave() {
        if (this.participants > 0) {
            this.participants--;
        }
    }

    // 이미 삭제된 방이면 no-op으로 최초 삭제 시각을 보존한다
    public void delete(LocalDateTime deletedAt) {
        if (isDeleted()) {
            return;
        }
        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
