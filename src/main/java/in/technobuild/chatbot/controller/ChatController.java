package in.technobuild.chatbot.controller;

import in.technobuild.chatbot.dto.request.ChatRequestDto;
import in.technobuild.chatbot.entity.AuditLog;
import in.technobuild.chatbot.entity.Conversation;
import in.technobuild.chatbot.entity.Message;
import in.technobuild.chatbot.exception.RateLimitExceededException;
import in.technobuild.chatbot.exception.TokenBudgetExceededException;
import in.technobuild.chatbot.kafka.model.ChatRequestEvent;
import in.technobuild.chatbot.kafka.model.SqlRequestEvent;
import in.technobuild.chatbot.kafka.producer.ChatRequestProducer;
import in.technobuild.chatbot.kafka.producer.SqlRequestProducer;
import in.technobuild.chatbot.repository.AuditLogRepository;
import in.technobuild.chatbot.security.UserPrincipal;
import in.technobuild.chatbot.service.ConversationService;
import in.technobuild.chatbot.service.CostTrackerService;
import in.technobuild.chatbot.service.InjectionGuardService;
import in.technobuild.chatbot.service.ModelRouterService;
import in.technobuild.chatbot.service.PiiScrubberService;
import in.technobuild.chatbot.service.RateLimiterService;
import in.technobuild.chatbot.sse.SseEmitterRegistry;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final InjectionGuardService injectionGuardService;
    private final AuditLogRepository auditLogRepository;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chat(@Valid @RequestBody ChatRequestDto request,
                                           @AuthenticationPrincipal UserPrincipal user) {
        Long userId = parseUserId(user);

        if (rateLimiterService.isBlacklisted(userId)) {
            throw new RateLimitExceededException("Your account is temporarily restricted.", 30L);
        }
        if (!rateLimiterService.isAllowed(userId)) {
            throw new RateLimitExceededException("Too many messages. Please wait 30 seconds.", 30L);
        }
        if (!costTrackerService.isWithinBudget(userId)) {
            throw new TokenBudgetExceededException(
                    costTrackerService.getBudgetExceededMessage(),
                    costTrackerService.getRemainingBudget(userId)
            );
        }

        String messageId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(120_000L);
        sseEmitterRegistry.register(messageId, emitter);

        try {
            if (injectionGuardService.isInjectionAttempt(request.getMessage())) {
                injectionGuardService.handleInjectionAttempt(userId, request.getMessage());
                emitter.send(SseEmitter.event().data(injectionGuardService.getInjectionResponse()));
                emitter.send(SseEmitter.event().name("complete").data("[DONE]"));
                emitter.complete();
                sseEmitterRegistry.remove(messageId);
                return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
            }

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
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to stream injection response", ex);
        } catch (Exception ex) {
            log.error("Failed to process /api/chat request", ex);
            try {
                emitter.send(SseEmitter.event().name("error").data("Something went wrong. Please try again."));
            } catch (IOException ignored) {
                log.error("Failed to send SSE error event", ignored);
            }
            emitter.completeWithError(ex);
            sseEmitterRegistry.remove(messageId);
            throw ex;
        }
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<Conversation>> conversations(@AuthenticationPrincipal UserPrincipal user) {
        Long userId = parseUserId(user);
        return ResponseEntity.ok(conversationService.getConversationsForUser(userId));
    }

    @GetMapping("/conversations/{sessionId}/messages")
    public ResponseEntity<List<Message>> conversationMessages(@PathVariable String sessionId,
                                                              @AuthenticationPrincipal UserPrincipal user) {
        Long userId = parseUserId(user);
        try {
            return ResponseEntity.ok(conversationService.getMessagesForUserSession(userId, sessionId));
        } catch (IllegalArgumentException ex) {
            throw new AccessDeniedException("Session does not belong to user");
        }
    }

    @DeleteMapping("/conversations/{sessionId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String sessionId,
                                                   @AuthenticationPrincipal UserPrincipal user) {
        Long userId = parseUserId(user);
        try {
            conversationService.deleteConversationBySession(userId, sessionId);
        } catch (IllegalArgumentException ex) {
            throw new AccessDeniedException("Session does not belong to user");
        }

        auditLogRepository.save(AuditLog.builder()
                .userId(userId)
                .sessionId(sessionId)
                .eventType(AuditLog.EventType.DATA_DELETION)
                .tokensUsed(0)
                .latencyMs(0)
                .createdAt(LocalDateTime.now())
                .build());

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/history")
    public ResponseEntity<Void> deleteAllHistory(@AuthenticationPrincipal UserPrincipal user) {
        Long userId = parseUserId(user);
        conversationService.deleteUserHistory(userId);

        auditLogRepository.save(AuditLog.builder()
                .userId(userId)
                .eventType(AuditLog.EventType.DATA_DELETION)
                .tokensUsed(0)
                .latencyMs(0)
                .createdAt(LocalDateTime.now())
                .build());

        return ResponseEntity.noContent().build();
    }

    private Long parseUserId(UserPrincipal user) {
        if (user == null || user.getUserId() == null) {
            throw new AccessDeniedException("User not authenticated");
        }
        return Long.parseLong(user.getUserId());
    }
}
