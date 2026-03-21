package in.technobuild.chatbot.controller;

import in.technobuild.chatbot.dto.request.ChatRequestDto;
import in.technobuild.chatbot.entity.Conversation;
import in.technobuild.chatbot.kafka.model.ChatRequestEvent;
import in.technobuild.chatbot.kafka.model.SqlRequestEvent;
import in.technobuild.chatbot.kafka.producer.ChatRequestProducer;
import in.technobuild.chatbot.kafka.producer.SqlRequestProducer;
import in.technobuild.chatbot.security.UserPrincipal;
import in.technobuild.chatbot.service.ConversationService;
import in.technobuild.chatbot.service.CostTrackerService;
import in.technobuild.chatbot.service.ModelRouterService;
import in.technobuild.chatbot.service.PiiScrubberService;
import in.technobuild.chatbot.service.RateLimiterService;
import in.technobuild.chatbot.sse.SseEmitterRegistry;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatRequestProducer chatRequestProducer;
    private final SqlRequestProducer sqlRequestProducer;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final ConversationService conversationService;
    private final PiiScrubberService piiScrubberService;
    private final ModelRouterService modelRouterService;
    private final RateLimiterService rateLimiterService;
    private final CostTrackerService costTrackerService;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> chat(@Valid @RequestBody ChatRequestDto request,
                                  @AuthenticationPrincipal UserPrincipal user) {
        if (user == null || user.getUserId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "User not authenticated"));
        }

        Long userId = Long.parseLong(user.getUserId());
        if (rateLimiterService.isBlacklisted(userId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Your account is temporarily restricted."));
        }
        if (!rateLimiterService.isAllowed(userId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many messages. Please wait 30 seconds."));
        }
        if (!costTrackerService.isWithinBudget(userId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", costTrackerService.getBudgetExceededMessage()));
        }

        String messageId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(120_000L);
        sseEmitterRegistry.register(messageId, emitter);

        try {
            Conversation conversation = conversationService.getOrCreateConversation(userId, request.getSessionId());
            String scrubbedMessage = piiScrubberService.scrub(request.getMessage());

            ChatRequestEvent event = ChatRequestEvent.builder()
                    .messageId(messageId)
                    .userId(userId)
                    .sessionId(conversation.getSessionId())
                    .userMessage(scrubbedMessage)
                    .conversationId(conversation.getId())
                    .userRole(user.getRole())
                    .userName(user.getUsername())
                    .currentDateTime(LocalDateTime.now().toString())
                    .timestamp(System.currentTimeMillis())
                    .build();

            if (modelRouterService.isSqlIntent(scrubbedMessage)) {
                SqlRequestEvent sqlEvent = SqlRequestEvent.builder()
                        .messageId(messageId)
                        .userId(userId)
                        .sessionId(conversation.getSessionId())
                        .originalQuestion(scrubbedMessage)
                        .conversationId(conversation.getId())
                        .timestamp(System.currentTimeMillis())
                        .build();
                sqlRequestProducer.publish(sqlEvent);
                log.info("SQL request published for messageId={}", messageId);
            } else {
                chatRequestProducer.publish(event);
                log.info("Chat request published for messageId={}", messageId);
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(emitter);
        } catch (Exception ex) {
            log.error("Failed to process /api/chat request", ex);
            try {
                emitter.send(SseEmitter.event().name("error").data("Something went wrong. Please try again."));
            } catch (IOException ioEx) {
                log.error("Failed to send SSE error event", ioEx);
            }
            emitter.completeWithError(ex);
            sseEmitterRegistry.remove(messageId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(emitter);
        }
    }
}
