package in.technobuild.chatbot.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadDto {

    private String category;

    @Builder.Default
    private String audience = "CUSTOMER";
}
