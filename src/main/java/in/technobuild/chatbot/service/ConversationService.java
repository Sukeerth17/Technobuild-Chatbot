package in.technobuild.chatbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.technobuild.chatbot.entity.Conversation;
import in.technobuild.chatbot.entity.Message;
import in.technobuild.chatbot.repository.ConversationRepository;
import in.technobuild.chatbot.repository.MessageRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final int HISTORY_LIMIT = 8;
    private static final int HISTORY_TOKEN_LIMIT = 4000;
    private static final Duration REDIS_TTL = Duration.ofHours(24);

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    @Qualifier("stringRedisTemplate")
    private final RedisTemplate<String, String> redisTemplate;

    private final TokenCounterService tokenCounterService;
    private final ObjectMapper objectMapper;

    public Conversation getOrCreateConversation(Long userId, String sessionId) {
        String resolvedSessionId = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        return conversationRepository.findBySessionId(resolvedSessionId)
                .orElseGet(() -> {
                    Conversation conversation = Conversation.builder()
                            .userId(userId)
                            .sessionId(resolvedSessionId)
                            .title("New Conversation")
                            .build();
                    log.info("Creating new conversation for userId={}, sessionId={}", userId, resolvedSessionId);
                    return conversationRepository.save(conversation);
                });
    }

    public List<Message> loadHistory(String sessionId) {
        String key = getRedisHistoryKey(sessionId);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                List<Message> cachedMessages = objectMapper.readValue(cached, new TypeReference<List<Message>>() {
                });
                List<Message> sorted = sortAndLimit(cachedMessages);
                List<Message> trimmed = tokenCounterService.trimToTokenLimit(sorted, HISTORY_TOKEN_LIMIT);
                cacheHistory(key, trimmed);
                return trimmed;
            }
        } catch (Exception ex) {
            log.error("Failed to read history from Redis for sessionId={}", sessionId, ex);
        }

        List<Message> fromDb = conversationRepository.findBySessionId(sessionId)
                .map(conversation -> messageRepository.findTop8ByConversationIdOrderByCreatedAtDesc(conversation.getId()))
                .orElseGet(ArrayList::new);

        List<Message> sorted = sortAndLimit(fromDb);
        List<Message> trimmed = tokenCounterService.trimToTokenLimit(sorted, HISTORY_TOKEN_LIMIT);
        cacheHistory(key, trimmed);
        return trimmed;
    }

    public Message saveMessage(Long conversationId, String role, String content, int tokensUsed) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found for id=" + conversationId));

        Message message = Message.builder()
                .conversationId(conversationId)
                .userId(conversation.getUserId())
                .role(parseRole(role))
                .content(content)
                .tokensUsed(tokensUsed)
                .build();

        Message saved = messageRepository.save(message);

        List<Message> updatedHistory = new ArrayList<>(loadHistory(conversation.getSessionId()));
        updatedHistory.add(saved);
        updatedHistory = sortAndLimit(updatedHistory);
        updatedHistory = tokenCounterService.trimToTokenLimit(updatedHistory, HISTORY_TOKEN_LIMIT);
        cacheHistory(getRedisHistoryKey(conversation.getSessionId()), updatedHistory);

        return saved;
    }

    public String buildHistoryContext(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        for (Message message : messages) {
            if (message.getRole() == Message.Role.USER) {
                builder.append("User: ").append(message.getContent()).append("\n");
            } else {
                builder.append("Assistant: ").append(message.getContent()).append("\n");
            }
        }
        return builder.toString();
    }

    public void deleteUserHistory(Long userId) {
        List<Conversation> conversations = conversationRepository.findByUserIdOrderByLastActiveDesc(userId);
        for (Conversation conversation : conversations) {
            redisTemplate.delete(getRedisHistoryKey(conversation.getSessionId()));
        }
        messageRepository.deleteByUserId(userId);
        conversationRepository.deleteByUserId(userId);
        log.info("Deleted user history for userId={}", userId);
    }

    public List<Conversation> getConversationsForUser(Long userId) {
        return conversationRepository.findByUserIdOrderByLastActiveDesc(userId);
    }

    public List<Message> getMessagesForUserSession(Long userId, String sessionId) {
        Conversation conversation = conversationRepository.findByUserIdAndSessionId(userId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found for this user"));
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
    }

    public void deleteConversationBySession(Long userId, String sessionId) {
        Conversation conversation = conversationRepository.findByUserIdAndSessionId(userId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found for this user"));
        messageRepository.deleteByConversationId(conversation.getId());
        conversationRepository.delete(conversation);
        redisTemplate.delete(getRedisHistoryKey(sessionId));
        log.info("Deleted conversation for userId={} sessionId={}", userId, sessionId);
    }

    private Message.Role parseRole(String role) {
        return "ASSISTANT".equalsIgnoreCase(role) ? Message.Role.ASSISTANT : Message.Role.USER;
    }

    private String getRedisHistoryKey(String sessionId) {
        return "chat:history:" + sessionId;
    }

    private void cacheHistory(String key, List<Message> messages) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(messages), REDIS_TTL);
        } catch (Exception ex) {
            log.error("Failed to cache history for key={}", key, ex);
        }
    }

    private List<Message> sortAndLimit(List<Message> messages) {
        List<Message> sorted = new ArrayList<>(messages);
        sorted.sort(Comparator.comparing(Message::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        if (sorted.size() <= HISTORY_LIMIT) {
            return sorted;
        }
        return new ArrayList<>(sorted.subList(sorted.size() - HISTORY_LIMIT, sorted.size()));
    }
}
