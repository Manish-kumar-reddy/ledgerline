package com.ledgerline.controller;

import com.ledgerline.dto.LoginRequest;
import com.ledgerline.dto.RegisterRequest;
import com.ledgerline.dto.TokenResponse;
import com.ledgerline.dto.UserResponse;
import com.ledgerline.security.AuthenticatedUser;
import com.ledgerline.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Public. Always creates an ACCOUNT_HOLDER. 409 if the email is taken; 429 over the per-IP registration limit. */
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        UserResponse created = authService.register(request, http.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/auth/me")).body(created);
    }

    /**
     * Public. Any failure is the same generic 401; too many recent failures give 429 (see GlobalExceptionHandler).
     * The client IP is the connection's remote address. Behind a reverse proxy, configure
     * server.forward-headers-strategy so this is the real client and not the proxy.
     */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, http.getRemoteAddr());
    }

    /** Requires a bearer token. The user (and role) were just loaded from the database by the security layer. */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return UserResponse.from(user);
    }
}
