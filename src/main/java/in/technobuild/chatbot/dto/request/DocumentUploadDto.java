package in.technobuild.chatbot.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadDto {

    @Size(max = 100)
    private String category;

    @Pattern(
            regexp = "CUSTOMER|INTERNAL|ADMIN",
            message = "audience must be CUSTOMER, INTERNAL, or ADMIN"
    )
    @Builder.Default
    private String audience = "CUSTOMER";
}
