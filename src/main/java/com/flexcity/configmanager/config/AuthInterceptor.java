//package com.flexcity.configmanager.config;
//
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import jakarta.servlet.http.HttpSession;
//import org.springframework.stereotype.Component;
//import org.springframework.web.servlet.HandlerInterceptor;
//
///**
// * /api/** isteklerinde oturum kontrolü yapar.
// * Oturum açık değilse 401 döner.
// */
//@Component
//public class AuthInterceptor implements HandlerInterceptor {
//
//    @Override
//    public boolean preHandle(HttpServletRequest request,
//                             HttpServletResponse response,
//                             Object handler) throws Exception {
//
//        // SSE (EventSource) bağlantıları da korunuyor
//        HttpSession session = request.getSession(false);
//        if (session != null && Boolean.TRUE.equals(session.getAttribute("authenticated"))) {
//            return true;
//        }
//
//        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//        response.setContentType("application/json;charset=UTF-8");
//        response.getWriter().write("{\"success\":false,\"error\":\"Oturum açılmamış\"}");
//        return false;
//    }
//}
