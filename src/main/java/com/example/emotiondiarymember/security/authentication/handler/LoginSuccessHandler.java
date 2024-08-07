package com.example.emotiondiarymember.security.authentication.handler;

import com.example.emotiondiarymember.redis.RedisService;
import com.example.emotiondiarymember.security.authentication.LoginResponse;
import com.example.emotiondiarymember.security.jwt.Jwt;
import com.example.emotiondiarymember.security.jwt.JwtProvider;
import com.example.emotiondiarymember.security.jwt.Payload;
import com.example.emotiondiarymember.security.util.TokenUtil;
import com.example.emotiondiarymember.util.CookieUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/*
 * 1. 사이트 로그인 성공 Handler
 * 2. OAuth2 로그인 성공 Handler
 * 3. refresh-token 발급 성공 handler
 *
 * - Access-Token 과 Refresh-Token 은 해당 Handler 를 통해서만 발급된다.
 * - 로그인 성공에 대한 응답은 해당 핸들러를 통해서만 이루어진다.
 * - 이를 통해 모듈 간 결합도는 낮추고 응집도는 높인다.
 */
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

  private final RedisService redisService;
  private final JwtProvider jwtProvider;

  public LoginSuccessHandler(RedisService redisService, JwtProvider jwtProvider) {
    this.redisService = redisService;
    this.jwtProvider = jwtProvider;
  }

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    Payload payload = TokenUtil.getPayload();
    String accessToken = jwtProvider.createToken(payload);
    String refreshToken = jwtProvider.refreshToken(payload.getRedisKey());
    Jwt jwt = new Jwt(accessToken, refreshToken);

    redisService.accessTokenSave(payload.getRedisKey(), jwt.getAccessToken());
    redisService.refreshTokenSave(payload.getRedisKey(), jwt.getRefreshToken());

    int refreshTokenMaxAge = (int) jwtProvider.getRefreshExpirationTime() / 1000;

    Cookie refreshTokenCookie = CookieUtil.createCookie(
        jwtProvider.getRefreshTokenHeader(),
        URLEncoder.encode(refreshToken, StandardCharsets.UTF_8),
        refreshTokenMaxAge,
        true,
        false,
        "/"
    );

    CookieUtil.addCookie(response, refreshTokenCookie);

    if (TokenUtil.isInitSocialLogin()) {
      int accessTokenMaxAge = (int) jwtProvider.getExpirationTime() / 1000;

      Cookie accessTokenCookie = CookieUtil.createCookie(
          jwtProvider.getAccessTokenHeader(),
          URLEncoder.encode(jwtProvider.getTokenPrefix() + accessToken, StandardCharsets.UTF_8),
          accessTokenMaxAge,
          false,
          false,
          "/"
      );

      Cookie idCookie = CookieUtil.createCookie(
          "id",
          String.valueOf(payload.getId()),
          accessTokenMaxAge,
          false,
          false,
          "/"
      );

      CookieUtil.addCookie(response, accessTokenCookie, idCookie);
      response.sendRedirect("http://localhost:8081/oauth2-signin-success");
      return;
    }

    response.setStatus(HttpStatus.OK.value());
    response.setContentType(MediaType.APPLICATION_JSON_UTF8_VALUE);

    response.setHeader(jwtProvider.getAccessTokenHeader(), jwtProvider.getTokenPrefix() + jwt.getAccessToken());
    response.setHeader(jwtProvider.getRefreshTokenHeader(), jwtProvider.getTokenPrefix() + jwt.getRefreshToken());
    LoginResponse loginResponse = new LoginResponse(jwt, payload.getId());
    PrintWriter writer = response.getWriter();
    writer.println(new ObjectMapper().writeValueAsString(loginResponse));
    writer.flush();
    writer.close();
  }
}
