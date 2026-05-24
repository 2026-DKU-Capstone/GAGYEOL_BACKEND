package GAGYELOL.dto.approval;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class ApprovalHistoryResponse {
    private Long requestId;
    private Long requesterUserId;
    private Long evidenceId;
    private Long formId;
    private String formName;
    private String status;
    private Integer currentApprovalOrder;
    private Map<String, String> filledFields;
    private List<ApprovalResponse.StepSummary> steps;
    private List<EditEntry> editHistory;

    @Getter
    @Builder
    public static class EditEntry {
        private Long editId;
        private String editorName;
        private Map<String, String> beforeFields;
        private Map<String, String> afterFields;
        private LocalDateTime editedAt;
    }
}
