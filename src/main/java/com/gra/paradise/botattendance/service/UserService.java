package com.gra.paradise.botattendance.service;

import com.gra.paradise.botattendance.model.User;
import com.gra.paradise.botattendance.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public User getOrCreateUser(String discordId, String username, String nickname) {
        Optional<User> existingUser = userRepository.findById(discordId);

        return existingUser.orElseGet(() -> {
            User newUser = new User(discordId, username, nickname);
            return userRepository.save(newUser);
        });
    }
}