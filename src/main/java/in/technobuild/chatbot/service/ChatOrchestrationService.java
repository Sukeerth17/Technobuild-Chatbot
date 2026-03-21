package in.technobuild.chatbot.service;

import in.technobuild.chatbot.entity.AuditLog;
import in.technobuild.chatbot.entity.Conversation;
import in.technobuild.chatbot.kafka.model.ChatRequestEvent;
import in.technobuild.chatbot.repository.AuditLogRepository;
import in.technobuild.chatbot.repository.MessageRepository;
import in.technobuild.chatbot.sse.SseEventPublisher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatOrchestrationService {

    private final ConversationService conversationService;
    private final RagService ragService;
    private final PromptBuilderService promptBuilderService;
    private final OllamaService ollamaService;
    private final SseEventPublisher sseEventPublisher;
    private final HallucinationGuardService hallucinationGuardService;
    private final CostTrackerService costTrackerService;
    private final AuditLogRepository auditLogRepository;
    private final MessageRepository messageRepository;

    public void processChat(ChatRequestEvent event) {
        try {
            Conversation conversation = conversationService.getOrCreateConversation(event.getUserId(), event.getSessionId());
            List<in.technobuild.chatbot.entity.Message> history = conversationService.loadHistory(conversation.getSessionId());
            List<String> chunks = ragService.retrieveRelevantChunks(event.getUserMessage(), null);

            String historyContext = conversationService.buildHistoryContext(history);
            String prompt = promptBuilderService.buildChatPrompt(
                    null,
                    event.getUserName(),
                    event.getUserRole(),
                    event.getCurrentDateTime(),
                    chunks,
                    historyContext,
                    event.getUserMessage()
            );

            StringBuilder assistantResponseBuffer = new StringBuilder();
            AtomicBoolean completed = new AtomicBoolean(false);

            ollamaService.streamChatResponse(prompt, token -> {
                if ("[DONE]".equals(token)) {
                    if (completed.compareAndSet(false, true)) {
                        handleCompletion(event, conversation, chunks, assistantResponseBuffer.toString());
                    }
                    return;
                }

                assistantResponseBuffer.append(token);
                sseEventPublisher.sendToken(event.getMessageId(), token);
            });

            if (!completed.get()) {
                handleCompletion(event, conversation, chunks, assistantResponseBuffer.toString());
            }
        } catch (Exception ex) {
            log.error("Chat orchestration failed for messageId={}", event.getMessageId(), ex);
            sseEventPublisher.sendError(event.getMessageId(), "Something went wrong. Please try again.");
        }
    }

    private void handleCompletion(ChatRequestEvent event,
                                  Conversation conversation,
                                  List<String> chunks,
                                  String assistantResponseRaw) {
        String assistantResponse = assistantResponseRaw == null ? "" : assistantResponseRaw.trim();
        boolean grounded = hallucinationGuardService.isGrounded(assistantResponse, chunks);
        if (!grounded) {
            hallucinationGuardService.flagForReview(event.getConversationId(), event.getUserId(), assistantResponse, chunks);
        }
        assistantResponse = hallucinationGuardService.addDisclaimerIfNeeded(assistantResponse, chunks);

        int userTokens = promptBuilderService.estimatePromptTokens(event.getUserMessage());
        int assistantTokens = promptBuilderService.estimatePromptTokens(assistantResponse);

        conversationService.saveMessage(conversation.getId(), "USER", event.getUserMessage(), userTokens);
        conversationService.saveMessage(conversation.getId(), "ASSISTANT", assistantResponse, assistantTokens);
        costTrackerService.recordUsage(event.getUserId(), userTokens, assistantTokens);

        saveAuditLog(event, conversation, assistantResponse, userTokens + assistantTokens);
        sseEventPublisher.sendComplete(event.getMessageId());
    }

    private void saveAuditLog(ChatRequestEvent event,
                              Conversation conversation,
                              String response,
                              int tokensUsed) {
        AuditLog auditLog = AuditLog.builder()
                .userId(event.getUserId())
                .sessionId(conversation.getSessionId())
                .eventType(AuditLog.EventType.ANSWER)
                .questionHash(sha256(event.getUserMessage()))
                .responseHash(sha256(response))
                .tokensUsed(tokensUsed)
                .latencyMs(0)
                .createdAt(LocalDateTime.now())
                .build();

        auditLogRepository.save(auditLog);
        log.info("Saved audit log for messageId={}", event.getMessageId());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            log.error("Failed to hash value", ex);
            return "";
        }
    }
}
