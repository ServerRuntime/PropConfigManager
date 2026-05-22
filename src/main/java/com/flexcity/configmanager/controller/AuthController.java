//package com.flexcity.configmanager.controller;
//
//import jakarta.servlet.http.HttpSession;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.Map;
//
//@RestController
//@RequestMapping("/auth")
//public class AuthController {
//
//    @Value("${app.ui.password:1234}")
//    private String uiPassword;
//
//    /** Oturum açık mı? */
//    @GetMapping("/check")
//    public Map<String, Object> check(HttpSession session) {
//        boolean ok = Boolean.TRUE.equals(session.getAttribute("authenticated"));
//        return Map.of("authenticated", ok);
//    }
//
//    /** Giriş — şifre doğruysa session'a authenticated=true yaz */
//    @PostMapping("/login")
//    public ResponseEntity<Map<String, Object>> login(
//            @RequestBody Map<String, String> body,
//            HttpSession session) {
//
//        String pw = body.getOrDefault("password", "");
//        if (uiPassword.equals(pw)) {
//            session.setAttribute("authenticated", true);
//            return ResponseEntity.ok(Map.of("success", true));
//        }
//        return ResponseEntity.status(401)
//                .body(Map.of("success", false, "error", "Şifre hatalı"));
//    }
//
//    /** Çıkış */
//    @PostMapping("/logout")
//    public Map<String, Object> logout(HttpSession session) {
//        session.invalidate();
//        return Map.of("success", true);
//    }
//}
