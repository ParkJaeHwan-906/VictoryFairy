package com.skhynix.quiz.quizroom.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.quizroom.entity.QuizRoomQuiz;
import com.skhynix.domain.quizroom.entity.QuizRoomQuizOption;
import com.skhynix.domain.quizroom.entity.QuizRoomWeeklyBest;
import com.skhynix.domain.quizroom.repository.QuizRoomLikeRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomQuizOptionRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomQuizRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomWeekRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomWeeklyBestRepository;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.dto.PageResponse;
import com.skhynix.quiz.quizroom.dto.CreateQuizRoomRequest;
import com.skhynix.quiz.quizroom.dto.CreateQuizRoomResponse;
import com.skhynix.quiz.quizroom.dto.QuizRoomOptionResponse;
import com.skhynix.quiz.quizroom.dto.RoomDetailResponse;
import com.skhynix.quiz.quizroom.dto.RoomQuizResponse;
import com.skhynix.quiz.quizroom.dto.RoomSummaryResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 퀴즈방 생성·조회. 조회는 어떤 행도 만들거나 바꾸지 않는다(풀이는 첫 답 제출로 시작한다). */
@Service
@RequiredArgsConstructor
public class QuizRoomService {

    private final QuizRoomRepository roomRepository;
    private final QuizRoomQuizRepository quizRepository;
    private final QuizRoomQuizOptionRepository optionRepository;
    private final QuizRoomLikeRepository likeRepository;
    private final QuizRoomWeekRepository weekRepository;
    private final QuizRoomWeeklyBestRepository bestRepository;
    private final UserAccountRepository userAccountRepository;
    private final QuizRoomSolvingCounter solvingCounter;
    private final Clock kstClock;

    /** 한 트랜잭션 — 문항 하나라도 저장에 실패하면 방·문항·보기 전부 남지 않는다. */
    @Transactional
    public CreateQuizRoomResponse create(Long userAccountId, CreateQuizRoomRequest request) {
        LocalDateTime now = LocalDateTime.now(kstClock);
        QuizRoom room = roomRepository.save(QuizRoom.builder()
                .author(userAccountRepository.getReferenceById(userAccountId))
                .title(request.title())
                .description(request.description())
                .quizCount(request.quizzes().size())
                .createdAt(now)
                .build());

        // 요청 순서대로 저장한다 — IDENTITY 라 저장 순서가 곧 id 순서이고, 표시 순서는 id 오름차순이다
        List<QuizRoomQuizOption> options = new ArrayList<>();
        for (CreateQuizRoomRequest.QuizItem item : request.quizzes()) {
            QuizRoomQuiz quiz = quizRepository.save(QuizRoomQuiz.builder()
                    .room(room)
                    .type(item.resolvedType())
                    .question(item.question())
                    .answer(item.answer())
                    .build());
            List<String> texts = item.resolvedOptions();
            for (int no = 0; no < texts.size(); no++) {
                options.add(QuizRoomQuizOption.builder().quiz(quiz).no(no).text(texts.get(no)).build());
            }
        }
        optionRepository.saveAll(options);
        return new CreateQuizRoomResponse(room.getId());
    }

    @Transactional(readOnly = true)
    public PageResponse<RoomSummaryResponse> getRooms(int page, int size) {
        Page<QuizRoom> rooms = roomRepository.findAllByOrderByIdDesc(PageRequest.of(page, size));
        Map<Long, Long> solving = solvingCounter.counts(rooms.map(QuizRoom::getId).getContent());
        return PageResponse.from(rooms.map(room -> RoomSummaryResponse.of(room,
                solving.getOrDefault(room.getId(), 0L))));
    }

    /**
     * 가장 최근에 확정된 주간 베스트를 확정 순위대로. 다음 확정 전까지 구성·순서는 그대로이고, {@code likeCount}·{@code solvingCount}
     * 만 응답 시점 값이라 숫자와 순서가 어긋나 보일 수 있다(계약). 확정된 주가 없거나 그 주가 비었으면 빈 목록이다.
     */
    @Transactional(readOnly = true)
    public List<RoomSummaryResponse> getPopularRooms() {
        List<QuizRoomWeeklyBest> bests = weekRepository.findTopByOrderByWeekStartDesc()
                .map(week -> bestRepository.findAllByWeek_IdOrderByRankNoAsc(week.getId()))
                .orElse(List.of());
        if (bests.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> solving = solvingCounter.counts(bests.stream().map(best -> best.getRoom().getId()).toList());
        return bests.stream()
                .map(best -> RoomSummaryResponse.of(best.getRoom(), solving.getOrDefault(best.getRoom().getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoomDetailResponse getRoom(Long userAccountId, long roomId) {
        QuizRoom room = roomRepository.findWithAuthorById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_ROOM_NOT_FOUND));
        boolean liked = likeRepository.existsByUserAccount_IdAndRoom_Id(userAccountId, roomId);
        boolean isAuthor = room.getAuthor().getId().equals(userAccountId);
        return RoomDetailResponse.of(room, solvingCounter.count(roomId), liked, isAuthor);
    }

    /** 정답·분포를 싣지 않는다 — 이미 다 푼 계정이 요청해도 같다. */
    @Transactional(readOnly = true)
    public List<RoomQuizResponse> getQuizzes(long roomId) {
        if (!roomRepository.existsById(roomId)) {
            throw new BusinessException(ErrorCode.QUIZ_ROOM_NOT_FOUND);
        }
        Map<Long, List<QuizRoomOptionResponse>> options = optionsByQuiz(roomId);
        return quizRepository.findAllByRoom_IdOrderByIdAsc(roomId).stream()
                .map(quiz -> new RoomQuizResponse(quiz.getId(), quiz.getType().label(), quiz.getQuestion(),
                        options.getOrDefault(quiz.getId(), List.of())))
                .toList();
    }

    // public 인 이유: 이 빈은 CGLIB 프록시라 package-private 메서드를 다른 빈에서 부르면 필드가 빈 프록시 인스턴스에서 돌 수 있다
    @Transactional(readOnly = true)
    public Map<Long, List<QuizRoomOptionResponse>> optionsByQuiz(long roomId) {
        Map<Long, List<QuizRoomOptionResponse>> options = new LinkedHashMap<>();
        for (QuizRoomQuizOption option : optionRepository.findAllByQuiz_Room_IdOrderByQuiz_IdAscNoAsc(roomId)) {
            options.computeIfAbsent(option.getQuiz().getId(), key -> new ArrayList<>())
                    .add(QuizRoomOptionResponse.from(option));
        }
        return options;
    }
}
