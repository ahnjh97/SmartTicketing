package smartticketing.repository;

import smartticketing.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdAndReadFalseOrderByCreatedAtDesc(Long userId);
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
    @org.springframework.data.jpa.repository.Query("select n from Notification n where n.user.id=:user and n.type in :types and (:unread=false or n.read=false) order by n.createdAt desc,n.id desc")
    List<Notification> findVisible(@org.springframework.data.repository.query.Param("user") Long user,
            @org.springframework.data.repository.query.Param("types") java.util.Set<smartticketing.entity.enums.NotificationType> types,
            @org.springframework.data.repository.query.Param("unread") boolean unread);
    @org.springframework.data.jpa.repository.Query("select n from Notification n where n.user.id=:user and n.type in :types and (:unread=false or n.read=false) "
            + "and (:time is null or n.createdAt<:time or (n.createdAt=:time and n.id<:id)) order by n.createdAt desc,n.id desc")
    List<Notification> findVisiblePage(@org.springframework.data.repository.query.Param("user") Long user,
            @org.springframework.data.repository.query.Param("types") java.util.Set<smartticketing.entity.enums.NotificationType> types,
            @org.springframework.data.repository.query.Param("unread") boolean unread,
            @org.springframework.data.repository.query.Param("time") java.time.LocalDateTime time,
            @org.springframework.data.repository.query.Param("id") Long id, org.springframework.data.domain.Pageable page);
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update Notification n set n.read=true where n.user.id=:user and n.type in :types and n.read=false")
    int markVisibleRead(@org.springframework.data.repository.query.Param("user") Long user,
            @org.springframework.data.repository.query.Param("types") java.util.Set<smartticketing.entity.enums.NotificationType> types);
}
