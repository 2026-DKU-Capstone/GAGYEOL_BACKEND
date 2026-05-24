package GAGYELOL.service;

import GAGYELOL.dto.dashboard.DashboardSummaryResponse;
import GAGYELOL.dto.dashboard.MonthlyDirectoryResponse;
import GAGYELOL.dto.dashboard.RecentApprovalsResponse;
import GAGYELOL.entity.ApprovalRequest;
import GAGYELOL.repository.ApprovalRequestRepository;
import GAGYELOL.repository.UserGroupRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final ApprovalRequestRepository requestRepository;
    private final UserGroupRepository groupRepository;
    private final ObjectMapper objectMapper;

    public DashboardSummaryResponse getSummary(Long userId, Long groupId) {
        validateGroup(groupId);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime thisMonthStart = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime prevMonthStart = thisMonthStart.minusMonths(1);
        LocalDateTime prevMonthEnd = thisMonthStart.minusNanos(1);

        List<ApprovalRequest> thisMonth = requestRepository
                .findByRequesterIdAndGroupIdAndCreatedAtBetween(userId, groupId, thisMonthStart, now);
        List<ApprovalRequest> prevMonth = requestRepository
                .findByRequesterIdAndGroupIdAndCreatedAtBetween(userId, groupId, prevMonthStart, prevMonthEnd);

        long monthlyAmount = thisMonth.stream()
                .filter(r -> "APPROVED".equals(r.getStatus()))
                .mapToLong(r -> extractAmount(r.getFilledFields())).sum();
        long prevMonthAmount = prevMonth.stream()
                .filter(r -> "APPROVED".equals(r.getStatus()))
                .mapToLong(r -> extractAmount(r.getFilledFields())).sum();

        List<ApprovalRequest> inProgressList = requestRepository
                .findByRequesterIdAndGroupIdAndStatus(userId, groupId, "IN_PROGRESS");
        double avgWaiting = inProgressList.stream()
                .mapToDouble(r -> ChronoUnit.HOURS.between(r.getCreatedAt(), now) / 24.0)
                .average().orElse(0.0);

        long approved = thisMonth.stream().filter(r -> "APPROVED".equals(r.getStatus())).count();
        long rejected = thisMonth.stream().filter(r -> "REJECTED".equals(r.getStatus())).count();
        int complianceRate = (approved + rejected > 0) ? (int) (approved * 100 / (approved + rejected)) : 100;

        return DashboardSummaryResponse.builder()
                .monthlySubmitCount(thisMonth.size())
                .prevMonthSubmitCount(prevMonth.size())
                .monthlyTotalAmount(monthlyAmount)
                .prevMonthTotalAmount(prevMonthAmount)
                .inProgressCount(inProgressList.size())
                .avgWaitingDays(Math.round(avgWaiting * 10) / 10.0)
                .complianceRate(complianceRate)
                .build();
    }

    public List<MonthlyDirectoryResponse> getMonthlyDirectory(Long userId, Long groupId) {
        validateGroup(groupId);

        List<ApprovalRequest> all = requestRepository
                .findByRequesterIdAndGroupIdOrderByCreatedAtDesc(userId, groupId);

        Map<String, List<ApprovalRequest>> grouped = all.stream()
                .collect(Collectors.groupingBy(r ->
                        r.getCreatedAt().getYear() + "-" + r.getCreatedAt().getMonthValue()));

        return grouped.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<ApprovalRequest>>, String>
                        comparing(Map.Entry::getKey).reversed())
                .map(entry -> {
                    String[] parts = entry.getKey().split("-");
                    List<MonthlyDirectoryResponse.FileEntry> files = entry.getValue().stream()
                            .map(r -> MonthlyDirectoryResponse.FileEntry.builder()
                                    .requestId(r.getId())
                                    .fileName(resolveFileName(r))
                                    .date(r.getCreatedAt().format(DateTimeFormatter.ofPattern("MM-dd")))
                                    .status(r.getStatus())
                                    .build())
                            .toList();
                    return MonthlyDirectoryResponse.builder()
                            .year(Integer.parseInt(parts[0]))
                            .month(Integer.parseInt(parts[1]))
                            .count(files.size())
                            .files(files)
                            .build();
                })
                .toList();
    }

    public RecentApprovalsResponse getRecentApprovals(Long userId, Long groupId, String status) {
        validateGroup(groupId);

        List<ApprovalRequest> all = requestRepository
                .findByRequesterIdAndGroupIdOrderByCreatedAtDesc(userId, groupId);

        long reviewing = all.stream().filter(r -> "IN_PROGRESS".equals(r.getStatus())).count();
        long approved = all.stream().filter(r -> "APPROVED".equals(r.getStatus())).count();
        long rejected = all.stream().filter(r -> "REJECTED".equals(r.getStatus())).count();

        List<ApprovalRequest> filtered = (status != null && !status.isBlank())
                ? all.stream().filter(r -> r.getStatus().equals(status)).toList()
                : all;

        List<RecentApprovalsResponse.ApprovalItem> items = filtered.stream()
                .limit(5)
                .map(r -> RecentApprovalsResponse.ApprovalItem.builder()
                        .requestId(r.getId())
                        .title(resolveTitle(r))
                        .expCode(String.format("EXP-%d-%04d", r.getCreatedAt().getYear(), r.getId()))
                        .date(r.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                        .requesterName(r.getRequester().getName())
                        .status(r.getStatus())
                        .build())
                .toList();

        return RecentApprovalsResponse.builder()
                .reviewingCount(reviewing)
                .approvedCount(approved)
                .rejectedCount(rejected)
                .items(items)
                .build();
    }

    private String resolveFileName(ApprovalRequest r) {
        if (r.getEvidence() != null && r.getEvidence().getFileName() != null) {
            return r.getEvidence().getFileName();
        }
        if (r.getForm() != null) {
            return r.getForm().getFormName() + ".pdf";
        }
        return "결의서_" + r.getCreatedAt().format(DateTimeFormatter.ofPattern("MMdd")) + ".pdf";
    }

    private String resolveTitle(ApprovalRequest r) {
        try {
            Map<String, String> fields = objectMapper.readValue(
                    r.getFilledFields() != null ? r.getFilledFields() : "{}", new com.fasterxml.jackson.core.type.TypeReference<>() {});
            String businessName = fields.get("사업명");
            if (businessName != null && !businessName.isBlank()) return businessName;
        } catch (Exception ignored) {}
        if (r.getForm() != null && r.getForm().getFormName() != null) return r.getForm().getFormName();
        return "지출결의서";
    }

    private long extractAmount(String filledFieldsJson) {
        if (filledFieldsJson == null || filledFieldsJson.isBlank()) return 0;
        try {
            Map<String, String> fields = objectMapper.readValue(filledFieldsJson, new TypeReference<>() {});
            // 합계 포함 키 우선
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                if (entry.getKey().contains("합계")) {
                    String numeric = entry.getValue().replaceAll("[^0-9]", "");
                    if (!numeric.isEmpty()) return Long.parseLong(numeric);
                }
            }
            // 금액 포함 키 중 최댓값
            return fields.entrySet().stream()
                    .filter(e -> e.getKey().contains("금액"))
                    .mapToLong(e -> {
                        String numeric = e.getValue().replaceAll("[^0-9]", "");
                        return numeric.isEmpty() ? 0 : Long.parseLong(numeric);
                    })
                    .max().orElse(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private void validateGroup(Long groupId) {
        groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("그룹을 찾을 수 없습니다."));
    }
}
