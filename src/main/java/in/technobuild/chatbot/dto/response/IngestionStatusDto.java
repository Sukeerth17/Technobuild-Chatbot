package in.technobuild.chatbot.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionStatusDto {

    private String jobId;
    private String status;
    private String fileName;
    private int chunkCount;
    private String message;
}
