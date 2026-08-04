package com.skhynix.domain.game.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 수비 포지션 코드 테이블. 값은 자체 영문 약어다. py-collector 가 네이버 record API
 * 박스스코어의 {@code pos} 표기를 아래 매핑으로 변환해 lookup-or-insert 하며,
 * {@link GameLineup}이 {@code position_id} FK 로 참조한다.
 *
 * <p>네이버 {@code pos} 표기 ↔ 약어 매핑 (py-collector {@code db.py POSITION_CODES} 와 동일):
 * <pre>
 *   투 → P  (투수)      포 → C  (포수)      一 → 1B (1루수)     二 → 2B (2루수)
 *   三 → 3B (3루수)     유 → SS (유격수)    좌 → LF (좌익수)    중 → CF (중견수)
 *   우 → RF (우익수)    지 → DH (지명타자)  타 → PH (대타)      주 → PR (대주자)
 * </pre>
 * 1루/2루/3루는 네이버가 한자(一/二/三)로 보낸다. PH/PR 는 수비 위치가 아니라
 * 출전 형태임에 주의. 매핑에 없는 미지 표기는 수집기가 warning 후 원문 그대로
 * 적재하므로 약어 외 값이 존재할 수 있다(발견 시 양쪽 매핑에 추가).
 */
@Entity
@Table(name = "positions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Position(String name) {
        this.name = name;
    }
}
