package com.gra.paradise.botattendance.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "schedules")
public class Schedule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    private AircraftType aircraftType;

    @Enumerated(EnumType.STRING)
    private MissionType missionType;

    @ManyToMany
    @JoinTable(
            name = "schedule_crew",
            joinColumns = @JoinColumn(name = "schedule_id"),
            inverseJoinColumns = @JoinColumn(name = "user_discord_id")
    )
    private List<User> crewMembers = new ArrayList<>();

    private String createdById;
    private String createdByUsername;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ScheduleLog> logs = new ArrayList<>();

    // Mensagem no Discord que contém esta escala (para atualização)
    private String messageId;
    private String channelId;

    // Métodos auxiliares
    public void addCrewMember(User user) {
        crewMembers.add(user);
        user.getSchedules().add(this);
    }

    public void removeCrewMember(User user) {
        crewMembers.remove(user);
        user.getSchedules().remove(this);
    }

    private transient int crewMembersCount = -1;
    private transient List<User> initializedCrewMembers = null;

    public void initializeCrewMembers() {
        if (crewMembers != null) {
            this.crewMembersCount = crewMembers.size();
            this.initializedCrewMembers = new ArrayList<>(crewMembers);
        } else {
            this.crewMembersCount = 0;
            this.initializedCrewMembers = new ArrayList<>();
        }
    }

    public int getCrewMembersCount() {
        return crewMembersCount >= 0 ? crewMembersCount : 0;
    }

    public List<User> getInitializedCrewMembers() {
        return initializedCrewMembers != null ? initializedCrewMembers : new ArrayList<>();
    }
}