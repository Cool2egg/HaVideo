package com.videorag.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.videorag.auth.dto.AuthRequest;
import com.videorag.auth.dto.AuthResponse;
import com.videorag.auth.dto.UserView;
import com.videorag.auth.entity.User;
import com.videorag.auth.mapper.UserMapper;
import com.videorag.common.BusinessException;
import com.videorag.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    public static final String REQUEST_USER_ID = "authenticatedUserId";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String PASSWORD_PREFIX = "pbkdf2";
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final int PASSWORD_KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int TOKEN_BYTES = 32;
    private static final long SESSION_HOURS = 24;
    private static final String SESSION_PREFIX = "auth:session:";
    private static final String LOGIN_FAILURE_PREFIX = "auth:login-failures:";
    private static final int MAX_LOGIN_FAILURES = 8;
    private static final long LOGIN_FAILURE_WINDOW_MINUTES = 10;

    private final StringRedisTemplate redisTemplate;
    private final UserMapper userMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(StringRedisTemplate redisTemplate, UserMapper userMapper) {
        this.redisTemplate = redisTemplate;
        this.userMapper = userMapper;
    }

    public AuthResponse register(AuthRequest request) {
        String username = request.username().trim();
        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", username);
        if (userMapper.selectCount(query) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "该账号已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(hashPassword(request.password()));
        user.setNickname(normalizeNickname(request.nickname(), username));
        user.setRole("USER");
        user.setStatus("ACTIVE");
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException error) {
            throw new BusinessException(ErrorCode.CONFLICT, "该账号已存在");
        }
        log.info("user_registered userId={} username={}", user.getId(), username);
        return new AuthResponse(user.getId(), user.getUsername(), user.getNickname(),
                user.getRole(), createSession(user.getId()));
    }

    public AuthResponse login(AuthRequest request) {
        String username = request.username().trim();
        if (!loginAttemptAllowed(username)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "登录尝试过于频繁，请稍后再试");
        }

        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", username);
        User user = userMapper.selectOne(query);
        if (user == null || !passwordMatches(request.password(), user.getPassword())) {
            recordLoginFailure(username);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }
        if (!isHashed(user.getPassword())) {
            user.setPassword(hashPassword(request.password()));
            userMapper.updateById(user);
        }
        clearLoginFailures(username);
        log.info("user_logged_in userId={}", user.getId());
        return new AuthResponse(user.getId(), user.getUsername(), user.getNickname(),
                user.getRole(), createSession(user.getId()));
    }

    public void logout(String authorization) {
        String token = bearerToken(authorization);
        redisTemplate.delete(sessionKey(token));
    }

    public Long resolveUser(String authorization) {
        String token = bearerToken(authorization);
        String userId = redisTemplate.opsForValue().get(sessionKey(token));
        if (userId == null) {
            throw new SecurityException("登录状态已失效");
        }
        return Long.valueOf(userId);
    }

    public User requireUser(Long userId) {
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在");
        }
        return user;
    }

    public void requireAdmin(Long userId) {
        User user = requireUser(userId);
        if (!"ADMIN".equals(user.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可操作");
        }
    }

    public UserView view(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getNickname(),
                user.getAvatar(), user.getRole());
    }

    public UserView view(Long userId) {
        return view(requireUser(userId));
    }

    private String normalizeNickname(String nickname, String username) {
        if (nickname == null || nickname.isBlank()) {
            return "用户" + username;
        }
        return nickname.trim();
    }

    private String createSession(Long userId) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        redisTemplate.opsForValue().set(sessionKey(token), String.valueOf(userId),
                SESSION_HOURS, TimeUnit.HOURS);
        return token;
    }

    private boolean loginAttemptAllowed(String username) {
        String value = redisTemplate.opsForValue().get(LOGIN_FAILURE_PREFIX + username);
        if (value == null) return true;
        try {
            return Long.parseLong(value) < MAX_LOGIN_FAILURES;
        } catch (NumberFormatException error) {
            redisTemplate.delete(LOGIN_FAILURE_PREFIX + username);
            return true;
        }
    }

    private void recordLoginFailure(String username) {
        String key = LOGIN_FAILURE_PREFIX + username;
        Long failures = redisTemplate.opsForValue().increment(key);
        if (failures != null && failures == 1) {
            redisTemplate.expire(key, LOGIN_FAILURE_WINDOW_MINUTES, TimeUnit.MINUTES);
        }
    }

    private void clearLoginFailures(String username) {
        redisTemplate.delete(LOGIN_FAILURE_PREFIX + username);
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new SecurityException("请先登录");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.length() < 32) {
            throw new SecurityException("无效的登录凭证");
        }
        return token;
    }

    private String sessionKey(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return SESSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception error) {
            throw new IllegalStateException("无法生成会话摘要", error);
        }
    }

    public String hashPassword(String password) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = derive(password.toCharArray(), salt, PASSWORD_ITERATIONS);
        return String.join("$",
                PASSWORD_PREFIX,
                String.valueOf(PASSWORD_ITERATIONS),
                Base64.getEncoder().withoutPadding().encodeToString(salt),
                Base64.getEncoder().withoutPadding().encodeToString(hash));
    }

    private boolean passwordMatches(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null) return false;
        if (!isHashed(storedPassword)) {
            return MessageDigest.isEqual(
                    rawPassword.getBytes(StandardCharsets.UTF_8),
                    storedPassword.getBytes(StandardCharsets.UTF_8));
        }
        try {
            String[] parts = storedPassword.split("\\$", -1);
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(rawPassword.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean isHashed(String password) {
        return password != null && password.startsWith(PASSWORD_PREFIX + "$");
    }

    private byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, PASSWORD_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (Exception error) {
            throw new IllegalStateException("密码哈希失败", error);
        } finally {
            spec.clearPassword();
        }
    }
}
