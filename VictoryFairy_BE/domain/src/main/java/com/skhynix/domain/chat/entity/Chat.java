package com.skhynix.domain.chat.entity;

import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 히스토리 조회 인덱스 {@code idx_chats_chatroom_created(chatroom_id, created_at)}:
 * 히스토리 쿼리는 {@code chatroom_id} 등치 필터 + {@code created_at DESC} 정렬 + LIMIT 페이징이다.
 * 선행 컬럼을 {@code chatroom_id}로 둬 한 방으로 범위를 좁히고(등치, 고선택도), 뒤이은 {@code created_at}이
 * 정렬을 인덱스만으로 만족시켜 MySQL이 인덱스를 역방향 스캔해 filesort 없이 상위 N건을 읽는다.
 * {@code blind}·{@code deleted_at}은 선택도가 낮아(대부분 false·null) 인덱스에 넣지 않고 잔여 필터로 둔다
 * (넣으면 폭만 넓히고 정렬 이득이 없다). prod는 {@code ddl-auto=none}이라 이 인덱스는 자동 생성되지
 * 않으니 chats 테이블 생성 DDL에 반드시 포함할 것. — 인덱스 제거 시 히스토리 조회가 방 전체 스캔 + filesort로 떨어진다.
 */
@Entity
@Table(name = "chats", indexes = {
        @Index(name = "idx_chats_chatroom_created", columnList = "chatroom_id, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Chat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chatroom_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Chatroom chatroom;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount userAccount;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * 임시 숨김 여부. 신고가 접수되면 즉시 {@code true}로 숨기고, 관리자 처리에 따라 {@code false}로
     * 복구하거나 {@code deletedAt}을 채워 소프트 삭제한다.
     */
    @Column(name = "blind", columnDefinition = "TINYINT", nullable = false)
    private boolean blind;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Chat(Chatroom chatroom, UserAccount userAccount, String content) {
        // blind·deletedAt은 호출자가 지정할 수 없도록 @Builder 파라미터로 받지 않는다.
        // blind는 false(노출)로, deletedAt은 null(미삭제)로 시작한다.
        this.chatroom = chatroom;
        this.userAccount = userAccount;
        this.content = content;
        this.blind = false;
    }

    /**
     * 이 메시지를 임시 숨김 처리한다(신고 접수 시). 상태 전이를 엔티티가 직접 책임지므로 {@code @Setter}를 두지 않는다.
     */
    public void blind() {
        this.blind = true;
    }

    /**
     * 임시 숨김을 해제한다(관리자가 정상 메시지로 판단한 경우).
     */
    public void unblind() {
        this.blind = false;
    }

    /**
     * 이 메시지를 소프트 삭제한다. 이미 삭제된 메시지면 아무것도 하지 않아 최초 삭제 시각을 보존한다.
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
     * 소프트 삭제된 메시지인지 여부.
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
