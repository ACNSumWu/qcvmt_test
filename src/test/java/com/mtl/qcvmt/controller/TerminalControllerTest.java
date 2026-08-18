package com.mtl.qcvmt.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mtl.qcvmt.dto.colorset.ColorSetResponse;
import com.mtl.qcvmt.dto.response.WorkQueueResult;
import com.mtl.qcvmt.dto.vessel.VesselResponse;
import com.mtl.qcvmt.dto.response.BayCellResponse;
import com.mtl.qcvmt.entity.SequenceVO;
import com.mtl.qcvmt.entity.User;
import com.mtl.qcvmt.service.ColorSetService;
import com.mtl.qcvmt.service.VesselService;
import com.mtl.qcvmt.service.keycloak.KeycloakUserSyncService;
import com.mtl.qcvmt.service.n4.N4ContainerQueryService;
import com.mtl.qcvmt.service.n4.N4FacilityQueryService;
import com.mtl.qcvmt.service.n4.N4VesselQueryService;
import com.mtl.qcvmt.service.n4.N4WorkQueueService;
import com.mtl.qcvmt.service.n4.TerminalBayPlanService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TerminalController.class)
@Import(com.mtl.qcvmt.config.SecurityConfig.class)
class TerminalControllerTest {

  @Autowired
  private MockMvc mockMvc;

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

  @MockBean
  private JwtDecoder jwtDecoder;

  @Test
  @WithMockUser(roles = { "qcvmt-user" })
  void testQueryWithUserRole() throws Exception {
    mockHappyPath();

    mockMvc.perform(get("/api/terminal/query").param("qcid", "QC01"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.cells[0].row").value("01"))
        .andExpect(jsonPath("$.data.cells[0].tier").value("82"));
    verify(n4VesselQueryService).getBayCells("VESSEL-1", "17", "A");
    verify(terminalBayPlanService).render(
        any(), any(WorkQueueResult.class),
        any(), any(), eq("VISIT-1"), eq("A"), eq(List.of("17", "19")));
    verify(terminalBayPlanService).isRefueling("VISIT-1");
  }

  @Test
  @WithMockUser(roles = { "qcvmt-admin" })
  void testQueryWithAdminRole() throws Exception {
    mockHappyPath();
    mockMvc.perform(get("/api/terminal/query").param("qcid", "QC01"))
        .andExpect(status().isOk());
  }

  @Test
  void testQueryWithoutAuthentication() throws Exception {
    mockMvc.perform(get("/api/terminal/query").param("qcid", "QC01"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void testQueryWithInvalidJwt() throws Exception {
    when(jwtDecoder.decode("bad-token")).thenThrow(new BadJwtException("invalid token"));
    mockMvc.perform(get("/api/terminal/query")
        .param("qcid", "QC01")
        .header("Authorization", "Bearer bad-token"))
        .andExpect(status().isUnauthorized());
  }

  private void mockHappyPath() {
    User user = new User(1, "kid-1", "QC01", "alice", "qcvmt-user", "admin", LocalDateTime.now());
    when(keycloakUserSyncService.getOrCreateLocalUser()).thenReturn(user);
    when(n4FacilityQueryService.queryQcId()).thenReturn(List.of("QC01"));

    SequenceVO sequence = new SequenceVO();
    sequence.setQdeck("A");
    sequence.setBay("17");
    WorkQueueResult queue = new WorkQueueResult("LOAD", "QO-1", "VISIT-1", "17", "19", "A", List.of(sequence));
    when(n4WorkQueueService.getCurrentWorkQueue("QC01")).thenReturn(queue);

    when(n4VesselQueryService.getVesselName("VISIT-1")).thenReturn("VESSEL-1");
    when(n4VesselQueryService.getBayCells(anyString(), anyString(), anyString()))
        .thenReturn(List.of(BayCellResponse.empty("01", "82")));
    when(n4ContainerQueryService.getROBList(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(List.of());
    when(terminalBayPlanService.render(any(), any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(List.of(BayCellResponse.empty("01", "82")));

    when(vesselService.listByVesselId("VESSEL-1"))
        .thenReturn(List.of(new VesselResponse(1, "VESSEL-1", "A", "17", "01", "19", "82", "90", 0)));
    when(colorSetService.list())
        .thenReturn(List.of(new ColorSetResponse(1, "EMPTY", "white", 0)));
  }
}
