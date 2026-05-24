package GAGYELOL.service;

import GAGYELOL.dto.group.AssignRoleRequest;
import GAGYELOL.dto.group.CreateGroupRequest;
import GAGYELOL.dto.group.GroupResponse;
import GAGYELOL.dto.group.RoleRequest;
import GAGYELOL.entity.*;
import GAGYELOL.repository.*;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class GroupService {

    private final UserRepository userRepository;
    private final UserGroupRepository groupRepository;
    private final GroupRoleRepository roleRepository;
    private final GroupMemberRepository memberRepository;
    private final ApprovalStepRepository approvalStepRepository;
    private final ApprovalRequestRepository approvalRequestRepository;

    public GroupResponse createGroup(Long ownerId, CreateGroupRequest request) {
        User owner = findUser(ownerId);

        UserGroup group = groupRepository.save(UserGroup.builder()
                .name(request.getName())
                .description(request.getDescription())
                .inviteCode(generateInviteCode())
                .owner(owner)
                .build());

        // 역할 생성 (입력 순서 = approval_order)
        List<String> roleNames = request.getRoles();
        for (int i = 0; i < roleNames.size(); i++) {
            roleRepository.save(GroupRole.builder()
                    .group(group)
                    .roleName(roleNames.get(i))
                    .approvalOrder(i)
                    .build());
        }

        // 대표자를 최고 역할(마지막 순서)로 자동 등록
        GroupRole ownerRole = roleRepository.findByGroupAndApprovalOrder(group, roleNames.size() - 1)
                .orElseThrow();
        memberRepository.save(GroupMember.builder()
                .group(group)
                .user(owner)
                .role(ownerRole)
                .build());

        return getGroupDetail(group.getId());
    }

    public GroupResponse joinGroup(Long userId, String inviteCode) {
        User user = findUser(userId);
        UserGroup group = groupRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 초대코드입니다."));

        if (memberRepository.existsByGroupAndUser(group, user)) {
            throw new IllegalArgumentException("이미 가입된 그룹입니다.");
        }

        // 가입 시 최하위 역할(approval_order=0)로 자동 배정
        GroupRole defaultRole = roleRepository.findByGroupAndApprovalOrder(group, 0)
                .orElseThrow(() -> new IllegalArgumentException("그룹 역할 설정이 올바르지 않습니다."));

        memberRepository.save(GroupMember.builder()
                .group(group)
                .user(user)
                .role(defaultRole)
                .build());

        return getGroupDetail(group.getId());
    }

    public void kickMember(Long ownerId, Long groupId, Long targetUserId) {
        UserGroup group = findGroup(groupId);
        validateOwner(ownerId, group);

        User targetUser = findUser(targetUserId);
        if (group.getOwner().getId().equals(targetUserId)) {
            throw new IllegalArgumentException("그룹 대표자는 추방할 수 없습니다.");
        }

        GroupMember member = memberRepository.findByGroupAndUser(group, targetUser)
                .orElseThrow(() -> new IllegalArgumentException("해당 그룹의 멤버가 아닙니다."));

        // 탈퇴 멤버의 PENDING approval_steps 취소 처리
        List<ApprovalStep> pendingSteps = approvalStepRepository.findByApproverAndAction(targetUser, "PENDING");
        for (ApprovalStep step : pendingSteps) {
            ApprovalRequest request = step.getRequest();
            if (!"IN_PROGRESS".equals(request.getStatus())) continue;
            if (!request.getGroup().getId().equals(groupId)) continue;

            step.cancel();
            approvalStepRepository.save(step);
            log.info("멤버 추방으로 approval_step 취소 - stepId={}, requestId={}", step.getId(), request.getId());

            // 같은 단계에 PENDING이 남아있는지 확인 → 없으면 다음 단계 진행
            advanceIfAllDone(request);
        }

        memberRepository.delete(member);
        log.info("멤버 추방 완료 - groupId={}, userId={}", groupId, targetUserId);
    }

    private void advanceIfAllDone(ApprovalRequest request) {
        List<ApprovalStep> sameOrderSteps = approvalStepRepository
                .findByRequestAndApprovalOrder(request, request.getCurrentApprovalOrder());

        boolean anyPending = sameOrderSteps.stream().anyMatch(s -> "PENDING".equals(s.getAction()));
        if (anyPending) return;

        // approve()와 동일하게 CANCELED 제외 후 전원 APPROVED 여부 체크
        List<ApprovalStep> activeSteps = sameOrderSteps.stream()
                .filter(s -> !"CANCELED".equals(s.getAction()))
                .toList();
        boolean allApproved = !activeSteps.isEmpty() && activeSteps.stream().allMatch(s -> "APPROVED".equals(s.getAction()));

        List<GroupRole> nextRoles = roleRepository
                .findByGroupAndApprovalOrderGreaterThanOrderByApprovalOrderAsc(
                        request.getGroup(), request.getCurrentApprovalOrder());

        if (nextRoles.isEmpty()) {
            // 마지막 단계였으면 승인/취소 여부에 따라 최종 처리
            request.updateStatus(allApproved ? "APPROVED" : "CANCELED");
            log.info("결재 최종 처리 (멤버 추방) - requestId={}, status={}", request.getId(), request.getStatus());
        } else {
            // 다음 단계로 진행
            int nextOrder = nextRoles.get(0).getApprovalOrder();
            request.updateApprovalOrder(nextOrder);
            GroupRole nextRole = nextRoles.get(0);
            List<GroupMember> nextApprovers = memberRepository.findByGroupAndRole(request.getGroup(), nextRole);
            for (GroupMember approver : nextApprovers) {
                approvalStepRepository.save(ApprovalStep.builder()
                        .request(request)
                        .approver(approver.getUser())
                        .approvalOrder(nextOrder)
                        .action("PENDING")
                        .build());
            }
            log.info("결재 다음 단계 진행 (멤버 추방) - requestId={}, nextOrder={}", request.getId(), nextOrder);
        }
        approvalRequestRepository.save(request);
    }

    public GroupResponse assignRole(Long ownerId, Long groupId, AssignRoleRequest request) {
        UserGroup group = findGroup(groupId);
        validateOwner(ownerId, group);

        User targetUser = findUser(request.getUserId());
        GroupRole newRole = roleRepository.findById(request.getRoleId())
                .orElseThrow(() -> new IllegalArgumentException("역할을 찾을 수 없습니다."));
        if (!newRole.getGroup().getId().equals(groupId)) {
            throw new IllegalArgumentException("해당 그룹의 역할이 아닙니다.");
        }

        GroupMember member = memberRepository.findByGroupAndUser(group, targetUser)
                .orElseThrow(() -> new IllegalArgumentException("해당 그룹의 멤버가 아닙니다."));

        int oldOrder = member.getRole().getApprovalOrder();
        int newOrder = newRole.getApprovalOrder();
        member.updateRole(newRole);

        if (oldOrder != newOrder) {
            handleRoleChangeForPendingApprovals(group, targetUser, oldOrder, newOrder);
        }

        return getGroupDetail(groupId);
    }

    private void handleRoleChangeForPendingApprovals(UserGroup group, User targetUser, int oldOrder, int newOrder) {
        List<ApprovalRequest> inProgressRequests = approvalRequestRepository.findByGroupAndStatus(group, "IN_PROGRESS");

        for (ApprovalRequest req : inProgressRequests) {
            int currentOrder = req.getCurrentApprovalOrder();

            // 강등: 기존 역할(oldOrder)의 PENDING 스텝 보유 중이고 현재 결재 단계가 oldOrder면 CANCELED
            if (currentOrder == oldOrder) {
                approvalStepRepository.findByRequestAndApproverAndApprovalOrder(req, targetUser, oldOrder)
                        .filter(s -> "PENDING".equals(s.getAction()))
                        .ifPresent(s -> {
                            s.cancel();
                            approvalStepRepository.save(s);
                            log.info("역할 변경으로 PENDING 스텝 취소 - requestId={}, userId={}", req.getId(), targetUser.getId());
                            advanceIfAllDone(req);
                        });
            }

            // 승급: 새 역할(newOrder)이 현재 결재 단계이면 PENDING 스텝 추가
            if (currentOrder == newOrder) {
                boolean alreadyHasStep = approvalStepRepository
                        .findByRequestAndApproverAndApprovalOrder(req, targetUser, newOrder)
                        .isPresent();
                if (!alreadyHasStep) {
                    approvalStepRepository.save(ApprovalStep.builder()
                            .request(req)
                            .approver(targetUser)
                            .approvalOrder(newOrder)
                            .action("PENDING")
                            .build());
                    log.info("역할 변경으로 PENDING 스텝 추가 - requestId={}, userId={}", req.getId(), targetUser.getId());
                }
            }
        }
    }

    public GroupResponse updatePayerInfo(Long ownerId, Long groupId, GAGYELOL.dto.group.PayerInfoRequest request) {
        UserGroup group = findGroup(groupId);
        validateOwner(ownerId, group);
        group.updatePayerInfo(request.getName(), request.getAffiliation(), request.getStudentId(), request.getPhone());
        log.info("지출인 정보 업데이트 - groupId={}", groupId);
        return getGroupDetail(groupId);
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroupDetail(Long groupId) {
        UserGroup group = findGroup(groupId);
        List<GroupRole> roles = roleRepository.findByGroupOrderByApprovalOrderAsc(group);
        List<GroupMember> members = memberRepository.findByGroup(group);

        GroupResponse.PayerInfo payerInfo = null;
        if (group.getPayerName() != null) {
            payerInfo = GroupResponse.PayerInfo.builder()
                    .name(group.getPayerName())
                    .affiliation(group.getPayerAffiliation())
                    .studentId(group.getPayerStudentId())
                    .phone(group.getPayerPhone())
                    .build();
        }

        return GroupResponse.builder()
                .groupId(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .inviteCode(group.getInviteCode())
                .ownerId(group.getOwner().getId())
                .ownerName(group.getOwner().getName())
                .payerInfo(payerInfo)
                .roles(roles.stream()
                        .map(r -> GroupResponse.RoleSummary.builder()
                                .roleId(r.getId())
                                .roleName(r.getRoleName())
                                .approvalOrder(r.getApprovalOrder())
                                .build())
                        .toList())
                .members(members.stream()
                        .map(m -> GroupResponse.MemberSummary.builder()
                                .userId(m.getUser().getId())
                                .name(m.getUser().getName())
                                .email(m.getUser().getEmail())
                                .roleName(m.getRole().getRoleName())
                                .approvalOrder(m.getRole().getApprovalOrder())
                                .build())
                        .toList())
                .build();
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> getMyGroups(Long userId) {
        User user = findUser(userId);
        return memberRepository.findByUser(user).stream()
                .map(m -> getGroupDetail(m.getGroup().getId()))
                .toList();
    }

    public GroupResponse addRole(Long ownerId, Long groupId, RoleRequest request) {
        UserGroup group = findGroup(groupId);
        validateOwner(ownerId, group);

        if (roleRepository.findByGroupAndApprovalOrder(group, request.getApprovalOrder()).isPresent()) {
            throw new IllegalArgumentException("이미 존재하는 결재 순서입니다: " + request.getApprovalOrder());
        }

        roleRepository.save(GroupRole.builder()
                .group(group)
                .roleName(request.getRoleName())
                .approvalOrder(request.getApprovalOrder())
                .build());

        return getGroupDetail(groupId);
    }

    public GroupResponse updateRole(Long ownerId, Long groupId, Long roleId, RoleRequest request) {
        UserGroup group = findGroup(groupId);
        validateOwner(ownerId, group);

        GroupRole role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("역할을 찾을 수 없습니다."));
        if (!role.getGroup().getId().equals(groupId)) {
            throw new IllegalArgumentException("해당 그룹의 역할이 아닙니다.");
        }

        role.updateRoleName(request.getRoleName());
        return getGroupDetail(groupId);
    }

    public void deleteRole(Long ownerId, Long groupId, Long roleId) {
        UserGroup group = findGroup(groupId);
        validateOwner(ownerId, group);

        GroupRole role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("역할을 찾을 수 없습니다."));
        if (!role.getGroup().getId().equals(groupId)) {
            throw new IllegalArgumentException("해당 그룹의 역할이 아닙니다.");
        }
        if (memberRepository.existsByRole(role)) {
            throw new IllegalArgumentException("해당 역할을 가진 멤버가 있어 삭제할 수 없습니다.");
        }

        roleRepository.delete(role);
        log.info("역할 삭제 완료 - groupId={}, roleId={}", groupId, roleId);
    }

    public void assertMaxRole(User user, UserGroup group) {
        GroupMember member = memberRepository.findByGroupAndUser(group, user)
                .orElseThrow(() -> new IllegalArgumentException("해당 그룹의 멤버가 아닙니다."));
        int maxOrder = roleRepository.findByGroupOrderByApprovalOrderAsc(group).stream()
                .mapToInt(GroupRole::getApprovalOrder).max().orElse(0);
        if (member.getRole().getApprovalOrder() < maxOrder) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "그룹 최고 권한자만 가능합니다.");
        }
    }

    @Transactional(readOnly = true)
    public boolean isTopApprover(Long userId, Long groupId) {
        User user = findUser(userId);
        UserGroup group = findGroup(groupId);
        GroupMember member = memberRepository.findByGroupAndUser(group, user).orElse(null);
        if (member == null) return false;
        int maxOrder = roleRepository.findByGroupOrderByApprovalOrderAsc(group).stream()
                .mapToInt(GroupRole::getApprovalOrder).max().orElse(-1);
        return member.getRole().getApprovalOrder() == maxOrder;
    }

    private String generateInviteCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
    }

    private UserGroup findGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("그룹을 찾을 수 없습니다."));
    }

    private void validateOwner(Long userId, UserGroup group) {
        if (!group.getOwner().getId().equals(userId)) {
            throw new IllegalArgumentException("그룹 대표자만 권한을 변경할 수 있습니다.");
        }
    }
}
