package in.technobuild.chatbot.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackRequestDto {

    @NotNull
    private Long messageId;

    @NotNull
    private Long conversationId;

    @NotBlank
    @Size(max = 5000)
    private String question;

    @NotBlank
    @Size(max = 5000)
    private String answer;

    @NotNull
    @Min(-1)
    @Max(1)
    private Integer rating;

    @Size(max = 1000)
    private String comment;
}
