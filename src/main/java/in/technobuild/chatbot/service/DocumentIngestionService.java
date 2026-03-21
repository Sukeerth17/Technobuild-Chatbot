package in.technobuild.chatbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.technobuild.chatbot.client.PythonAiClient;
import in.technobuild.chatbot.dto.response.IngestionStatusDto;
import in.technobuild.chatbot.entity.Document;
import in.technobuild.chatbot.entity.VectorChunk;
import in.technobuild.chatbot.kafka.model.IngestionEvent;
import in.technobuild.chatbot.kafka.producer.IngestionProducer;
import in.technobuild.chatbot.repository.DocumentRepository;
import in.technobuild.chatbot.repository.VectorChunkRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final long MAX_FILE_SIZE = 52_428_800L;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private final PythonAiClient pythonAiClient;
    private final VectorChunkRepository vectorChunkRepository;
    private final DocumentRepository documentRepository;
    private final IngestionProducer ingestionProducer;
    private final ObjectMapper objectMapper;

    @Qualifier("byteArrayRedisTemplate")
    private final RedisTemplate<String, byte[]> byteArrayRedisTemplate;

    public String initiateIngestion(MultipartFile file, String category, String audience, Long uploadedBy) {
        validateFile(file);

        try {
            byte[] fileBytes = file.getBytes();
            String fileHash = sha256(fileBytes);

            Document existing = documentRepository.findByFileHash(fileHash).orElse(null);
            if (existing != null) {
                if (existing.getJobId() == null || existing.getJobId().isBlank()) {
                    existing.setJobId(UUID.randomUUID().toString());
                    documentRepository.save(existing);
                }
                return existing.getJobId();
            }

            String jobId = UUID.randomUUID().toString();
            Document document = Document.builder()
                    .jobId(jobId)
                    .fileName(file.getOriginalFilename())
                    .fileHash(fileHash)
                    .fileType(resolveFileType(file))
                    .category(category)
                    .audience(parseDocumentAudience(audience))
                    .status(Document.DocumentStatus.PENDING)
                    .chunkCount(0)
                    .uploadedBy(uploadedBy)
                    .build();

            Document savedDocument = documentRepository.save(document);
            IngestionEvent event = IngestionEvent.builder()
                    .jobId(jobId)
                    .documentId(savedDocument.getId())
                    .fileName(savedDocument.getFileName())
                    .fileType(savedDocument.getFileType())
                    .category(savedDocument.getCategory())
                    .audience(savedDocument.getAudience() == null
                            ? Document.Audience.CUSTOMER.name()
                            : savedDocument.getAudience().name())
                    .uploadedBy(savedDocument.getUploadedBy())
                    .timestamp(System.currentTimeMillis())
                    .build();

            ingestionProducer.publish(event);
            return jobId;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initiate document ingestion", ex);
        }
    }

    public void processIngestion(IngestionEvent event, byte[] fileBytes) {
        Document document = documentRepository.findById(event.getDocumentId())
                .orElseThrow(() -> new IllegalArgumentException("Document not found for id=" + event.getDocumentId()));

        try {
            document.setStatus(Document.DocumentStatus.PROCESSING);
            documentRepository.save(document);

            PythonAiClient.ParseDocumentResult parseResult =
                    pythonAiClient.parseDocument(fileBytes, event.getFileType(), event.getFileName());

            if (parseResult.text() == null || parseResult.text().isBlank()) {
                markDocumentFailed(document, "No text extracted from document");
                return;
            }

            List<String> chunks = pythonAiClient.chunkDocument(parseResult.text());
            if (chunks.isEmpty()) {
                markDocumentFailed(document, "Chunking returned no chunks");
                return;
            }

            List<List<Float>> embeddings = pythonAiClient.embedBatch(chunks);
            if (embeddings.isEmpty()) {
                markDocumentFailed(document, "Embedding generation failed");
                return;
            }

            int pairCount = Math.min(chunks.size(), embeddings.size());
            if (pairCount == 0) {
                markDocumentFailed(document, "No chunk/embedding pairs available");
                return;
            }

            vectorChunkRepository.deleteByDocumentId(document.getId());

            List<VectorChunk> vectorChunks = new ArrayList<>(pairCount);
            for (int i = 0; i < pairCount; i++) {
                String chunk = chunks.get(i);
                String embeddingJson = objectMapper.writeValueAsString(embeddings.get(i));

                vectorChunks.add(VectorChunk.builder()
                        .documentId(document.getId())
                        .content(chunk)
                        .embedding(embeddingJson)
                        .chunkIndex(i)
                        .tokenCount(countWords(chunk))
                        .heading(extractHeading(chunk))
                        .sourceFile(event.getFileName())
                        .category(event.getCategory())
                        .audience(parseVectorAudience(event.getAudience()))
                        .build());
            }

            vectorChunkRepository.saveAll(vectorChunks);

            document.setStatus(Document.DocumentStatus.COMPLETED);
            document.setChunkCount(pairCount);
            document.setLastEmbedded(LocalDateTime.now());
            documentRepository.save(document);

            log.info("Ingested {} chunks from {}", pairCount, event.getFileName());
        } catch (Exception ex) {
            markDocumentFailed(document, ex.getMessage());
            throw new IllegalStateException("Ingestion failed for jobId=" + event.getJobId(), ex);
        }
    }

    public IngestionStatusDto getIngestionStatus(String jobId) {
        Document document = documentRepository.findByJobId(jobId)
                .orElseThrow(() -> new IllegalArgumentException("No document found for jobId=" + jobId));

        return IngestionStatusDto.builder()
                .jobId(document.getJobId())
                .status(document.getStatus() == null ? "UNKNOWN" : document.getStatus().name())
                .fileName(document.getFileName())
                .chunkCount(document.getChunkCount() == null ? 0 : document.getChunkCount())
                .message("Ingestion status fetched")
                .build();
    }

    public List<Document> listAllDocuments() {
        return documentRepository.findAllByOrderByCreatedAtDesc();
    }

    public void deleteDocument(Long documentId, Long userId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found for id=" + documentId));
        vectorChunkRepository.deleteByDocumentId(documentId);
        documentRepository.delete(document);
        log.info("Document {} deleted by user {}", documentId, userId);
    }

    public void reEmbedDocument(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found for id=" + documentId));

        String key = fileRedisKey(document.getJobId());
        byte[] fileBytes = byteArrayRedisTemplate.opsForValue().get(key);
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalStateException("Re-embed requires file bytes in Redis. Re-upload document first.");
        }

        IngestionEvent event = IngestionEvent.builder()
                .jobId(document.getJobId())
                .documentId(document.getId())
                .fileName(document.getFileName())
                .fileType(document.getFileType())
                .category(document.getCategory())
                .audience(document.getAudience() == null
                        ? Document.Audience.CUSTOMER.name()
                        : document.getAudience().name())
                .uploadedBy(document.getUploadedBy())
                .timestamp(System.currentTimeMillis())
                .build();

        processIngestion(event, fileBytes);
    }

    public void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds 50MB");
        }

        String contentType = file.getContentType();
        if (contentType != null && ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            return;
        }

        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!(filename.endsWith(".pdf") || filename.endsWith(".docx") || filename.endsWith(".txt") || filename.endsWith(".xlsx"))) {
            throw new IllegalArgumentException("Unsupported file type");
        }
    }

    private void markDocumentFailed(Document document, String reason) {
        document.setStatus(Document.DocumentStatus.FAILED);
        documentRepository.save(document);
        log.error("Document ingestion failed for docId={}, reason={}", document.getId(), reason);
    }

    private String resolveFileType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            return contentType;
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (filename.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (filename.endsWith(".txt")) {
            return "text/plain";
        }
        if (filename.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return "application/octet-stream";
    }

    private Document.Audience parseDocumentAudience(String audience) {
        if (audience == null || audience.isBlank()) {
            return Document.Audience.CUSTOMER;
        }
        try {
            return Document.Audience.valueOf(audience.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return Document.Audience.CUSTOMER;
        }
    }

    private VectorChunk.Audience parseVectorAudience(String audience) {
        if (audience == null || audience.isBlank()) {
            return VectorChunk.Audience.CUSTOMER;
        }
        try {
            return VectorChunk.Audience.valueOf(audience.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return VectorChunk.Audience.CUSTOMER;
        }
    }

    private String sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash file", ex);
        }
    }

    private int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private String extractHeading(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return null;
        }
        String compact = chunk.replaceAll("\\s+", " ").trim();
        return compact.length() <= 120 ? compact : compact.substring(0, 120);
    }

    private String fileRedisKey(String jobId) {
        return "ingestion:file:" + jobId;
    }
}
