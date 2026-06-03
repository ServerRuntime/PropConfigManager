package com.flexcity.configmanager.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Value("${app.ui.password:1234}")
    private String uiPassword;

    @Value("${app.ui.admin-password:admin1234}")
    private String adminPassword;

    /** Oturum açık mı? Admin mi? */
    @GetMapping("/check")
    public Map<String, Object> check(HttpSession session) {
        boolean ok      = Boolean.TRUE.equals(session.getAttribute("authenticated"));
        boolean isAdmin = Boolean.TRUE.equals(session.getAttribute("isAdmin"));
        return Map.of("authenticated", ok, "isAdmin", isAdmin);
    }

    /** Giriş — normal şifre veya admin şifresi */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        String pw = body.getOrDefault("password", "");

        if (adminPassword.equals(pw)) {
            session.setAttribute("authenticated", true);
            session.setAttribute("isAdmin", true);
            return ResponseEntity.ok(Map.of("success", true, "isAdmin", true));
        }

        if (uiPassword.equals(pw)) {
            session.setAttribute("authenticated", true);
            session.setAttribute("isAdmin", false);
            return ResponseEntity.ok(Map.of("success", true, "isAdmin", false));
        }

        return ResponseEntity.status(401)
                .body(Map.of("success", false, "error", "Şifre hatalı"));
    }

    /** Çıkış */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        return Map.of("success", true);
    }
}
