package com.mtl.qcvmt.service.n4;

import com.mtl.qcvmt.dto.response.BayCellResponse;
import com.mtl.qcvmt.dto.response.RobContainer;
import com.mtl.qcvmt.dto.response.WorkQueueResult;
import com.mtl.qcvmt.entity.SequenceVO;
import com.mtl.qcvmt.entity.VesselColor;
import com.mtl.qcvmt.repository.VesselColorRepository;
import com.mtl.qcvmt.repository.VesselRefuelRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class TerminalBayPlanService {

  private final VesselRefuelRepository vesselRefuelRepository;
  private final VesselColorRepository vesselColorRepository;

  public TerminalBayPlanService(
      VesselRefuelRepository vesselRefuelRepository,
      VesselColorRepository vesselColorRepository) {
    this.vesselRefuelRepository = vesselRefuelRepository;
    this.vesselColorRepository = vesselColorRepository;
  }

  public List<BayCellResponse> render(
      List<BayCellResponse> layout,
      WorkQueueResult workQueue,
      List<RobContainer> robContainers,
      List<RobContainer> twentyContainers,
      String vesselId,
      String deckHold,
      List<String> bays) {
    Map<String, CellState> states = buildWorkQueueStates(workQueue);
    mergeInactive(states, robContainers, false);
    mergeInactive(states, twentyContainers, true);
    Set<String> refuelCells = refuelCells(vesselId, deckHold, bays);
    boolean refueling = vesselRefuelRepository.existsByVesselIdAndIsRefuel(vesselId, "Yes");

    return layout.stream().map(cell -> {
      String key = key(cell.tier(), cell.row());
      if (refueling && (refuelCells.contains(key) || refuelCells.contains("blank" + cell.row()))) {
        return new BayCellResponse(
            cell.row(), cell.tier(), cell.active(), "refuel", "", false, false);
      }
      CellState state = states.get(key);
      if (state == null) {
        return cell;
      }
      String status = state.twenty ? "twenty" : state.complex ? "complexunit" : state.status;
      return new BayCellResponse(
          cell.row(),
          cell.tier(),
          cell.active(),
          status,
          text(state, isAcrossBay(workQueue)),
          state.dg,
          "load".equals(state.status));
    }).toList();
  }

  public boolean isRefueling(String vesselId) {
    return vesselRefuelRepository.existsByVesselIdAndIsRefuel(vesselId, "Yes");
  }

  private Map<String, CellState> buildWorkQueueStates(WorkQueueResult workQueue) {
    Map<String, CellState> states = new HashMap<>();
    List<SequenceVO> sequences = workQueue.sequences();
    String firstLoadTime = sequences.isEmpty() ? null : sequences.get(0).getTimeMove();

    for (SequenceVO sequence : sequences) {
      String slot = sequence.getCurrentPosSlot();
      if (slot == null || slot.length() < 6) {
        continue;
      }
      String coordinate = key(slot.substring(4, 6), slot.substring(2, 4));
      CellState next = CellState.from(sequence);
      if ("LOAD".equalsIgnoreCase(workQueue.qType())) {
        next.status = equal(firstLoadTime, sequence.getTimeMove()) ? "load" : "inactive";
        states.putIfAbsent(coordinate, next);
      } else {
        next.status = "discharge";
        CellState previous = states.get(coordinate);
        if (previous != null && isAcrossBay(workQueue)) {
          next.mergeDischarge(previous);
        }
        states.put(coordinate, next);
      }
    }
    return states;
  }

  private void mergeInactive(
      Map<String, CellState> states, List<RobContainer> containers, boolean twenty) {
    for (RobContainer container : containers) {
      if (container.row() == null || container.tier() == null) {
        continue;
      }
      states.computeIfAbsent(key(container.tier(), container.row()), ignored -> {
        CellState state = new CellState();
        state.status = "inactive";
        state.twenty = twenty;
        return state;
      });
    }
  }

  private Set<String> refuelCells(String vesselId, String deckHold, List<String> bays) {
    Set<String> result = new HashSet<>();
    for (VesselColor range :
        vesselColorRepository.findAllByVesselIdAndDeckHoldAndBayIn(vesselId, deckHold, bays)) {
      int rowStart = number(range.getRowStart());
      int rowEnd = number(range.getRowEnd());
      if (range.getTierStart() == null || range.getTierStart().isBlank()
          || range.getTierEnd() == null || range.getTierEnd().isBlank()) {
        for (int row = rowStart; row <= rowEnd; row += 2) {
          result.add("blank" + position(row));
        }
        continue;
      }
      int tierStart = number(range.getTierStart());
      int tierEnd = number(range.getTierEnd());
      for (int row = rowStart; row <= rowEnd; row += 2) {
        for (int tier = tierStart; tier <= tierEnd; tier += 2) {
          result.add(key(position(tier), position(row)));
        }
      }
    }
    return result;
  }

  private String text(CellState state, boolean acrossBay) {
    StringBuilder text = new StringBuilder();
    if (state.oog) {
      text.append('O');
    }
    if (state.powered) {
      text.append('R');
    }
    if (state.tank) {
      text.append('X');
    }
    if (state.quad) {
      text.append('Q');
    }
    if (state.twin) {
      text.append('W');
    }
    if (state.tandem) {
      text.append('T');
    }
    if (acrossBay && state.single && !state.complex && state.bay != null) {
      text.append(state.bay);
    }
    if (state.twenty) {
      text.append("20");
    }
    return text.toString();
  }

  private boolean isAcrossBay(WorkQueueResult workQueue) {
    return workQueue.minBay() != null
        && workQueue.maxBay() != null
        && !workQueue.minBay().equals(workQueue.maxBay());
  }

  private String key(String tier, String row) {
    return tier + row;
  }

  private boolean equal(String left, String right) {
    return left == null ? right == null : left.equals(right);
  }

  private int number(String value) {
    return Integer.parseInt(value);
  }

  private String position(int value) {
    return String.format("%02d", value);
  }

  private static final class CellState {
    private String status;
    private String bay;
    private boolean oog;
    private boolean powered;
    private boolean tank;
    private boolean dg;
    private boolean quad;
    private boolean tandem;
    private boolean twin;
    private boolean single;
    private boolean complex;
    private boolean twenty;

    private static CellState from(SequenceVO sequence) {
      CellState state = new CellState();
      state.bay = sequence.getBay();
      state.oog = sequence.isOog();
      state.powered = sequence.isPowered();
      state.tank = sequence.isTank();
      state.dg = sequence.isDg();
      state.quad = sequence.isQuad();
      state.tandem = sequence.isTandem();
      state.twin = sequence.isTwin();
      state.single = sequence.isSingle();
      return state;
    }

    private void mergeDischarge(CellState previous) {
      complex = previous.complex
          || previous.oog != oog
          || previous.powered != powered
          || previous.tank != tank;
      oog |= previous.oog;
      powered |= previous.powered;
      tank |= previous.tank;
      dg |= previous.dg;
    }
  }
}
