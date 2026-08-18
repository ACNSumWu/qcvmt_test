package com.mtl.qcvmt.controller;

import com.mtl.qcvmt.dto.common.ApiResponse;
import com.mtl.qcvmt.dto.response.BayCellResponse;
import com.mtl.qcvmt.dto.response.RobContainer;
import com.mtl.qcvmt.dto.response.TerminalView;
import com.mtl.qcvmt.dto.response.WorkQueueResult;
import com.mtl.qcvmt.dto.vessel.VesselResponse;
import com.mtl.qcvmt.entity.SequenceVO;
import com.mtl.qcvmt.entity.User;
import com.mtl.qcvmt.exception.BusinessException;
import com.mtl.qcvmt.exception.N4QueryException;
import com.mtl.qcvmt.service.ColorSetService;
import com.mtl.qcvmt.service.VesselService;
import com.mtl.qcvmt.service.keycloak.KeycloakUserSyncService;
import com.mtl.qcvmt.service.n4.N4ContainerQueryService;
import com.mtl.qcvmt.service.n4.N4FacilityQueryService;
import com.mtl.qcvmt.service.n4.N4VesselQueryService;
import com.mtl.qcvmt.service.n4.N4WorkQueueService;
import com.mtl.qcvmt.service.n4.TerminalBayPlanService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/terminal")
@PreAuthorize("hasAnyRole('qcvmt-admin', 'qcvmt-user')")
public class TerminalController {

  private final KeycloakUserSyncService keycloakUserSyncService;
  private final N4WorkQueueService n4WorkQueueService;
  private final N4ContainerQueryService n4ContainerQueryService;
  private final N4VesselQueryService n4VesselQueryService;
  private final N4FacilityQueryService n4FacilityQueryService;
  private final TerminalBayPlanService terminalBayPlanService;
  private final VesselService vesselService;
  private final ColorSetService colorSetService;
  private final boolean allowEmptyQueue;

  public TerminalController(
      KeycloakUserSyncService keycloakUserSyncService,
      N4WorkQueueService n4WorkQueueService,
      N4ContainerQueryService n4ContainerQueryService,
      N4VesselQueryService n4VesselQueryService,
      N4FacilityQueryService n4FacilityQueryService,
      TerminalBayPlanService terminalBayPlanService,
      VesselService vesselService,
      ColorSetService colorSetService,
      @Value("${qcvmt.terminal.allow-empty-queue:false}") boolean allowEmptyQueue) {
    this.keycloakUserSyncService = keycloakUserSyncService;
    this.n4WorkQueueService = n4WorkQueueService;
    this.n4ContainerQueryService = n4ContainerQueryService;
    this.n4VesselQueryService = n4VesselQueryService;
    this.n4FacilityQueryService = n4FacilityQueryService;
    this.terminalBayPlanService = terminalBayPlanService;
    this.vesselService = vesselService;
    this.colorSetService = colorSetService;
    this.allowEmptyQueue = allowEmptyQueue;
  }

  @GetMapping("/query")
  public ApiResponse<TerminalView> query(
      @RequestParam(value = "qcid", required = false) String qcid,
      @RequestParam(value = "qcNum", required = false) String qcNum) {
    User currentUser = keycloakUserSyncService.getOrCreateLocalUser();

    String effectiveQcid = resolveQcid(qcid, qcNum, currentUser.getQcid());
    validateQcid(effectiveQcid);
    WorkQueueResult workQueue = n4WorkQueueService.getCurrentWorkQueue(effectiveQcid);

    if (isQueueEmpty(workQueue) && !allowEmptyQueue) {
      throw new BusinessException("terminal_queue_not_found");
    }

    String vesselId = coalesce(workQueue.vesselId(), firstVesselId());
    String deckHold = coalesce(workQueue.deckHold(), firstDeckHold(workQueue.sequences()));
    String bay = coalesce(workQueue.minBay(), firstBay(workQueue.sequences()));
    List<VesselResponse> matchedVessels = resolveTerminalVessels(vesselId);
    String layoutVesselId = matchedVessels.stream()
        .findFirst()
        .map(VesselResponse::vesselId)
        .orElse(vesselId);

    List<BayCellResponse> layout =
        n4VesselQueryService.getBayCells(layoutVesselId, bay, deckHold);
    List<RobContainer> robContainers = n4ContainerQueryService.getROBList(
        vesselId, bay, workQueue.maxBay(), workQueue.qType());
    List<RobContainer> twentyContainers =
        shouldLoadTwentyContainers(workQueue, bay)
            ? n4ContainerQueryService.getTwentyUnitList(vesselId, bay)
            : Collections.emptyList();

    if (robContainers.isEmpty()) {
      robContainers = Collections.emptyList();
    }
    List<BayCellResponse> cells = terminalBayPlanService.render(
        layout,
        workQueue,
        robContainers,
        twentyContainers,
        vesselId,
        deckHold,
        participatingBays(bay, workQueue.maxBay()));

    TerminalView response = new TerminalView(
        matchedVessels,
        workQueue,
        colorSetService.list(),
        robContainers,
        cells,
        vesselId,
        coalesce(bay, workQueue.maxBay()),
        coalesce(deckHold, "A"),
        workQueue.qType(),
        robContainers.size(),
        safeSize(workQueue.sequences()),
        countBy(workQueue.sequences(), SequenceVO::isDg),
        countBy(workQueue.sequences(), SequenceVO::isPowered),
        countBy(workQueue.sequences(), SequenceVO::isTank),
        countBy(workQueue.sequences(), SequenceVO::isOog),
        countBy(workQueue.sequences(), SequenceVO::isTwin),
        countBy(workQueue.sequences(), SequenceVO::isTandem),
        countBy(workQueue.sequences(), SequenceVO::isQuad),
        terminalBayPlanService.isRefueling(vesselId),
        queueStatus(workQueue));

    return ApiResponse.ok("qcid=" + effectiveQcid + "; user=" + currentUser.getUsername(), response);
  }

