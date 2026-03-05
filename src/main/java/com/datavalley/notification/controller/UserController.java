package com.datavalley.notification.controller;

import com.datavalley.notification.model.User;
import com.datavalley.notification.repository.UserRepository;
import com.datavalley.notification.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthService authService;

    @PostMapping
    public ResponseEntity<UserPayload> createUser(@RequestBody CreateUserRequest request) {
        User user = authService.register(request.username(), request.email(), request.password());
        if (Boolean.TRUE.equals(request.approved())) {
            user.setApproved(true);
            user = userRepository.save(user);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toPayload(user));
    }

    @GetMapping
    public ResponseEntity<List<UserPayload>> getAllUsers() {
        return ResponseEntity.ok(userRepository.findAll().stream()
                .map(this::toPayload)
                .collect(Collectors.toList()));
    }

    @GetMapping("/pending")
    public ResponseEntity<List<UserPayload>> getPendingUsers() {
        return ResponseEntity.ok(userRepository.findByApproved(false).stream()
                .map(this::toPayload)
                .collect(Collectors.toList()));
    }

    @PutMapping("/{id}/approve")
    public ResponseEntity<UserPayload> approveUser(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found."));
        user.setApproved(true);
        return ResponseEntity.ok(toPayload(userRepository.save(user)));
    }

    private UserPayload toPayload(User user) {
        return new UserPayload(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.isApproved(),
                user.isNotificationsEnabled()
        );
    }

    public record CreateUserRequest(String username, String email, String password, Boolean approved) {
    }

    public record UserPayload(Long id, String username, String email, boolean approved, boolean notificationsEnabled) {
    }
}
