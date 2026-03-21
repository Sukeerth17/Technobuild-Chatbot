package in.technobuild.chatbot.service;

import in.technobuild.chatbot.entity.TokenUsage;
import in.technobuild.chatbot.repository.TokenUsageRepository;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CostTrackerService {

    private final TokenUsageRepository tokenUsageRepository;
    private final int dailyTokenLimit;

    public CostTrackerService(
            TokenUsageRepository tokenUsageRepository,
            @Value("${chatbot.rate-limit.daily-token-limit:50000}") int dailyTokenLimit) {
        this.tokenUsageRepository = tokenUsageRepository;
        this.dailyTokenLimit = dailyTokenLimit;
    }

    public void recordUsage(Long userId, int inputTokens, int outputTokens) {
        LocalDate today = LocalDate.now();
        TokenUsage usage = tokenUsageRepository.findByUserIdAndUsageDate(userId, today)
                .orElseGet(() -> TokenUsage.builder()
                        .userId(userId)
                        .usageDate(today)
                        .inputTokens(0)
                        .outputTokens(0)
                        .requestCount(0)
                        .build());

        usage.setInputTokens(nullSafe(usage.getInputTokens()) + Math.max(0, inputTokens));
        usage.setOutputTokens(nullSafe(usage.getOutputTokens()) + Math.max(0, outputTokens));
        usage.setRequestCount(nullSafe(usage.getRequestCount()) + 1);
        tokenUsageRepository.save(usage);
    }

    public int getDailyUsage(Long userId) {
        Long total = tokenUsageRepository.getTotalTokensForUserToday(userId, LocalDate.now());
        return total == null ? 0 : Math.toIntExact(total);
    }

    public boolean isWithinBudget(Long userId) {
        return getDailyUsage(userId) < dailyTokenLimit;
    }

    public int getRemainingBudget(Long userId) {
        return Math.max(0, dailyTokenLimit - getDailyUsage(userId));
    }

    public String getBudgetExceededMessage() {
        return "You have reached your daily limit. Your limit resets at midnight.";
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }
}
