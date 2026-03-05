package com.datavalley.notification.service;

import com.datavalley.notification.model.AuthSession;
import com.datavalley.notification.model.User;
import com.datavalley.notification.repository.AuthSessionRepository;
import com.datavalley.notification.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public User register(String username, String email, String password) {
        String normalizedUsername = normalizeRequiredValue(username, "Username is required.");
        String normalizedEmail = normalizeEmail(email);
        String normalizedPassword = normalizeRequiredValue(password, "Password is required.");

        if (normalizedPassword.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters.");
        }
        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already in use.");
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already in use.");
        }

        User user = new User();
        user.setUsername(normalizedUsername);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(normalizedPassword));
        user.setApproved(false);
        user.setNotificationsEnabled(true);

        return userRepository.save(user);
    }

    @Transactional
    public SignInResult signIn(String identifier, String password) {
        String normalizedIdentifier = normalizeRequiredValue(identifier, "Username or email is required.");
        String normalizedPassword = normalizeRequiredValue(password, "Password is required.");
        String normalizedEmailCandidate = normalizedIdentifier.toLowerCase(Locale.ROOT);

        User user = userRepository.findByUsernameOrEmail(normalizedIdentifier, normalizedEmailCandidate)
                .orElseThrow(this::unauthorizedException);

        if (!passwordEncoder.matches(normalizedPassword, user.getPasswordHash())) {
            throw unauthorizedException();
        }
        if (!user.isApproved()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registration pending admin approval.");
        }

        authSessionRepository.deleteByUser_Id(user.getId());

        String token = UUID.randomUUID().toString().replace("-", "");
        AuthSession session = new AuthSession();
        session.setToken(token);
        session.setUser(user);
        session.setCreatedAt(LocalDateTime.now());
        authSessionRepository.save(session);

        return new SignInResult(token, user);
    }

    @Transactional
    public void signOut(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        if (token != null) {
            authSessionRepository.deleteByToken(token);
        }
    }

    public User requireUserFromAuthorizationHeader(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        if (token == null) {
            throw unauthorizedException();
        }
        return authSessionRepository.findByToken(token)
                .map(AuthSession::getUser)
                .orElseThrow(this::unauthorizedException);
    }

    private String normalizeRequiredValue(String value, String errorMessage) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }
        return value.trim();
    }

    private String normalizeEmail(String email) {
        return normalizeRequiredValue(email, "Email is required.").toLowerCase(Locale.ROOT);
    }

    private ResponseStatusException unauthorizedException() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username/email or password.");
    }

    private String extractBearerToken(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) {
            return null;
        }

        String prefix = "Bearer ";
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }

        String token = authorizationHeader.substring(prefix.length()).trim();
        return StringUtils.hasText(token) ? token : null;
    }

    public record SignInResult(String token, User user) {
    }
}
