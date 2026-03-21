package in.technobuild.chatbot.service;

import in.technobuild.chatbot.client.PythonAiClient;
import in.technobuild.chatbot.entity.Feedback;
import in.technobuild.chatbot.repository.FeedbackRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class HallucinationGuardService {

    private static final String DISCLAIMER =
            "Note: This answer may need verification. Please confirm with official sources.\n\n";

    private final PythonAiClient pythonAiClient;
    private final FeedbackRepository feedbackRepository;

    public HallucinationGuardService(PythonAiClient pythonAiClient, FeedbackRepository feedbackRepository) {
        this.pythonAiClient = pythonAiClient;
        this.feedbackRepository = feedbackRepository;
    }

    public boolean isGrounded(String answer, List<String> retrievedChunks) {
        try {
            return pythonAiClient.checkConfidence(answer, retrievedChunks);
        } catch (Exception ex) {
            log.warn("Confidence check failed, allowing response to pass", ex);
            return true;
        }
    }

    public String addDisclaimerIfNeeded(String answer, List<String> retrievedChunks) {
        return isGrounded(answer, retrievedChunks) ? answer : DISCLAIMER + answer;
    }

    public void flagForReview(Long messageId, Long userId, String answer, List<String> chunks) {
        log.warn("Low confidence answer for messageId {} userId {}", messageId, userId);
        Feedback feedback = Feedback.builder()
                .messageId(messageId)
                .userId(userId)
                .conversationId(0L)
                .question("AUTO_FLAGGED_LOW_CONFIDENCE")
                .answer(answer == null ? "" : answer)
                .rating(-1)
                .comment("Automatically flagged by HallucinationGuardService")
                .flagged(true)
                .build();
        feedbackRepository.save(feedback);
    }
}
