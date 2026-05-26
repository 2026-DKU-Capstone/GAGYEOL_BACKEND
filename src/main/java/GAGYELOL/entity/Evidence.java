package GAGYELOL.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "evidence")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Evidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private UserGroup group;

    @Column(name = "policy_id")
    private Long policyId;

    @Column(name = "selected_form_ids", columnDefinition = "TEXT")
    private String selectedFormIds; // JSON 배열: [1, 2, 3]

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "extracted_text", columnDefinition = "TEXT")
    private String extractedText;

    @Column(name = "select_reason", columnDefinition = "TEXT")
    private String selectReason;

    @Column(name = "business_name")
    private String businessName;

    @Column(name = "item_name")
    private String itemName;

    @Column(name = "recipient_image_path")
    private String recipientImagePath;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
