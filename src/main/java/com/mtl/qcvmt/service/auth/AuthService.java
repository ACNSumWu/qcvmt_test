package com.mtl.qcvmt.service.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mtl.qcvmt.dto.auth.AuthHealthResponse;
import com.mtl.qcvmt.dto.auth.AuthLogoutUrlResponse;
import com.mtl.qcvmt.dto.auth.AuthMeResponse;
import com.mtl.qcvmt.dto.auth.AuthPermissionsResponse;
import com.mtl.qcvmt.dto.auth.AuthSyncResponse;
import com.mtl.qcvmt.dto.auth.LoginRequest;
import com.mtl.qcvmt.dto.auth.LoginResponse;
import com.mtl.qcvmt.entity.ShowLog;
import com.mtl.qcvmt.entity.User;
import com.mtl.qcvmt.repository.ShowLogRepository;
import com.mtl.qcvmt.service.keycloak.KeycloakUserSyncService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class AuthService {

  private static final String ROLE_ADMIN = "ROLE_qcvmt-admin";

  private final KeycloakUserSyncService keycloakUserSyncService;
  private final ShowLogRepository showLogRepository;
  private final String keycloakServerUrl;
  private final String keycloakRealm;
  private final String keycloakClientId;
  private final String keycloakClientSecret;
  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;

  public AuthService(
      KeycloakUserSyncService keycloakUserSyncService,
      ShowLogRepository showLogRepository,
      @Value("${qcvmt.keycloak.server-url}") String keycloakServerUrl,
      @Value("${qcvmt.keycloak.realm}") String keycloakRealm,
      @Value("${qcvmt.keycloak.client-id}") String keycloakClientId,
      @Value("${qcvmt.keycloak.client-secret}") String keycloakClientSecret) {
    this.keycloakUserSyncService = keycloakUserSyncService;
    this.showLogRepository = showLogRepository;
    this.keycloakServerUrl = keycloakServerUrl;
    this.keycloakRealm = keycloakRealm;
    this.keycloakClientId = keycloakClientId;
    this.keycloakClientSecret = keycloakClientSecret;
    this.restTemplate = new RestTemplate();
    this.objectMapper = new ObjectMapper();
  }

  @Transactional
  public LoginResponse login(LoginRequest request) {
    Map<String, Object> tokenResponse;
    try {
      tokenResponse = requestTokenFromKeycloak(request.username(), request.password());
    } catch (ResponseStatusException ex) {
      writeFailedLoginLog(request.username());
      throw ex;
    } catch (Exception ex) {
      writeFailedLoginLog(request.username());
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "keycloak_unavailable");
    }

    String accessToken = claimAsString(tokenResponse, "access_token");
    if (accessToken == null || accessToken.isBlank()) {
      writeFailedLoginLog(request.username());
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "keycloak_token_missing");
    }
    Map<String, Object> tokenClaims = decodeJwtClaims(accessToken);

    String keycloakId = claimAsString(tokenClaims, "sub");
    String preferredUsername = claimAsString(tokenClaims, "preferred_username");
    if (preferredUsername == null || preferredUsername.isBlank()) {
      preferredUsername = request.username();
    }

    List<String> roles = extractRealmRoles(tokenClaims);
    boolean admin = roles.contains("ROLE_qcvmt-admin");

    KeycloakUserSyncService.SyncResult syncResult = keycloakUserSyncService.syncByIdentity(
        keycloakId == null || keycloakId.isBlank() ? preferredUsername : keycloakId,
        preferredUsername,
        admin);

    Instant loginTime = Instant.now();
    writeLoginLog(syncResult.user(), request.qcid(), loginTime);

    AuthMeResponse me = new AuthMeResponse(
        syncResult.user().getId(),
        syncResult.user().getKeycloakId(),
        syncResult.user().getUsername(),
        request.qcid(),
        syncResult.user().getRole(),
        roles,
        admin,
        expiresAt(tokenResponse));

    return new LoginResponse(
        accessToken,
        claimAsString(tokenResponse, "refresh_token"),
        claimAsString(tokenResponse, "token_type"),
        longValue(tokenResponse.get("expires_in")),
        longValue(tokenResponse.get("refresh_expires_in")),
        claimAsString(tokenResponse, "scope"),
        me);
  }

  @Transactional
  public AuthMeResponse me() {
    KeycloakUserSyncService.SyncResult syncResult = keycloakUserSyncService.syncCurrentUser();
    return toMeResponse(syncResult.user(), currentJwt(), currentAuthorities());
  }

  @Transactional
  public AuthSyncResponse sync() {
    KeycloakUserSyncService.SyncResult syncResult = keycloakUserSyncService.syncCurrentUser();
    Instant loginTime = Instant.now();
    writeLoginLog(syncResult.user(), syncResult.user().getQcid(), loginTime);
    AuthMeResponse me = toMeResponse(syncResult.user(), currentJwt(), currentAuthorities());
    return new AuthSyncResponse(me, syncResult.created(), loginTime);
  }

  public AuthPermissionsResponse permissions() {
    List<String> roles = currentAuthorities();
    boolean isAdmin = roles.contains(ROLE_ADMIN);

    List<String> permissions = isAdmin
        ? List.of(
            "terminal:read",
            "users:read",
            "users:write",
            "operation-logs:read",
            "operation-logs:write",
            "import:write",
            "export:read")
        : List.of("terminal:read");

    return new AuthPermissionsResponse(roles, permissions, isAdmin);
  }

  public AuthLogoutUrlResponse logoutUrl(String postLogoutRedirectUri) {
    UriComponentsBuilder builder = UriComponentsBuilder
        .fromUriString(keycloakServerUrl)
        .pathSegment("realms", keycloakRealm, "protocol", "openid-connect", "logout")
        .queryParam("client_id", keycloakClientId);

    if (postLogoutRedirectUri != null && !postLogoutRedirectUri.isBlank()) {
      builder.queryParam("post_logout_redirect_uri", postLogoutRedirectUri);
    }

    return new AuthLogoutUrlResponse(builder.build(true).toUriString());
  }

  public AuthHealthResponse health() {
    Jwt jwt = currentJwt();
    return new AuthHealthResponse(
        true,
        jwt.getSubject(),
        jwt.getClaimAsString("preferred_username"),
        jwt.getIssuer() == null ? null : jwt.getIssuer().toString(),
        jwt.getIssuedAt(),
        jwt.getExpiresAt(),
        currentAuthorities());
  }

  private void writeLoginLog(User user, String qcid, Instant loginTime) {
    try {
      ShowLog showLog = new ShowLog();
      showLog.setUserId(user.getId());
      showLog.setUsername(user.getUsername());
      showLog.setQcid(qcid);
      showLog.setLoginTime(LocalDateTime.ofInstant(loginTime, ZoneOffset.UTC));
      showLog.setOperation("LOGIN");
      showLogRepository.save(showLog);
    } catch (Exception ignored) {
      // Audit failure must not block login success path.
    }
  }

  private AuthMeResponse toMeResponse(User user, Jwt jwt, List<String> authorities) {
    return new AuthMeResponse(
        user.getId(),
        user.getKeycloakId(),
        user.getUsername(),
        user.getQcid(),
        user.getRole(),
        authorities,
        authorities.contains(ROLE_ADMIN),
        jwt.getExpiresAt());
  }

  private ResponseStatusException mapLoginException(String code, String description, int httpStatus) {
    if ("invalid_grant".equals(code)) {
      return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_username_or_password");
    }
    if ("unauthorized_client".equals(code)) {
      return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "keycloak_client_not_allowed");
    }
    if ("invalid_client".equals(code)) {
      return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "keycloak_client_invalid");
    }
    if (description != null && !description.isBlank()) {
      return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "keycloak_login_failed: " + description);
    }
    if (httpStatus >= 500) {
      return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "keycloak_unavailable");
    }
    return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "keycloak_login_failed");
  }

  private void writeFailedLoginLog(String username) {
    try {
      ShowLog showLog = new ShowLog();
      showLog.setUserId(null);
      showLog.setUsername(username);
      showLog.setQcid(null);
      showLog.setLoginTime(LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
      showLog.setOperation("LOGIN_FAIL");
      showLogRepository.save(showLog);
    } catch (Exception ignored) {
      // Audit failure must not override authentication error mapping.
    }
  }

  private List<String> extractRealmRoles(Map<String, Object> tokenClaims) {
    Object realmAccess = tokenClaims.get("realm_access");
    if (!(realmAccess instanceof Map<?, ?> realmAccessMap)) {
      return List.of();
    }
    Object roles = realmAccessMap.get("roles");
    if (!(roles instanceof List<?> roleList)) {
      return List.of();
    }
    return roleList.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .filter(role -> role.startsWith("qcvmt-"))
        .map(role -> "ROLE_" + role)
        .sorted()
        .toList();
  }

  private long longValue(Object raw) {
    if (raw instanceof Number number) {
      return number.longValue();
    }
    if (raw instanceof String text) {
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    return 0;
  }

  private String claimAsString(Map<String, Object> claims, String claimName) {
    Object value = claims.get(claimName);
    return value instanceof String text ? text : null;
  }

  private Map<String, Object> requestTokenFromKeycloak(String username, String password) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "password");
    form.add("client_id", keycloakClientId);
    form.add("client_secret", keycloakClientSecret);
    form.add("username", username);
    form.add("password", password);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

    try {
      return restTemplate
          .postForEntity(keycloakTokenEndpoint(), new HttpEntity<>(form, headers), Map.class)
          .getBody();
    } catch (RestClientResponseException ex) {
      throw mapKeycloakError(ex);
    }
  }

  private ResponseStatusException mapKeycloakError(RestClientResponseException ex) {
    String responseBody = ex.getResponseBodyAsString();
    try {
      Map<String, Object> body = objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {
      });
      String error = claimAsString(body, "error");
      String description = claimAsString(body, "error_description");
      return mapLoginException(error, description, ex.getRawStatusCode());
    } catch (Exception ignored) {
      return mapLoginException(null, responseBody, ex.getRawStatusCode());
    }
  }

  private Map<String, Object> decodeJwtClaims(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length < 2) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "invalid_access_token");
      }
      byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
      String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
      return objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {
      });
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "invalid_access_token");
    } catch (ResponseStatusException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "invalid_access_token");
    }
  }

  private Instant expiresAt(Map<String, Object> tokenResponse) {
    long expiresIn = longValue(tokenResponse.get("expires_in"));
    if (expiresIn <= 0) {
      return null;
    }
    return Instant.now().plusSeconds(expiresIn);
  }

  private String keycloakTokenEndpoint() {
    return UriComponentsBuilder
        .fromUriString(keycloakServerUrl)
        .pathSegment("realms", keycloakRealm, "protocol", "openid-connect", "token")
        .build(true)
        .toUriString();
  }

  private Jwt currentJwt() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "jwt_not_found");
    }
    return jwt;
  }

  private List<String> currentAuthorities() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication_not_found");
    }
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .filter(Objects::nonNull)
        .sorted()
        .toList();
  }
}
