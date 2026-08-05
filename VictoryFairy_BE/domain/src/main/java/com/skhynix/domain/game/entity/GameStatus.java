package com.skhynix.domain.game.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 경기 상태 코드 테이블 — 상태값은 코드 상수가 아니라 {@code game_statuses} 테이블의 행({@code name})으로
 * 존재하며 {@link Game}이 {@code game_status_id} FK로 참조한다. 네이버 스포츠 API ↔ name 매핑 근거는
 * domain 모듈 문서 참고.
 *
 * <p>취소 판정 주의: 취소된 경기는 {@code statusCode}가 {@code "RESULT"}가 아니라 {@code "BEFORE"}로 오고
 * 점수는 0-0 껍데기다 — {@code statusCode}가 아니라 {@code cancel} 플래그로 판정해야 한다.
 */
@Entity
@Table(
        name = "game_statuses",
        // 제약 이름을 명시한다(@Column(unique=true) 의 Hibernate 자동 생성명 UK... 대신). 이 제약을
        // 기존 DB 에 넣는 건 Hibernate 가 아니라 손으로 도는 infra/sql/migrate-game-status-unique.sql
        // 이라, 양쪽이 같은 이름을 써야 "이미 걸렸는지"를 이름으로 확인할 수 있다.
        uniqueConstraints = @UniqueConstraint(name = "uk_game_statuses_name", columnNames = "name"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * 상태 코드명. 소스 자연키라 UNIQUE 다(위 {@code @Table} 의 {@code uk_game_statuses_name}) —
     * 이 테이블을 채우는 주체가 둘(앱 기동 시드 {@code infra/sql/game-statuses-init.sql},
     * py-collector 의 lookup-or-insert)이고 둘 다 name 으로 행을 찾기 때문에, 제약이 없으면 같은 상태가
     * 두 행으로 갈라진 채 조용히 남는다(수집기는 그때그때 다른 id 를 집어가고,
     * {@code GameStatusRepository.findByName}은 {@code Optional} 이라 2행이면 예외).
     *
     * <p>제약이 실제로 막는 것: 완전히 비어 있는 DB 에 파드 여러 개가 동시에 뜨면 시드의
     * {@code WHERE NOT EXISTS}가 서로의 미커밋 INSERT 를 못 봐 같은 이름을 두 번 넣는다. UNIQUE 가 있으면
     * 그 두 번째가 {@code Duplicate entry}로 죽어 **기동이 실패**하고(= 조용한 중복 대신 시끄러운 실패),
     * 재시작하면 앞선 파드가 넣은 행이 보여 no-op 으로 통과한다. {@code Team.code}와 같은 성격이다.
     *
     * <p>⚠ <b>이 선언만으로는 기존 DB 에 제약이 생기지 않는다.</b> {@code ddl-auto=update} 는 이미
     * 존재하는 테이블에 UNIQUE 를 추가하지 않는다 — 중복 행 유무와 무관하게 아예 시도하지 않는다
     * (2026-08-05 실측: 같은 기동에서 새로 만들어진 {@code teams} 에는 {@code code} UNIQUE 가 붙었으나,
     * 미리 존재하던 {@code game_statuses} 에는 아무것도 붙지 않음). 그래서 신규 환경은 이 선언이,
     * 이미 뜬 prod/dev 는 {@code infra/sql/migrate-game-status-unique.sql} 수동 실행이 각각 담당한다.
     */
    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private GameStatus(String name) {
        this.name = name;
    }
}
