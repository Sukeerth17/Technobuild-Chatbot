package in.technobuild.chatbot.repository;

import in.technobuild.chatbot.entity.VectorChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface VectorChunkRepository extends JpaRepository<VectorChunk, Long> {

    List<VectorChunk> findByDocumentId(Long documentId);

    List<VectorChunk> findByCategory(String category);

    void deleteByDocumentId(Long documentId);

    @Query(value = "SELECT * FROM vector_chunks WHERE " +
            "MATCH(content) AGAINST (:query IN BOOLEAN MODE) " +
            "AND (:category IS NULL OR category = :category) " +
            "LIMIT :limit", nativeQuery = true)
    List<VectorChunk> findByFullText(@Param("query") String query,
                                     @Param("category") String category,
                                     @Param("limit") int limit);
}
