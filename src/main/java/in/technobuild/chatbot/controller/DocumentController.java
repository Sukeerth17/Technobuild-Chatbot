package in.technobuild.chatbot.controller;

import in.technobuild.chatbot.dto.request.DocumentUploadDto;
import in.technobuild.chatbot.dto.response.IngestionStatusDto;
import in.technobuild.chatbot.entity.Document;
import in.technobuild.chatbot.security.UserPrincipal;
import in.technobuild.chatbot.service.DocumentIngestionService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/docs")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentIngestionService documentIngestionService;

    @Qualifier("byteArrayRedisTemplate")
    private final RedisTemplate<String, byte[]> byteArrayRedisTemplate;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<IngestionStatusDto> upload(
            @RequestParam("file") MultipartFile file,
            @Valid @ModelAttribute DocumentUploadDto request,
            @AuthenticationPrincipal UserPrincipal user) {

        Long userId = Long.parseLong(user.getUserId());
        String jobId = documentIngestionService.initiateIngestion(
                file,
                request.getCategory(),
                request.getAudience(),
                userId
        );

        try {
            byteArrayRedisTemplate.opsForValue().set("ingestion:file:" + jobId, file.getBytes(), Duration.ofHours(1));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to cache uploaded file bytes", ex);
        }

        IngestionStatusDto response = IngestionStatusDto.builder()
                .jobId(jobId)
                .status(Document.DocumentStatus.PENDING.name())
                .fileName(file.getOriginalFilename())
                .chunkCount(0)
                .message("Document accepted for ingestion")
                .build();

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<IngestionStatusDto> status(@PathVariable String jobId) {
        return ResponseEntity.ok(documentIngestionService.getIngestionStatus(jobId));
    }

    @GetMapping("/list")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Document>> list() {
        return ResponseEntity.ok(documentIngestionService.listAllDocuments());
    }

    @DeleteMapping("/{documentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long documentId,
                                       @AuthenticationPrincipal UserPrincipal user) {
        documentIngestionService.deleteDocument(documentId, Long.parseLong(user.getUserId()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{documentId}/re-embed")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> reEmbed(@PathVariable Long documentId) {
        documentIngestionService.reEmbedDocument(documentId);
        return ResponseEntity.accepted().build();
    }
}
