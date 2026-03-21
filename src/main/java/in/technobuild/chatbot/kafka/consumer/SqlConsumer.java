package in.technobuild.chatbot.kafka.consumer;

import in.technobuild.chatbot.entity.SqlAuditLog;
import in.technobuild.chatbot.kafka.model.SqlRequestEvent;
import in.technobuild.chatbot.repository.SqlAuditLogRepository;
import in.technobuild.chatbot.service.ConversationService;
import in.technobuild.chatbot.service.CostTrackerService;
import in.technobuild.chatbot.service.OllamaService;
import in.technobuild.chatbot.service.PromptBuilderService;
import in.technobuild.chatbot.service.SqlExecutionService;
import in.technobuild.chatbot.service.SqlGenerationService;
import in.technobuild.chatbot.sse.SseEventPublisher;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlConsumer {

    private final SqlGenerationService sqlGenerationService;
    private final OllamaService ollamaService;
    private final SseEventPublisher sseEventPublisher;
    private final SqlAuditLogRepository sqlAuditLogRepository;
    private final ConversationService conversationService;
    private final CostTrackerService costTrackerService;
    private final SqlExecutionService sqlExecutionService;
    private final PromptBuilderService promptBuilderService;

    @KafkaListener(topics = "${kafka.topics.sql-requests}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(SqlRequestEvent event, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        try {
            log.info("Received event from topic {}: {}", topic, event);

            boolean firstRequest = event.getSessionId() == null || event.getSessionId().isBlank();
            SqlGenerationService.PythonSqlResponse pythonResponse =
                    sqlGenerationService.callPythonGetResponse(event.getOriginalQuestion(), event.getSessionId(), firstRequest);

            if (pythonResponse == null) {
                sseEventPublisher.sendError(event.getMessageId(), "Python service unavailable.");
                return;
            }

            List<Map<String, Object>> rows = pythonResponse.response() == null ? List.of() : pythonResponse.response();
            String formattedRows = sqlExecutionService.formatRowsAsText(rows);
            String formattingPrompt =
                    promptBuilderService.buildSqlFormattingPrompt(formattedRows, event.getOriginalQuestion());
            String englishResponse = ollamaService.generateChatResponse(formattingPrompt);

            if (englishResponse == null || englishResponse.isBlank()) {
                englishResponse = "I could not format the SQL result right now.";
            }

            String[] tokens = englishResponse.split("\\s+");
            for (String token : tokens) {
                sseEventPublisher.sendToken(event.getMessageId(), token + " ");
            }

            sseEventPublisher.sendComplete(event.getMessageId());

            int userTokens = promptBuilderService.estimatePromptTokens(event.getOriginalQuestion());
            int assistantTokens = promptBuilderService.estimatePromptTokens(englishResponse);
            conversationService.saveMessage(event.getConversationId(), "USER", event.getOriginalQuestion(), userTokens);
            conversationService.saveMessage(event.getConversationId(), "ASSISTANT", englishResponse, assistantTokens);
            costTrackerService.recordUsage(event.getUserId(), userTokens, assistantTokens);

            SqlAuditLog auditLog = SqlAuditLog.builder()
                    .userId(event.getUserId())
                    .sessionId(event.getSessionId())
                    .originalQuestion(event.getOriginalQuestion())
                    .generatedSql("Python /getResponse pipeline")
                    .validated(true)
                    .executed(true)
                    .rowCount(rows.size())
                    .executionMs(0)
                    .build();
            sqlAuditLogRepository.save(auditLog);
        } catch (Exception ex) {
            log.error("SQL consumer failed for messageId={}", event.getMessageId(), ex);
            sseEventPublisher.sendError(event.getMessageId(), "Something went wrong. Please try again.");
        }
    }
}
