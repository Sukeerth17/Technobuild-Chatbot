package in.technobuild.chatbot.repository;

import in.technobuild.chatbot.entity.Document;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByFileHash(String fileHash);

    Optional<Document> findByJobId(String jobId);

    List<Document> findByStatus(Document.DocumentStatus status);

    List<Document> findByUploadedBy(Long userId);

    List<Document> findAllByOrderByCreatedAtDesc();

    @Query("SELECT d FROM Document d WHERE d.status = in.technobuild.chatbot.entity.Document$DocumentStatus.COMPLETED " +
            "AND d.lastModified > d.lastEmbedded")
    List<Document> findStaleDocuments();
}
