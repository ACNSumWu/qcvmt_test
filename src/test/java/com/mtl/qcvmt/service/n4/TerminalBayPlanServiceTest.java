package com.mtl.qcvmt.service.n4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mtl.qcvmt.dto.response.BayCellResponse;
import com.mtl.qcvmt.dto.response.RobContainer;
import com.mtl.qcvmt.dto.response.WorkQueueResult;
import com.mtl.qcvmt.entity.SequenceVO;
import com.mtl.qcvmt.entity.VesselColor;
import com.mtl.qcvmt.repository.VesselColorRepository;
import com.mtl.qcvmt.repository.VesselRefuelRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TerminalBayPlanServiceTest {

  @Mock
  private VesselColorRepository vesselColorRepository;
  @Mock
  private VesselRefuelRepository vesselRefuelRepository;

  @Test
  void appliesLegacyStatusesTextAndPrecedenceToTheCompleteLayout() {
    SequenceVO currentLoad = sequence("170182", "2026-08-14 10:00:00");
    currentLoad.setQtype("LOAD");
    currentLoad.setOog(true);
    currentLoad.setPowered(true);
    currentLoad.setTwin(true);

    SequenceVO oldLoad = sequence("170384", "2026-08-14 09:59:00");
    oldLoad.setQtype("LOAD");
    oldLoad.setTank(true);

    WorkQueueResult queue =
        new WorkQueueResult("LOAD", "1", "VISIT-1", "17", "17", "A", List.of(currentLoad, oldLoad));
    List<BayCellResponse> layout = List.of(
        BayCellResponse.empty("01", "82"),
        BayCellResponse.empty("03", "84"),
        BayCellResponse.empty("05", "86"));
    List<RobContainer> rob = List.of(new RobContainer("17", "05", "86", "ROB-1", null));

    TerminalBayPlanService service =
        new TerminalBayPlanService(vesselRefuelRepository, vesselColorRepository);

    List<BayCellResponse> result =
        service.render(layout, queue, rob, List.of(), "VESSEL-1", "A", List.of("17"));

    assertThat(result).containsExactly(
        new BayCellResponse("01", "82", "1", "load", "ORW", false, true),
        new BayCellResponse("03", "84", "1", "inactive", "X", false, false),
        new BayCellResponse("05", "86", "1", "inactive", "", false, false));
  }

  @Test
  void refuelOverridesWorkAndTwentyUsesLegacyText() {
    SequenceVO discharge = sequence("170182", null);
    discharge.setQtype("DISCH");
    WorkQueueResult queue =
        new WorkQueueResult("DISCH", "1", "VISIT-1", "18", "18", "A", List.of(discharge));
    VesselColor range = new VesselColor(1, "VESSEL-1", "A", "18", "01", "01", "82", "82", 0);
    when(vesselRefuelRepository.existsByVesselIdAndIsRefuel("VESSEL-1", "Yes")).thenReturn(true);
    when(vesselColorRepository.findAllByVesselIdAndDeckHoldAndBayIn(
        "VESSEL-1", "A", List.of("18"))).thenReturn(List.of(range));

    TerminalBayPlanService service =
        new TerminalBayPlanService(vesselRefuelRepository, vesselColorRepository);
    List<BayCellResponse> result = service.render(
        List.of(BayCellResponse.empty("01", "82"), BayCellResponse.empty("03", "82")),
        queue,
        List.of(),
        List.of(new RobContainer("17", "03", "82", "TWENTY", null)),
        "VESSEL-1",
        "A",
        List.of("18"));

    assertThat(result).containsExactly(
        new BayCellResponse("01", "82", "1", "refuel", "", false, false),
        new BayCellResponse("03", "82", "1", "twenty", "20", false, false));
  }

  private SequenceVO sequence(String slot, String timeMove) {
    SequenceVO sequence = new SequenceVO();
    sequence.setCurrentPosSlot(slot);
    sequence.setBay(slot.substring(0, 2));
    sequence.setTimeMove(timeMove);
    sequence.setSingle(true);
    return sequence;
  }
}
