package com.flexcity.configmanager.config;

import com.flexcity.configmanager.controller.TerminalWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalHandler;

    public WebSocketConfig(TerminalWebSocketHandler terminalHandler) {
        this.terminalHandler = terminalHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalHandler, "/ws/terminal")
                .setAllowedOrigins("*")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res,
                                                   WebSocketHandler handler, Map<String, Object> attrs) {
                        if (req instanceof ServletServerHttpRequest servletReq) {
                            HttpSession session = servletReq.getServletRequest().getSession(false);
                            if (session == null) return false;
                            boolean authenticated = Boolean.TRUE.equals(session.getAttribute("authenticated"));
                            boolean isAdmin       = Boolean.TRUE.equals(session.getAttribute("isAdmin"));
                            return authenticated && isAdmin;  // sadece admin bağlanabilir
                        }
                        return false;
                    }
                    @Override
                    public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res,
                                               WebSocketHandler handler, Exception ex) {}
                });
    }
}
