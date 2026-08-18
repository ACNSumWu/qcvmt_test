package com.mtl.qcvmt.service.n4;

import com.mtl.qcvmt.dto.response.WorkQueueResult;
import com.mtl.qcvmt.entity.SequenceVO;
import com.mtl.qcvmt.exception.BusinessException;
import com.mtl.qcvmt.n4.N4QueryRepository;
import com.mtl.qcvmt.n4.N4TableConstants;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Rebuilds the QC work-queue / bay lookup to match the legacy (springMVC CellDaoImpl) behaviour.
 *
 * <p>The legacy implementation always derives the current bay from the unit's actual, current
 * vessel position ({@code inv_unit_fcy_visit.last_pos_slot}), and only considers work-queue rows
 * that are: active (blue), still located on the vessel, not yard/shift moves, belong to a carrier
 * visit that has not departed/closed/canceled/archived, and match the load/discharge specific
 * move-stage. The previous version of this service read the *planned* slot
 * ({@code inv_wi.pos_slot}) instead, and skipped all of those filters, which is why its bay
 * numbers diverged from the legacy tool. All queries below were realigned with the legacy SQL.</p>
 */
@Service
public class N4WorkQueueService {

  private static final String COMMON_FROM = "FROM "
      + N4TableConstants.INV_WQ + " iq, "
      + N4TableConstants.INV_WI + " iw, "
      + N4TableConstants.INV_UNIT_YARD_VISIT + " iuyv, "
      + N4TableConstants.INV_UNIT_FCY_VISIT + " iufv, "
      + N4TableConstants.INV_UNIT + " iu, "
      + N4TableConstants.XPS_CRANESHIFT + " xcs, "
      + N4TableConstants.XPS_POINTOFWORK + " xpow, "
      + N4TableConstants.REF_EQUIPMENT + " re, "
      + N4TableConstants.INV_GOODS + " ig, "
      + N4TableConstants.ARGO_CARRIER_VISIT + " acv ";

  private static final String COMMON_WHERE = "WHERE iw.work_queue_gkey = iq.gkey "
      + "AND iw.uyv_gkey = iuyv.gkey "
      + "AND iuyv.ufv_gkey = iufv.gkey "
      + "AND iq.pos_locid = acv.id "
      + "AND iufv.unit_gkey = iu.gkey "
      + "AND iq.first_shift_pkey = xcs.pkey "
      + "AND xcs.owner_pow = xpow.pkey "
      + "AND re.gkey = iu.eq_gkey "
      + "AND ig.gkey = iu.goods "
      + "AND acv.phase NOT IN ('60DEPARTED','70CLOSED','80CANCELED','90ARCHIVED') "
      + "AND iq.qdeck IN ('A','B') "
      + "AND iq.pos_loctype = 'VESSEL' "
      + "AND iq.is_blue = '1' "
      + "AND iw.move_kind != 'YARD' "
      + "AND iw.move_kind != 'SHFT' ";

  private final N4QueryRepository n4QueryRepository;

  public N4WorkQueueService(N4QueryRepository n4QueryRepository) {
    this.n4QueryRepository = n4QueryRepository;
  }

  public String getLoadOrder(String qcid) {
    String sql = "SELECT MIN(iq.qorder) " + COMMON_FROM + COMMON_WHERE
        + "AND iq.qtype = 'LOAD' "
        + "AND iw.move_stage = 'COMPLETE' "
        + "AND iufv.time_move BETWEEN SYSDATE - 1/1440 AND SYSDATE "
        + "AND xpow.name = ?";
    String qorder = n4QueryRepository.queryForObject(sql, String.class, qcid);
    if (qorder != null) {
      return qorder;
    }

    // Fall back to any not-yet-completed load move so a slower-refreshing crane still resolves.
    String fallbackSql = "SELECT MIN(iq.qorder) " + COMMON_FROM + COMMON_WHERE
        + "AND iq.qtype = 'LOAD' "
        + "AND iw.move_stage != 'COMPLETE' "
        + "AND xpow.name = ?";
    return n4QueryRepository.queryForObject(fallbackSql, String.class, qcid);
  }

  public String getDischargeOrder(String qcid) {
    String sql = "SELECT MIN(iq.qorder) " + COMMON_FROM + COMMON_WHERE
        + "AND iq.qtype = 'DISCH' "
        + "AND iw.move_stage IN ('PLANNED','NONE') "
        + "AND xpow.name = ?";
    return n4QueryRepository.queryForObject(sql, String.class, qcid);
  }

