package GAGYELOL.dto.group;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class GroupResponse {
    private Long groupId;
    private String name;
    private String description;
    private String inviteCode;
    private Long ownerId;
    private String ownerName;
    private List<RoleSummary> roles;
    private List<MemberSummary> members;
    private PayerInfo payerInfo;

    @Getter
    @Builder
    public static class PayerInfo {
        private String name;
        private String affiliation;
        private String studentId;
        private String phone;
    }

    @Getter
    @Builder
    public static class RoleSummary {
        private Long roleId;
        private String roleName;
        private Integer approvalOrder;
    }

    @Getter
    @Builder
    public static class MemberSummary {
        private Long userId;
        private String name;
        private String email;
        private String roleName;
        private Integer approvalOrder;
    }
}
