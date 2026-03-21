package in.technobuild.chatbot.service;

import in.technobuild.chatbot.entity.AuditLog;
import in.technobuild.chatbot.entity.Feedback;
import in.technobuild.chatbot.repository.AuditLogRepository;
import in.technobuild.chatbot.repository.FeedbackRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final AuditLogRepository auditLogRepository;

    public Feedback saveFeedback(Long userId,
                                 Long messageId,
                                 Long conversationId,
                                 String question,
                                 String answer,
                                 int rating,
                                 String comment) {
        if (rating != 1 && rating != -1) {
            throw new IllegalArgumentException("Rating must be 1 or -1");
        }

        Feedback feedback = Feedback.builder()
                .messageId(messageId)
                .userId(userId)
                .conversationId(conversationId)
                .question(question)
                .answer(answer)
                .rating(rating)
                .comment(comment)
                .flagged(rating == -1)
                .build();

        Feedback saved = feedbackRepository.save(feedback);

        AuditLog auditLog = AuditLog.builder()
                .userId(userId)
                .eventType(AuditLog.EventType.FEEDBACK)
                .sessionId(null)
                .tokensUsed(0)
                .latencyMs(0)
                .build();
        auditLogRepository.save(auditLog);

        return saved;
    }

    public List<Feedback> getFeedbackForUser(Long userId) {
        return feedbackRepository.findByUserId(userId);
    }

    public List<Feedback> getFlaggedFeedback() {
        return feedbackRepository.findByFlaggedTrue();
    }

    public Map<String, Long> getFeedbackSummary(Long userId) {
        List<Feedback> entries = feedbackRepository.findByUserId(userId);
        long thumbsUp = entries.stream().filter(f -> f.getRating() != null && f.getRating() == 1).count();
        long thumbsDown = entries.stream().filter(f -> f.getRating() != null && f.getRating() == -1).count();
        long flagged = entries.stream().filter(f -> Boolean.TRUE.equals(f.getFlagged())).count();

        Map<String, Long> summary = new HashMap<>();
        summary.put("thumbsUp", thumbsUp);
        summary.put("thumbsDown", thumbsDown);
        summary.put("flagged", flagged);
        return summary;
    }
}
