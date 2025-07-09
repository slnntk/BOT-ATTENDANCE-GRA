package com.gra.paradise.botattendance.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "schedule_logs")
public class ScheduleLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "schedule_id")
    @Getter
    private Schedule schedule;

    private LocalDateTime timestamp;
    private String action; // CREATED, JOINED, LEFT, CLOSED
    private String userId;
    private String username;
    private String details;

    // Construtor necessário para JPA
    protected ScheduleLog() {}

    public ScheduleLog(Schedule schedule, String action, String userId, String username, String details) {
        this.schedule = schedule;
        this.timestamp = LocalDateTime.now();
        this.action = action;
        this.userId = userId;
        this.username = username;
        this.details = details;
    }
}