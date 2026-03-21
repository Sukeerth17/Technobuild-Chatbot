package in.technobuild.chatbot.kafka.consumer;

import in.technobuild.chatbot.entity.Document;
import in.technobuild.chatbot.kafka.model.IngestionEvent;
import in.technobuild.chatbot.repository.DocumentRepository;
import in.technobuild.chatbot.service.DocumentIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionConsumer {

    private final DocumentIngestionService documentIngestionService;
    private final DocumentRepository documentRepository;

    @Qualifier("byteArrayRedisTemplate")
    private final RedisTemplate<String, byte[]> byteArrayRedisTemplate;

    @KafkaListener(topics = "${kafka.topics.doc-ingestion}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(IngestionEvent event, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.info("Received event from topic {}: {}", topic, event);

        String key = "ingestion:file:" + event.getJobId();
        byte[] fileBytes = byteArrayRedisTemplate.opsForValue().get(key);
        if (fileBytes == null || fileBytes.length == 0) {
            log.error("File bytes expired or not found for jobId={}", event.getJobId());
            markFailed(event.getDocumentId());
            return;
        }

        try {
            documentIngestionService.processIngestion(event, fileBytes);
            byteArrayRedisTemplate.delete(key);
        } catch (Exception ex) {
            log.error("Document ingestion failed for jobId={}", event.getJobId(), ex);
            markFailed(event.getDocumentId());
        }
    }

    private void markFailed(Long documentId) {
        documentRepository.findById(documentId).ifPresent(document -> {
            document.setStatus(Document.DocumentStatus.FAILED);
            documentRepository.save(document);
        });
    }
}
