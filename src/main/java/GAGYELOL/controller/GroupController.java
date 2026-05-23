package GAGYELOL.controller;

import GAGYELOL.config.JwtUtil;
import GAGYELOL.dto.group.AssignRoleRequest;
import GAGYELOL.dto.group.CreateGroupRequest;
import GAGYELOL.dto.group.GroupResponse;
import GAGYELOL.dto.group.PayerInfoRequest;
import GAGYELOL.dto.group.RoleRequest;
import jakarta.validation.Valid;
import GAGYELOL.service.GroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;
    private final JwtUtil jwtUtil;

    // 그룹 생성
    @PostMapping
    public ResponseEntity<GroupResponse> createGroup(
            @RequestHeader("Authorization") String token,
            @RequestBody CreateGroupRequest request) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(groupService.createGroup(userId, request));
    }

    // 초대코드로 그룹 가입
    @PostMapping("/join/{inviteCode}")
    public ResponseEntity<GroupResponse> joinGroup(
            @RequestHeader("Authorization") String token,
            @PathVariable String inviteCode) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(groupService.joinGroup(userId, inviteCode));
    }

    // 그룹 상세 조회
    @GetMapping("/{groupId}")
    public ResponseEntity<GroupResponse> getGroup(@PathVariable Long groupId) {
        return ResponseEntity.ok(groupService.getGroupDetail(groupId));
    }

    // 내 그룹 목록
    @GetMapping("/my")
    public ResponseEntity<List<GroupResponse>> getMyGroups(
            @RequestHeader("Authorization") String token) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(groupService.getMyGroups(userId));
    }

    // 멤버 추방 (대표자만 가능)
    @DeleteMapping("/{groupId}/members/{targetUserId}")
    public ResponseEntity<Void> kickMember(
            @RequestHeader("Authorization") String token,
            @PathVariable Long groupId,
            @PathVariable Long targetUserId) {
        Long userId = extractUserId(token);
        groupService.kickMember(userId, groupId, targetUserId);
        return ResponseEntity.noContent().build();
    }

    // 역할 부여 (대표자만 가능)
    @PutMapping("/{groupId}/members/role")
    public ResponseEntity<GroupResponse> assignRole(
            @RequestHeader("Authorization") String token,
            @PathVariable Long groupId,
            @RequestBody AssignRoleRequest request) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(groupService.assignRole(userId, groupId, request));
    }

    // 역할 추가 (대표자만 가능)
    @PostMapping("/{groupId}/roles")
    public ResponseEntity<GroupResponse> addRole(
            @RequestHeader("Authorization") String token,
            @PathVariable Long groupId,
            @Valid @RequestBody RoleRequest request) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(groupService.addRole(userId, groupId, request));
    }

    // 역할 이름 수정 (대표자만 가능)
    @PutMapping("/{groupId}/roles/{roleId}")
    public ResponseEntity<GroupResponse> updateRole(
            @RequestHeader("Authorization") String token,
            @PathVariable Long groupId,
            @PathVariable Long roleId,
            @Valid @RequestBody RoleRequest request) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(groupService.updateRole(userId, groupId, roleId, request));
    }

    // 지출인 정보 등록/수정 (대표자만 가능)
    @PutMapping("/{groupId}/payer-info")
    public ResponseEntity<GroupResponse> updatePayerInfo(
            @RequestHeader("Authorization") String token,
            @PathVariable Long groupId,
            @RequestBody PayerInfoRequest request) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(groupService.updatePayerInfo(userId, groupId, request));
    }

    // 현재 사용자가 그룹 최고 권한자인지 확인
    @GetMapping("/{groupId}/is-top-approver")
    public ResponseEntity<Map<String, Boolean>> isTopApprover(
            @RequestHeader("Authorization") String token,
            @PathVariable Long groupId) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(Map.of("isTopApprover", groupService.isTopApprover(userId, groupId)));
    }

    // 역할 삭제 (대표자만, 해당 역할 멤버 없을 때만 가능)
    @DeleteMapping("/{groupId}/roles/{roleId}")
    public ResponseEntity<Void> deleteRole(
            @RequestHeader("Authorization") String token,
            @PathVariable Long groupId,
            @PathVariable Long roleId) {
        Long userId = extractUserId(token);
        groupService.deleteRole(userId, groupId, roleId);
        return ResponseEntity.noContent().build();
    }

    private Long extractUserId(String token) {
        return jwtUtil.extractUserId(token.replace("Bearer ", ""));
    }
}
