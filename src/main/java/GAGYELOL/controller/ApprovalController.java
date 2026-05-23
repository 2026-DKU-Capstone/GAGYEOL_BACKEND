package GAGYELOL.controller;

import GAGYELOL.config.JwtUtil;
import GAGYELOL.dto.approval.ApprovalHistoryResponse;
import GAGYELOL.dto.approval.ApprovalResponse;
import GAGYELOL.dto.approval.ApproveRequest;
import GAGYELOL.dto.approval.CreateApprovalRequest;
import GAGYELOL.dto.approval.EditFieldsRequest;
import GAGYELOL.dto.approval.ResubmitRequest;
import GAGYELOL.service.ApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;
    private final JwtUtil jwtUtil;

    // 결재 요청 생성
    @PostMapping
    public ResponseEntity<ApprovalResponse> createRequest(
            @RequestHeader("Authorization") String token,
            @RequestBody CreateApprovalRequest request) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(approvalService.createRequest(userId, request));
    }

    // 결재 단건 조회
    @GetMapping("/{requestId}")
    public ResponseEntity<ApprovalResponse> getRequest(@PathVariable Long requestId) {
        return ResponseEntity.ok(approvalService.getRequest(requestId));
    }

    // 결재 이력 조회 (steps + 수정 이력)
    @GetMapping("/{requestId}/history")
    public ResponseEntity<ApprovalHistoryResponse> getHistory(@PathVariable Long requestId) {
        return ResponseEntity.ok(approvalService.getHistory(requestId));
    }

    // 내 결재 목록 (내가 요청자인 건)
    @GetMapping("/my")
    public ResponseEntity<List<ApprovalResponse>> getMyRequests(
            @RequestHeader("Authorization") String token) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(approvalService.getMyRequests(userId));
    }

    // 그룹 결재 목록 조회
    @GetMapping("/group/{groupId}")
    public ResponseEntity<List<ApprovalResponse>> getGroupRequests(@PathVariable Long groupId) {
        return ResponseEntity.ok(approvalService.getGroupRequests(groupId));
    }

    // 결재 재요청 (REJECTED → 수정 후 새 결재 생성)
    @PostMapping("/{requestId}/resubmit")
    public ResponseEntity<ApprovalResponse> resubmit(
            @RequestHeader("Authorization") String token,
            @PathVariable Long requestId,
            @RequestBody ResubmitRequest request) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(approvalService.resubmit(userId, requestId, request));
    }

    // 승인
    @PostMapping("/{requestId}/approve")
    public ResponseEntity<ApprovalResponse> approve(
            @RequestHeader("Authorization") String token,
            @PathVariable Long requestId,
            @RequestBody ApproveRequest request) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(approvalService.approve(userId, requestId, request));
    }

    // 반려
    @PostMapping("/{requestId}/reject")
    public ResponseEntity<ApprovalResponse> reject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long requestId,
            @RequestBody ApproveRequest request) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(approvalService.reject(userId, requestId, request));
    }

    // 양식지 수정
    @PutMapping("/{requestId}/fields")
    public ResponseEntity<ApprovalResponse> editFields(
            @RequestHeader("Authorization") String token,
            @PathVariable Long requestId,
            @RequestBody EditFieldsRequest request) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(approvalService.editFields(userId, requestId, request));
    }

    // 받은 결재 목록 (내가 현재 처리해야 할 PENDING 결재)
    @GetMapping("/pending")
    public ResponseEntity<List<ApprovalResponse>> getPendingForMe(
            @RequestHeader("Authorization") String token) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(approvalService.getPendingForMe(userId));
    }

    // 최종 승인 취소
    @PostMapping("/{requestId}/cancel")
    public ResponseEntity<ApprovalResponse> cancel(
            @RequestHeader("Authorization") String token,
            @PathVariable Long requestId) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(approvalService.cancel(userId, requestId));
    }

    // 결재요청 삭제 (최고 권한자만)
    @DeleteMapping("/{requestId}")
    public ResponseEntity<Void> deleteRequest(
            @RequestHeader("Authorization") String token,
            @PathVariable Long requestId) {
        Long userId = extractUserId(token);
        approvalService.deleteRequest(userId, requestId);
        return ResponseEntity.noContent().build();
    }

    private Long extractUserId(String token) {
        return jwtUtil.extractUserId(token.replace("Bearer ", ""));
    }
}
