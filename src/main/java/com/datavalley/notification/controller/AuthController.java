package com.datavalley.notification.controller;

import com.datavalley.notification.model.User;
import com.datavalley.notification.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@RequestBody RegisterRequest request) {
        authService.register(request.username(), request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "Registration submitted. Wait for admin approval."));
    }

    @PostMapping("/signin")
    public ResponseEntity<SignInResponse> signIn(@RequestBody SignInRequest request) {
        AuthService.SignInResult result = authService.signIn(request.identifier(), request.password());
        User user = result.user();
        return ResponseEntity.ok(new SignInResponse(
                result.token(),
                new UserPayload(user.getId(), user.getUsername(), user.getEmail(), user.isApproved())
        ));
    }

    @PostMapping("/signout")
    public ResponseEntity<Map<String, String>> signOut(
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        authService.signOut(authorizationHeader);
        return ResponseEntity.ok(Map.of("message", "Signed out."));
    }

    @GetMapping("/me")
    public ResponseEntity<UserPayload> me(
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        User user = authService.requireUserFromAuthorizationHeader(authorizationHeader);
        return ResponseEntity.ok(new UserPayload(user.getId(), user.getUsername(), user.getEmail(), user.isApproved()));
    }

    public record RegisterRequest(String username, String email, String password) {
    }

    public record SignInRequest(String identifier, String password) {
    }

    public record UserPayload(Long id, String username, String email, boolean approved) {
    }

    public record SignInResponse(String token, UserPayload user) {
    }
}
