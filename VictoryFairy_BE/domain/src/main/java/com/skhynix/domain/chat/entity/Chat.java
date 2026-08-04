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

// idx_chats_chatroom_created(chatroom_id, created_at): chatroom_id 등치 필터 + created_at DESC 정렬을
// 인덱스만으로 만족시켜 filesort 없이 페이징한다. blind/deleted_at은 선택도가 낮아 인덱스에 넣지 않음
// — 제거 시 히스토리 조회가 방 전체 스캔 + filesort로 떨어진다.
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

    // 신고 접수 시 true로 숨기고, 관리자 처리에 따라 false 복구 또는 deletedAt으로 소프트 삭제
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
        // blind·deletedAt은 @Builder 파라미터로 받지 않는다 — 생성 시점부터 정해진 초기값에서 시작
        this.chatroom = chatroom;
        this.userAccount = userAccount;
        this.content = content;
        this.blind = false;
    }

    public void blind() {
        this.blind = true;
    }

    public void unblind() {
        this.blind = false;
    }

    // 이미 삭제된 메시지면 no-op으로 최초 삭제 시각을 보존한다
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
