package com.mtl.qcvmt.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mtl.qcvmt.controller.TerminalController;
import com.mtl.qcvmt.controller.UserController;
import com.mtl.qcvmt.dto.colorset.ColorSetResponse;
import com.mtl.qcvmt.dto.response.BayCellResponse;
import com.mtl.qcvmt.dto.response.WorkQueueResult;
import com.mtl.qcvmt.dto.vessel.VesselResponse;
import com.mtl.qcvmt.entity.SequenceVO;
import com.mtl.qcvmt.entity.User;
import com.mtl.qcvmt.service.ColorSetService;
import com.mtl.qcvmt.service.UserService;
import com.mtl.qcvmt.service.VesselService;
import com.mtl.qcvmt.service.keycloak.KeycloakUserSyncService;
import com.mtl.qcvmt.service.n4.N4ContainerQueryService;
import com.mtl.qcvmt.service.n4.N4FacilityQueryService;
import com.mtl.qcvmt.service.n4.N4VesselQueryService;
import com.mtl.qcvmt.service.n4.N4WorkQueueService;
import com.mtl.qcvmt.service.n4.TerminalBayPlanService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = { UserController.class, TerminalController.class, SecurityConfigTest.HealthController.class })
@Import(SecurityConfig.class)
class SecurityConfigTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private JwtDecoder jwtDecoder;

  @MockBean
  private UserService userService;

  @MockBean
  private KeycloakUserSyncService keycloakUserSyncService;

  @MockBean
  private N4WorkQueueService n4WorkQueueService;

  @MockBean
  private N4ContainerQueryService n4ContainerQueryService;

  @MockBean
  private N4VesselQueryService n4VesselQueryService;

  @MockBean
  private N4FacilityQueryService n4FacilityQueryService;
  @MockBean
  private TerminalBayPlanService terminalBayPlanService;

  @MockBean
  private VesselService vesselService;

  @MockBean
  private ColorSetService colorSetService;

  @Test
  @Disabled("Actuator endpoint behavior is unstable in this WebMvc slice; covered by integration-level health check")
  void testActuatorHealthPublic() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(result -> {
      int statusCode = result.getResponse().getStatus();
      assertThat(statusCode).isLessThan(500);
    });
  }

  @Test
  @WithMockUser(roles = { "qcvmt-user" })
  void testAdminRoutesRequireAdminRole() throws Exception {
    mockMvc.perform(get("/api/users")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = { "qcvmt-user" })
  void testTerminalRoutesAccessibleByUser() throws Exception {
    mockTerminalHappyPath();
    mockMvc.perform(get("/api/terminal/query").param("qcid", "QC01")).andExpect(status().isOk());
  }

  @Test
  void testAdminRoutesRequireAuthentication() throws Exception {
    mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
  }

  private void mockTerminalHappyPath() {
    User user = new User(1, "kid-1", "QC01", "alice", "qcvmt-user", "admin", LocalDateTime.now());
    when(keycloakUserSyncService.getOrCreateLocalUser()).thenReturn(user);
    when(n4FacilityQueryService.queryQcId()).thenReturn(List.of("QC01"));

    SequenceVO sequence = new SequenceVO();
    sequence.setQdeck("A");
    sequence.setBay("17");
    WorkQueueResult queue = new WorkQueueResult("LOAD", "QO-1", "V123456", "17", "19", "A", List.of(sequence));
    when(n4WorkQueueService.getCurrentWorkQueue("QC01")).thenReturn(queue);

    when(n4VesselQueryService.getBayCells(anyString(), anyString(), anyString()))
        .thenReturn(List.of(BayCellResponse.empty("01", "82")));
    when(n4ContainerQueryService.getROBList(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(List.of());
    when(terminalBayPlanService.render(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        anyString(),
        anyString(),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(BayCellResponse.empty("01", "82")));
    when(vesselService.list())
        .thenReturn(List.of(new VesselResponse(1, "V123456", "A", "17", "01", "19", "82", "90", 0)));
    when(colorSetService.list())
        .thenReturn(List.of(new ColorSetResponse(1, "EMPTY", "white", 0)));
  }

  @RestController
  static class HealthController {

    @GetMapping("/actuator/health")
    public String health() {
      return "UP";
    }
  }
}
