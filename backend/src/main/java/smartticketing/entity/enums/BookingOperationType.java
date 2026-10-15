package smartticketing.entity.enums;

public enum BookingOperationType {
    CREATE_GROUP,
    CREATE_SMART_PLAN, // Historical idempotency records only.
    CREATE_SMART_CANDIDATES,
    MANUAL_HOLD,
    SMART_HOLD,
    WAITING_HOLD,
    REGISTER_WAITING,
    CONFIRM_PAYMENT,
    CANCEL_RESERVATION,
    CANCEL_GROUP
}
