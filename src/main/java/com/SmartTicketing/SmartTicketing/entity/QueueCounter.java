package com.SmartTicketing.SmartTicketing.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "queue_counters")
@Data
@NoArgsConstructor
public class QueueCounter {

    @Id
    @Column(name = "showtime_id")
    private Long showtimeId;

    @Column(name = "next_queue_number", nullable = false)
    private Integer nextQueueNumber = 1;
}