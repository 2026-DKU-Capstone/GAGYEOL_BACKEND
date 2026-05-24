package GAGYELOL.dto.approval;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class ApprovalResponse {
    private Long requestId;
    private String status;
    private String businessName;
    private String formName;
    private Integer currentApprovalOrder;
    private Map<String, String> filledFields;
    private List<StepSummary> steps;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Getter
    @Builder
    public static class StepSummary {
        private String approverName;
        private Integer approvalOrder;
        private String roleName;
        private String action;
        private String comment;
        private LocalDateTime actedAt;
    }
}
