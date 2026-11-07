package com.videorag.chat;

import com.videorag.auth.AuthService;
import com.videorag.chat.dto.ChatRequest;
import com.videorag.chat.dto.CreateSessionRequest;
import com.videorag.chat.dto.MessageView;
import com.videorag.chat.dto.SessionView;
import com.videorag.common.Result;
import com.videorag.common.ratelimit.RateLimitService;
import com.videorag.config.RateLimitProperties;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/rag")
public class RagController {

    private final RagChatService chatService;
    private final RateLimitService rateLimitService;
    private final RateLimitProperties rateLimitProperties;

    public RagController(RagChatService chatService,
                         RateLimitService rateLimitService,
                         RateLimitProperties rateLimitProperties) {
        this.chatService = chatService;
        this.rateLimitService = rateLimitService;
        this.rateLimitProperties = rateLimitProperties;
    }

    @PostMapping("/sessions")
    public Result<SessionView> createSession(
            @Valid @RequestBody CreateSessionRequest request,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(chatService.createSession(userId, request));
    }

    @GetMapping("/sessions")
    public Result<List<SessionView>> sessions(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(chatService.listSessions(userId));
    }

    @GetMapping("/sessions/{id}/messages")
    public Result<List<MessageView>> messages(
            @PathVariable Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(chatService.messages(userId, id));
    }

    @DeleteMapping("/sessions/{id}")
    public Result<Void> deleteSession(
            @PathVariable Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        chatService.deleteSession(userId, id);
        return Result.ok();
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @Valid @RequestBody ChatRequest request,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        rateLimitService.require(userId, "rag:chat", rateLimitProperties.getRagChat());
        return chatService.stream(userId, request);
    }
}
