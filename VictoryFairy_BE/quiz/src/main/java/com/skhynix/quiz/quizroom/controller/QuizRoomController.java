package com.skhynix.quiz.quizroom.controller;

import com.skhynix.common.error.BusinessDataException;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.common.response.ApiResponse;
import com.skhynix.quiz.chat.dto.PageResponse;
import com.skhynix.quiz.quizroom.dto.AnswerRequest;
import com.skhynix.quiz.quizroom.dto.AnswerResponse;
import com.skhynix.quiz.quizroom.dto.CreateQuizRoomRequest;
import com.skhynix.quiz.quizroom.dto.CreateQuizRoomResponse;
import com.skhynix.quiz.quizroom.dto.LikeRequest;
import com.skhynix.quiz.quizroom.dto.QuizRoomLikeResponse;
import com.skhynix.quiz.quizroom.dto.RoomDetailResponse;
import com.skhynix.quiz.quizroom.dto.RoomQuizResponse;
import com.skhynix.quiz.quizroom.dto.RoomSummaryResponse;
import com.skhynix.quiz.quizroom.service.QuizRoomAnswerService;
import com.skhynix.quiz.quizroom.service.QuizRoomLikeService;
import com.skhynix.quiz.quizroom.service.QuizRoomService;
import com.skhynix.quiz.quizroom.service.QuizRoomSubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 접두사 /rt 은 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /rt/quiz-rooms/**
// ⚠ @Validated 를 붙이지 말 것 — 내장 메서드 검증 대신 AOP 검증이 돌아 page/size 위반이 500 이 된다(GlobalExceptionHandler)
@RestController
@RequiredArgsConstructor
@RequestMapping("/quiz-rooms")
public class QuizRoomController {

    private final QuizRoomService roomService;
    private final QuizRoomAnswerService answerService;
    private final QuizRoomLikeService likeService;
    private final QuizRoomSubscriptionService subscriptionService;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateQuizRoomResponse>> create(
            @Valid @RequestBody CreateQuizRoomRequest request,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(roomService.create(userAccountId, request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<RoomSummaryResponse>>> getRooms(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size는 1 이상이어야 합니다.")
            @Max(value = 50, message = "size는 50 이하여야 합니다.") int size) {
        return ResponseEntity.ok(ApiResponse.ok(roomService.getRooms(page, size)));
    }

    @GetMapping("/popular")
    public ResponseEntity<ApiResponse<List<RoomSummaryResponse>>> getPopularRooms() {
        return ResponseEntity.ok(ApiResponse.ok(roomService.getPopularRooms()));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<ApiResponse<RoomDetailResponse>> getRoom(@PathVariable long roomId,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(roomService.getRoom(userAccountId, roomId)));
    }

    @GetMapping("/{roomId}/quizzes")
    public ResponseEntity<ApiResponse<List<RoomQuizResponse>>> getQuizzes(@PathVariable long roomId) {
        return ResponseEntity.ok(ApiResponse.ok(roomService.getQuizzes(roomId)));
    }

    @PostMapping("/{roomId}/quizzes/{quizId}/answer")
    public ResponseEntity<ApiResponse<AnswerResponse>> answer(@PathVariable long roomId,
            @PathVariable long quizId,
            @RequestBody AnswerRequest request,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(
                answerService.answer(userAccountId, roomId, quizId, request.option())));
    }

    @PutMapping("/{roomId}/like")
    public ResponseEntity<ApiResponse<QuizRoomLikeResponse>> setLiked(@PathVariable long roomId,
            @Valid @RequestBody LikeRequest request,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(likeService.setLiked(userAccountId, roomId, request.liked())));
    }

    @GetMapping(value = "/{roomId}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable long roomId, @AuthenticationPrincipal Long userAccountId) {
        return subscriptionService.subscribe(roomId, userAccountId);
    }

    @DeleteMapping("/{roomId}/subscribe")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(@PathVariable long roomId,
            @AuthenticationPrincipal Long userAccountId) {
        subscriptionService.unsubscribe(roomId, userAccountId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * 본문은 공유 {@code GlobalExceptionHandler} 와 같고, Content-Type 을 JSON 으로 못박는 것만 다르다.
     *
     * <p>⚠ 구독 경로는 {@code produces=text/event-stream} 이라 EventSource 처럼 {@code Accept: text/event-stream}
     * 만 보내면, 공유 핸들러의 JSON 본문은 내용 협상에서 쓸 곳이 없어 404 대신 예외가 컨테이너로 샌다. 응답에
     * Content-Type 이 미리 정해져 있으면 컨버터가 협상을 건너뛰고 그 타입으로 쓴다. 공유 핸들러를 고치면 user·chat 의
     * 협상 동작까지 바뀌므로 이 컨트롤러 안에 둔다 — 컨트롤러 안의 핸들러는 어드바이스보다 먼저 고려되고, 별도
     * 어드바이스와 달리 컨트롤러만 올리는 슬라이스 테스트에서도 빠지지 않는다.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        // 하위 타입도 여기로 오므로 공유 핸들러처럼 data 를 살린다
        ApiResponse<Object> body = e instanceof BusinessDataException dataException
                ? ApiResponse.fail(errorCode.getMessage(), dataException.getData())
                : ApiResponse.fail(errorCode.getMessage());
        return ResponseEntity.status(errorCode.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
