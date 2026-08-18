package com.mtl.qcvmt.service.n4;

import com.mtl.qcvmt.dto.response.BayCellResponse;
import com.mtl.qcvmt.entity.CellMatrix;
import com.mtl.qcvmt.entity.Vessel;
import com.mtl.qcvmt.n4.N4QueryRepository;
import com.mtl.qcvmt.n4.N4TableConstants;
import com.mtl.qcvmt.repository.CellMatrixRepository;
import com.mtl.qcvmt.repository.VesselRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class N4VesselQueryService {

  private final N4QueryRepository n4QueryRepository;
  private final CellMatrixRepository cellMatrixRepository;
  private final VesselRepository vesselRepository;

  public N4VesselQueryService(
      N4QueryRepository n4QueryRepository,
      CellMatrixRepository cellMatrixRepository,
      VesselRepository vesselRepository) {
    this.n4QueryRepository = n4QueryRepository;
    this.cellMatrixRepository = cellMatrixRepository;
    this.vesselRepository = vesselRepository;
  }

  public List<BayCellResponse> getBayCells(String vesselId, String bay, String qdeck) {
    if (vesselId == null || vesselId.isBlank()
        || bay == null || bay.isBlank()
        || qdeck == null || qdeck.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "vesselId, bay and deckHold are required for bay layout");
    }

    Optional<Vessel> configuredVessel = findConfiguredVessel(vesselId, qdeck, bay);
    if (configuredVessel.isPresent()) {
      Vessel vessel = configuredVessel.get();
      List<CellMatrix> configuredRows = cellMatrixRepository.findByTypeAndRowBetweenOrderByIdDesc(
          qdeck, formatPosition(requireNumber(vessel.getRowStart(), "rowStart")),
          formatPosition(requireNumber(vessel.getRowEnd(), "rowEnd")));
      if (!configuredRows.isEmpty()) {
        return expandConfiguredRows(
            configuredRows, vessel.getTierStart(), vessel.getTierEnd(), qdeck);
      }
    }

    List<CellMatrix> fallbackRows = cellMatrixRepository.findByTypeAndActiveOrderByIdDesc(qdeck, "1");
    if (fallbackRows.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Bay matrix rows not found for deckHold=" + qdeck);
    }
    return expandConfiguredRows(fallbackRows, "0", fallbackRows.get(0).getTier(), qdeck);
  }

  private Optional<Vessel> findConfiguredVessel(String vesselId, String deckHold, String bay) {
    Integer bayNumber = parseNumber(bay);
    Optional<Vessel> currentBay = findConfiguredVessel(
        vesselId, deckHold, bayCandidates(bay, bayNumber));
    if (currentBay.isPresent()) {
      return currentBay;
    }

    if (bayNumber == null || bayNumber <= 0) {
      return Optional.empty();
    }
    int previousBayNumber = bayNumber - 1;
    String previousBay = String.format("%0" + Math.max(2, bay.length()) + "d", previousBayNumber);
    return findConfiguredVessel(
        vesselId, deckHold, bayCandidates(previousBay, previousBayNumber));
  }

  private Optional<Vessel> findConfiguredVessel(
      String vesselId, String deckHold, Set<String> bayCandidates) {
    for (String candidate : bayCandidates) {
      Optional<Vessel> configured =
          vesselRepository.findByVesselIdAndDeckHoldAndBay(vesselId, deckHold, candidate);
      if (configured.isPresent()) {
        return configured;
      }
    }
    return Optional.empty();
  }

  private Set<String> bayCandidates(String original, Integer bayNumber) {
    Set<String> candidates = new LinkedHashSet<>();
    candidates.add(original);
    if (bayNumber != null) {
      candidates.add(String.valueOf(bayNumber));
    }
    return candidates;
  }

  private List<BayCellResponse> expandConfiguredRows(
      List<CellMatrix> configuredRows,
      String tierStartValue,
      String tierEndValue,
      String deckHold) {
    int tierStart = requireNumber(tierStartValue, "tierStart");
    int tierEnd = requireNumber(tierEndValue, "tierEnd");
    if (tierStart > tierEnd) {
      throw new IllegalStateException("Bay tier range start must not exceed end");
    }

    List<BayCellResponse> cells = new ArrayList<>();
    for (CellMatrix configuredRow : configuredRows) {
      for (int tier = tierEnd; tier >= tierStart; tier -= 2) {
        if ("B".equalsIgnoreCase(deckHold) && tier == 0) {
          continue;
        }
        cells.add(BayCellResponse.empty(
            formatPosition(requireNumber(configuredRow.getRow(), "row")),
            formatPosition(tier)));
      }
    }
    return cells;
  }

  private int requireNumber(String value, String field) {
    Integer parsed = parseNumber(value);
    if (parsed == null) {
      throw new IllegalStateException("Invalid bay " + field + ": " + value);
    }
    return parsed;
  }

  private Integer parseNumber(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private String formatPosition(int value) {
    return String.format("%02d", value);
  }

  public String getVesselName(String vesselId) {
    String sql = "SELECT vv.name FROM " + N4TableConstants.ARGO_CARRIER_VISIT + " acv "
        + "JOIN " + N4TableConstants.VSL_VISIT_DETAILS + " vvd ON vvd.vvd_gkey = acv.cvcvd_gkey "
        + "JOIN " + N4TableConstants.VSL_VESSELS + " vv ON vv.gkey = vvd.vessel_gkey "
        + "WHERE acv.id = ? FETCH FIRST 1 ROWS ONLY";
    return n4QueryRepository.queryForObject(sql, String.class, vesselId);
  }
}
