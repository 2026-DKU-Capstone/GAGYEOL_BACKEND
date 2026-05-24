package GAGYELOL.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 수령인 학생증/신분증 이미지 OCR 추출 결과. (#3)
 * 못 찾은 값은 빈 문자열로 반환되며, 프론트엔드에서 미입력 필드로 처리합니다.
 */
@Getter
@Builder
public class RecipientInfoResponse {
    private String name;
    private String affiliation;
    private String studentId;
    private String phone;
}
