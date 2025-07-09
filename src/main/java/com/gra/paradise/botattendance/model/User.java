package com.gra.paradise.botattendance.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "aviation_users")
public class User {
    @Id
    private String discordId;

    private String username;
    private String nickname;

    @ManyToMany(mappedBy = "crewMembers")
    private List<Schedule> schedules = new ArrayList<>();

    // Construtor necessário para JPA
    protected User() {}

    public User(String discordId, String username, String nickname) {
        this.discordId = discordId;
        this.username = username;
        this.nickname = nickname;
    }
}
