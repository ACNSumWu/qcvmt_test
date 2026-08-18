package com.mtl.qcvmt.service.n4;

import com.mtl.qcvmt.dto.response.RobContainer;
import com.mtl.qcvmt.n4.N4QueryRepository;
import com.mtl.qcvmt.n4.N4TableConstants;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class N4ContainerQueryService {

  private final N4QueryRepository n4QueryRepository;

  public N4ContainerQueryService(N4QueryRepository n4QueryRepository) {
    this.n4QueryRepository = n4QueryRepository;
  }

  public List<RobContainer> getROBList(
      String vesselId, String minBay, String maxBay, String qType) {
    if (vesselId == null || vesselId.isBlank() || minBay == null || minBay.isBlank()) {
      return Collections.emptyList();
    }
    String effectiveMaxBay = maxBay == null || maxBay.isBlank() ? minBay : maxBay;
    Map<String, RobContainer> result = new LinkedHashMap<>();
    addAll(result, queryRobByBay(vesselId, minBay, qType));
    if (!minBay.equals(effectiveMaxBay)) {
      addAll(result, queryRobByBay(vesselId, effectiveMaxBay, qType));
    }

    if ("LOAD".equalsIgnoreCase(qType)) {
      int bay = Integer.parseInt(minBay);
      String previous = formatBay(bay - 1);
      String next = formatBay(bay + 1);
      if (bay % 2 == 0) {
        Map<String, RobContainer> previousCells = byCoordinate(queryRobByBay(vesselId, previous, qType));
        Map<String, RobContainer> nextCells = byCoordinate(queryRobByBay(vesselId, next, qType));
        previousCells.forEach((coordinate, container) -> {
          if (nextCells.containsKey(coordinate)) {
            result.putIfAbsent(coordinate, container);
          }
        });
      } else {
        addAll(result, queryRobByBay(vesselId, previous, qType));
        addAll(result, queryRobByBay(vesselId, next, qType));
      }
    }
    return List.copyOf(result.values());
  }

  public List<RobContainer> getTwentyUnitList(String vesselId, String bay) {
    if (vesselId == null || vesselId.isBlank() || bay == null || bay.isBlank()) {
      return Collections.emptyList();
    }
    int bayNumber = Integer.parseInt(bay);
    String sql = "SELECT SUBSTR(iufv.last_pos_slot, 1, 2) AS bay, "
        + "iufv.last_pos_slot AS slot, iu.id AS container_id "
        + "FROM " + N4TableConstants.INV_UNIT_FCY_VISIT + " iufv "
        + "JOIN " + N4TableConstants.INV_UNIT + " iu ON iufv.unit_gkey = iu.gkey "
        + "JOIN " + N4TableConstants.ARGO_CARRIER_VISIT
        + " acv ON (iufv.actual_ib_cv = acv.gkey OR iufv.actual_ob_cv = acv.gkey) "
        + "WHERE acv.id = ? "
        + "AND ((iufv.actual_ib_cv = acv.gkey AND iufv.transit_state = 'S20_INBOUND') "
        + "OR (iufv.actual_ob_cv = acv.gkey AND iufv.transit_state = 'S60_LOADED')) "
        + "AND SUBSTR(iufv.last_pos_slot, 1, 2) IN (?, ?) "
        + "ORDER BY iufv.last_pos_slot";
    List<Map<String, Object>> rows = n4QueryRepository.queryForList(
        sql, vesselId, formatBay(bayNumber - 1), formatBay(bayNumber + 1));
    return rows.stream().map(this::toRobContainer).toList();
  }

  public List<Map<String, Object>> getHazardList(String unitId) {
    // String sql = "SELECT hm.unno, hm.proper_name FROM " +
    // N4TableConstants.REF_HAZARDOUS_MATERIAL + " hm "
    // + "JOIN " + N4TableConstants.INV_UNIT + " iu ON iu.goods = hm.gkey WHERE
    // iu.id = ?";
    // return n4QueryRepository.queryForList(sql, unitId);
    return null;
  }

  private List<RobContainer> queryRobByBay(String vesselId, String bay, String qType) {
    boolean discharge = "DISCH".equalsIgnoreCase(qType);
    String sql = "SELECT SUBSTR(iufv.last_pos_slot, 1, 2) AS bay, "
        + "iufv.last_pos_slot AS slot, iu.id AS container_id "
        + "FROM " + N4TableConstants.INV_UNIT_FCY_VISIT + " iufv "
        + "JOIN " + N4TableConstants.INV_UNIT + " iu ON iu.gkey = iufv.unit_gkey "
        + "JOIN " + N4TableConstants.ARGO_CARRIER_VISIT + " acv ON "
        + (discharge ? "iufv.actual_ib_cv = acv.gkey "
            : "(iufv.actual_ib_cv = acv.gkey OR iufv.actual_ob_cv = acv.gkey) ")
        + "WHERE acv.phase NOT IN ('60DEPARTED','70CLOSED','80CANCELED','90ARCHIVED') "
        + "AND acv.id = ? AND SUBSTR(iufv.last_pos_slot, 1, 2) = ? "
        + (discharge
            ? "AND iufv.transit_state = 'S20_INBOUND' AND iu.category = 'THRGH' "
                + "AND iufv.restow_typ = 'NONE' "
            : "AND ((iufv.actual_ib_cv = acv.gkey AND iufv.transit_state = 'S20_INBOUND') "
                + "OR (iufv.actual_ob_cv = acv.gkey AND iufv.transit_state = 'S60_LOADED')) ")
        + "ORDER BY iufv.last_pos_slot";
    return n4QueryRepository.queryForList(sql, vesselId, formatBay(Integer.parseInt(bay))).stream()
        .map(this::toRobContainer)
        .toList();
  }

  private Map<String, RobContainer> byCoordinate(List<RobContainer> containers) {
    return containers.stream().collect(Collectors.toMap(
        container -> container.tier() + container.row(),
        container -> container,
        (first, ignored) -> first,
        LinkedHashMap::new));
  }

  private void addAll(Map<String, RobContainer> target, List<RobContainer> containers) {
    containers.forEach(container -> target.putIfAbsent(
        container.tier() + container.row(), container));
  }

  private String formatBay(int bay) {
    return String.format("%02d", Math.max(0, bay));
  }

  private RobContainer toRobContainer(Map<String, Object> row) {
    String bay = value(row.get("bay"));
    String slot = value(row.get("slot"));
    String containerId = value(row.get("container_id"));
    String rowNo = slot != null && slot.length() >= 6 ? slot.substring(2, 4) : null;
    String tier = slot != null && slot.length() >= 6 ? slot.substring(4, 6) : null;
    return new RobContainer(bay, rowNo, tier, containerId, null);
  }

  private String value(Object value) {
    return value == null ? null : value.toString();
  }
}