  public WorkQueueResult getCurrentWorkQueue(String qcNum) {
    String loadOrder = safeGetLoadOrder(qcNum);
    String dischOrder = safeGetDischargeOrder(qcNum);

    String qtype;
    String qorder;
    if (loadOrder == null && dischOrder == null) {
      return emptyResult();
    } else if (dischOrder == null) {
      qtype = "LOAD";
      qorder = loadOrder;
    } else if (loadOrder == null) {
      qtype = "DISCH";
      qorder = dischOrder;
    } else if (loadOrder.compareTo(dischOrder) > 0) {
      // Same tie-break as the legacy CellDaoImpl.getQorder(): the numerically/lexically
      // smaller qorder is the one currently being worked.
      qtype = "DISCH";
      qorder = dischOrder;
    } else {
      qtype = "LOAD";
      qorder = loadOrder;
    }

    WorkQueueResult result = buildWorkQueueResult(qcNum, qtype, qorder);
    return result != null ? result : emptyResult();
  }

  public List<SequenceVO> getSequenceList(String qcid, String qorder, String qtype) {
    if (qorder == null || qorder.isBlank() || qtype == null || qtype.isBlank()) {
      return Collections.emptyList();
    }

    String sql = "SELECT iufv.last_pos_slot AS current_pos_slot, iw.pos_slot AS planned_pos_slot, "
        + "TO_CHAR(iufv.time_move, 'yyyy-mm-dd hh24:mi:ss') AS time_move, "
        + "iq.qtype, iq.qdeck, iq.qrow, iw.move_stage AS status, iu.is_oog, "
        + "CASE WHEN ig.temp_reqd_c IS NULL THEN '0' ELSE '1' END AS is_powered, "
        + "CASE re.iso_group WHEN 'TN' THEN '1' WHEN 'TD' THEN '1' WHEN 'TG' THEN '1' ELSE '0' END AS istank, "
        // NOTE: twin_with/twin_int_fetch/is_tandem_with_next/is_tandem_with_previous are
        // intentionally left unqualified (no table alias), matching the legacy CellDaoImpl SQL.
        // These columns do not live on inv_unit (iu) - qualifying them with iu. causes an
        // ORA-00904 invalid identifier error, which surfaces to the frontend as an
        // unhandled N4QueryException -> HTTP 500.
        + "CASE WHEN (twin_with = 'PREV' OR twin_with = 'NEXT') AND twin_int_fetch = 1 "
        + "AND (is_tandem_with_next = 1 OR is_tandem_with_previous = 1) THEN '1' ELSE '0' END AS isquad, "
        + "CASE WHEN twin_with = 'NONE' AND twin_int_fetch = 0 "
        + "AND (is_tandem_with_next = 1 OR is_tandem_with_previous = 1) THEN '1' ELSE '0' END AS istandem, "
        + "CASE WHEN (twin_with = 'PREV' OR twin_with = 'NEXT') AND twin_int_fetch = 1 "
        + "AND (is_tandem_with_next = 0 AND is_tandem_with_previous = 0) THEN '1' ELSE '0' END AS istwin, "
        + "CASE WHEN (twin_with = 'NONE' AND twin_int_fetch = 0 "
        + "AND (is_tandem_with_next = 0 AND is_tandem_with_previous = 0)) "
        + "OR is_tandem_with_next IS NULL OR is_tandem_with_previous IS NULL THEN '1' ELSE '0' END AS issingle "
        + COMMON_FROM + COMMON_WHERE
        + "AND iq.qtype = ? "
        + moveStageFilter(qtype) + " "
        + "AND xpow.name = ? "
        + "AND iq.qorder = ? "
        + "ORDER BY " + ("LOAD".equals(qtype) ? "iufv.time_move DESC, iufv.last_pos_slot DESC" : "iufv.last_pos_slot DESC");

    List<Map<String, Object>> rows = n4QueryRepository.queryForList(sql, qtype, qcid, qorder);
    List<SequenceVO> sequences = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      SequenceVO sequence = new SequenceVO();
      String currentPosSlot = value(row.get("current_pos_slot"));
      sequence.setCurrentPosSlot(currentPosSlot);
      sequence.setPlannedPosSlot(value(row.get("planned_pos_slot")));
      sequence.setQtype(value(row.get("qtype")));
      sequence.setQdeck(value(row.get("qdeck")));
      sequence.setQrow(value(row.get("qrow")));
      sequence.setStatus(value(row.get("status")));
      sequence.setTimeMove(value(row.get("time_move")));
      sequence.setBay(currentPosSlot != null && currentPosSlot.length() >= 2 ? currentPosSlot.substring(0, 2) : null);
      sequence.setOog("1".equals(value(row.get("is_oog"))));
      sequence.setPowered("1".equals(value(row.get("is_powered"))));
      sequence.setTank("1".equals(value(row.get("istank"))));
      sequence.setQuad("1".equals(value(row.get("isquad"))));
      sequence.setTandem("1".equals(value(row.get("istandem"))));
      sequence.setTwin("1".equals(value(row.get("istwin"))));
      sequence.setSingle("1".equals(value(row.get("issingle"))));
      sequences.add(sequence);
    }
    return sequences;
  }

  private WorkQueueResult buildWorkQueueResult(String qcid, String qtype, String qorder) {
    if (qorder == null || qorder.isBlank()) {
      return null;
    }

    BayRange bayRange = resolveBayRange(qcid, qorder, qtype);
    if (bayRange == null) {
      return null;
    }

    List<SequenceVO> sequences = getSequenceList(qcid, qorder, qtype);
    if (sequences.isEmpty()) {
      return null;
    }

    return new WorkQueueResult(
        qtype,
        qorder,
        bayRange.vesselId(),
        bayRange.minBay(),
        bayRange.maxBay(),
        bayRange.deckHold(),
        sequences);
  }

  /**
   * Determines the min/max bay currently worked, mirroring legacy CellDaoImpl.checkSequenceList():
   * the bay is read off the unit's actual current slot (last_pos_slot), a queue may only span at
   * most two adjacent bays (twin/tandem/quad lifts), and anything else means the data is
   * inconsistent and must be rejected rather than silently displayed.
   */
  private BayRange resolveBayRange(String qcid, String qorder, String qtype) {
    String sql = "SELECT MIN(SUBSTR(iufv.last_pos_slot,1,2)) AS min_bay, "
        + "MAX(SUBSTR(iufv.last_pos_slot,1,2)) AS max_bay, "
        + "COUNT(DISTINCT SUBSTR(iufv.last_pos_slot,1,2)) AS bay_count, "
        + "MIN(iq.pos_locid) AS vessel_id, MIN(iq.qdeck) AS deck_hold "
        + COMMON_FROM + COMMON_WHERE
        + "AND iq.qtype = ? "
        + moveStageFilter(qtype) + " "
        + "AND xpow.name = ? "
        + "AND iq.qorder = ?";

    Map<String, Object> meta = n4QueryRepository.queryForMap(sql, qtype, qcid, qorder);
    long bayCount = toLong(meta.get("bay_count"));
    if (bayCount == 0) {
      return null;
    }
    if (bayCount > 2) {
      throw new BusinessException("error_more_than_3bay");
    }

    String minBay = value(meta.get("min_bay"));
    String maxBay = value(meta.get("max_bay"));
    assertBayIsNumeric(minBay);
    assertBayIsNumeric(maxBay);

    return new BayRange(value(meta.get("vessel_id")), minBay, maxBay, value(meta.get("deck_hold")));
  }

  private void assertBayIsNumeric(String bay) {
    if (bay == null) {
      return;
    }
    try {
      Integer.parseInt(bay);
    } catch (NumberFormatException ex) {
      throw new BusinessException("error_bay_number_integer");
    }
  }

  private String moveStageFilter(String qtype) {
    return "DISCH".equals(qtype) ? "AND iw.move_stage IN ('PLANNED','NONE')" : "AND iw.move_stage = 'COMPLETE'";
  }

  private WorkQueueResult emptyResult() {
    return new WorkQueueResult("UNKNOWN", null, null, null, null, null, Collections.emptyList());
  }

  private String safeGetLoadOrder(String qcid) {
    try {
      return getLoadOrder(qcid);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private String safeGetDischargeOrder(String qcid) {
    try {
      return getDischargeOrder(qcid);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private long toLong(Object obj) {
    if (obj == null) {
      return 0L;
    }
    return obj instanceof Number number ? number.longValue() : Long.parseLong(obj.toString());
  }

  private String value(Object obj) {
    return obj == null ? null : obj.toString();
  }

  private record BayRange(String vesselId, String minBay, String maxBay, String deckHold) {
  }
}
