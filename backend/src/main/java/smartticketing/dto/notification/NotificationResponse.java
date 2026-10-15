package smartticketing.dto.notification;

import smartticketing.entity.enums.NotificationType;

import java.time.LocalDateTime;

public record NotificationResponse(Long id, NotificationType type, String message, boolean read,
                                   LocalDateTime createdAt, Long groupId, Long reservationId) {
}
