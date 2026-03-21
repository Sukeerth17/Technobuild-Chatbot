package in.technobuild.chatbot.service;

import in.technobuild.chatbot.entity.Document;
import in.technobuild.chatbot.repository.DocumentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StaleDocumentDetector {

    private final DocumentRepository documentRepository;

    @Scheduled(cron = "0 0 2 * * *")
    public void detectStaleDocuments() {
        List<Document> staleDocuments = documentRepository.findStaleDocuments();
        for (Document document : staleDocuments) {
            document.setStatus(Document.DocumentStatus.STALE);
            documentRepository.save(document);
            log.warn("Document {} is stale, needs re-embedding: {}", document.getId(), document.getFileName());
        }

        if (!staleDocuments.isEmpty()) {
            log.warn("{} stale documents detected", staleDocuments.size());
        }
    }

    public List<Document> getStaleDocuments() {
        return documentRepository.findStaleDocuments();
    }

    public void markAsStale(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        document.setStatus(Document.DocumentStatus.STALE);
        documentRepository.save(document);
        log.info("Document {} marked as STALE", documentId);
    }
}
