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

    /**
     * 외부 노출용 식별자. {@code id}(순차 PK)는 내부 전용이며, SSE 구독 URL 등 외부에는 이 값을 노출해
     * PK 열거를 막는다. 생성자 본문에서만 채워지고 {@code updatable = false}로 고정된다.
     */
    @Column(name = "uid", length = 36, nullable = false, unique = true, updatable = false,
            columnDefinition = "VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin")
    private String uid;

    /**
     * 채팅방은 팀 종속 데이터이므로 팀 삭제 시 연쇄 삭제한다({@code Player → Team} 선례와 동일한 CASCADE 기준).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Team team;

    /**
     * 방 소유자 계정. 소유자 계정이 사라져도 방은 공용 자원으로 보존돼야 하므로 팀 FK와 달리
     * {@code @OnDelete} 없이 매핑한다({@code Game → Team}과 같은 non-cascade 형). 계정 삭제는
     * 소프트 삭제({@code UserAccount.withdraw}가 {@code exit_at}만 채우고 행은 남긴다)라 고아 FK가
     * 생기지 않는다.
     */
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
        // uid·participants·deletedAt은 호출자가 지정할 수 없도록 @Builder 파라미터로 받지 않는다.
        // uid는 여기서 생성하고, participants는 0(빈 방)으로, deletedAt은 null(미삭제)로 시작한다.
        // owner는 생성 시점에 반드시 지정돼야 하는 값이라 @Builder 파라미터로 받는다.
        this.uid = UUID.randomUUID().toString();
        this.team = team;
        this.owner = owner;
        this.name = name;
        this.participants = 0;
    }

    /**
     * 참여 인원을 1 증가시킨다. 상태 전이를 엔티티가 직접 책임지므로 {@code @Setter}를 두지 않는다.
     */
    public void join() {
        this.participants++;
    }

    /**
     * 참여 인원을 1 감소시킨다. 음수 인원은 존재할 수 없으므로 0 미만으로는 내려가지 않는다.
     */
    public void leave() {
        if (this.participants > 0) {
            this.participants--;
        }
    }

    /**
     * 이 채팅방을 소프트 삭제한다. 이미 삭제된 방이면 아무것도 하지 않아 최초 삭제 시각을 보존한다.
     *
     * @param deletedAt 삭제 시각. 엔티티가 {@code now()}를 직접 읽지 않고 호출자에게서 받는다.
     */
    public void delete(LocalDateTime deletedAt) {
        if (isDeleted()) {
            return;
        }
        this.deletedAt = deletedAt;
    }

    /**
     * 소프트 삭제된 채팅방인지 여부.
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