  private void validateQcid(String qcid) {
    List<String> allQcids = n4FacilityQueryService.queryQcId();
    if (allQcids.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No QC IDs available from N4");
    }
    if (!allQcids.contains(qcid)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown qcid: " + qcid);
    }
  }

  private String resolveQcid(String qcid, String qcNum, String userQcid) {
    if (qcid != null && !qcid.isBlank()) {
      return qcid;
    }
    if (qcNum != null && !qcNum.isBlank()) {
      return qcNum;
    }
    if (userQcid != null && !userQcid.isBlank()) {
      return userQcid;
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "qcid or qcNum is required");
  }

  private String firstVesselId() {
    return vesselService.list().stream().findFirst().map(v -> v.vesselId()).orElse(null);
  }

  private List<VesselResponse> resolveTerminalVessels(String vesselId) {
    if (vesselId == null || vesselId.isBlank()) {
      return Collections.emptyList();
    }

    try {
      String vesselName = n4VesselQueryService.getVesselName(vesselId);
      List<VesselResponse> vessels = vesselService.listByVesselId(vesselName);
      if (!vessels.isEmpty()) {
        return vessels;
      }
    } catch (N4QueryException | ResponseStatusException ignored) {
    }

    return vesselService.listByVesselId(vesselId);
  }

  private String firstDeckHold(List<SequenceVO> sequences) {
    if (sequences == null || sequences.isEmpty()) {
      return null;
    }
    return sequences.stream().map(SequenceVO::getQdeck).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
  }

  private String firstBay(List<SequenceVO> sequences) {
    if (sequences == null || sequences.isEmpty()) {
      return null;
    }
    return sequences.stream().map(SequenceVO::getBay).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
  }

  private String coalesce(String preferred, String fallback) {
    if (preferred != null && !preferred.isBlank()) {
      return preferred;
    }
    return fallback;
  }

  private boolean isQueueEmpty(WorkQueueResult workQueue) {
    if (workQueue == null) {
      return true;
    }
    return workQueue.qorder() == null || workQueue.qorder().isBlank() || safeSize(workQueue.sequences()) == 0;
  }

  private int safeSize(List<?> list) {
    return list == null ? 0 : list.size();
  }

  private int countBy(List<SequenceVO> sequences, Predicate<SequenceVO> predicate) {
    if (sequences == null || sequences.isEmpty()) {
      return 0;
    }
    return (int) sequences.stream().filter(predicate).count();
  }

  private String queueStatus(WorkQueueResult workQueue) {
    return isQueueEmpty(workQueue) ? "EMPTY" : "READY";
  }

  private boolean shouldLoadTwentyContainers(WorkQueueResult workQueue, String bay) {
    if (!"DISCH".equalsIgnoreCase(workQueue.qType())
        || bay == null
        || !bay.equals(workQueue.maxBay())) {
      return false;
    }
    try {
      return Integer.parseInt(bay) % 2 == 0;
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  private List<String> participatingBays(String minBay, String maxBay) {
    List<String> bays = new ArrayList<>();
    if (minBay != null && !minBay.isBlank()) {
      bays.add(minBay);
    }
    if (maxBay != null && !maxBay.isBlank() && !maxBay.equals(minBay)) {
      bays.add(maxBay);
    }
    return bays;
  }
}
