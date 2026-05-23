package GAGYELOL.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class EvidenceListResponse {
    private Long evidenceId;
    private Long requestId;      // 연결된 결재요청 ID (approved/in_progress 시 존재)
    private String businessName;
    private String title;        // businessName과 동일
    private String status;       // draft / in_progress / approved / rejected
    private Integer totalAmount; // 현재 미지원, null 반환
    private Integer itemCount;   // 현재 미지원, null 반환
    private String fileType;     // DOCX / XLSX / PDF / JPG 등
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
