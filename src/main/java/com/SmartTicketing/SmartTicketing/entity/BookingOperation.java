package com.SmartTicketing.SmartTicketing.entity;

import com.SmartTicketing.SmartTicketing.entity.enums.BookingOperationStatus;
import com.SmartTicketing.SmartTicketing.entity.enums.BookingOperationType;
import jakarta.persistence.*;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 멱등 요청 기록. 인증 사용자+작업+키를 식별하고 본문 해시가 다르면 재사용을 거절한다. */
@Entity
@Table(name = "booking_operations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_booking_operation_user_type_key",
                columnNames = {"user_id", "operation_type", "request_key"})
})
@Getter
@Setter
@NoArgsConstructor
public class BookingOperation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private Users user;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 30, updatable = false, columnDefinition = "varchar(30)")
    private BookingOperationType operationType;

    @Pattern(regexp = "[a-z0-9-]{16,64}")
    @Column(name = "request_key", nullable = false, length = 64, updatable = false)
    private String requestKey;

    @Pattern(regexp = "[a-f0-9]{64}")
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private BookingOperationStatus status = BookingOperationStatus.PROCESSING;

    @Column(name = "response_status")
    private Integer responseStatus;

    // API 응답 DTO만 저장하며 인증 토큰/카드정보는 저장하지 않는다.
    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
