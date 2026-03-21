package in.technobuild.chatbot.controller;

import in.technobuild.chatbot.dto.request.FeedbackRequestDto;
import in.technobuild.chatbot.security.UserPrincipal;
import in.technobuild.chatbot.service.FeedbackService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    public ResponseEntity<Void> submitFeedback(@Valid @RequestBody FeedbackRequestDto request,
                                               @AuthenticationPrincipal UserPrincipal user) {
        Long userId = Long.parseLong(user.getUserId());
        feedbackService.saveFeedback(
                userId,
                request.getMessageId(),
                request.getConversationId(),
                request.getQuestion(),
                request.getAnswer(),
                request.getRating(),
                request.getComment()
        );
        return ResponseEntity.ok().build();
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Long>> feedbackSummary(@AuthenticationPrincipal UserPrincipal user) {
        Long userId = Long.parseLong(user.getUserId());
        return ResponseEntity.ok(feedbackService.getFeedbackSummary(userId));
    }
}
