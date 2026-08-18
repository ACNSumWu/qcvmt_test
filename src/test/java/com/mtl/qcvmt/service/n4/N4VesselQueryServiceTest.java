package com.mtl.qcvmt.service.n4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mtl.qcvmt.dto.response.BayCellResponse;
import com.mtl.qcvmt.entity.CellMatrix;
import com.mtl.qcvmt.entity.Vessel;
import com.mtl.qcvmt.n4.N4QueryRepository;
import com.mtl.qcvmt.repository.CellMatrixRepository;
import com.mtl.qcvmt.repository.VesselRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class N4VesselQueryServiceTest {

  @Mock
  private N4QueryRepository n4QueryRepository;
  @Mock
  private CellMatrixRepository cellMatrixRepository;
  @Mock
  private VesselRepository vesselRepository;

  @Test
  void expandsConfiguredBayIntoEveryPhysicalRowAndTierCell() {
    Vessel vessel = new Vessel(1, "VESSEL-1", "A", "17", "01", "05", "82", "86", 0);
    when(vesselRepository.findByVesselIdAndDeckHoldAndBay("VESSEL-1", "A", "17"))
        .thenReturn(Optional.of(vessel));
    when(cellMatrixRepository.findByTypeAndRowBetweenOrderByIdDesc("A", "01", "05"))
        .thenReturn(List.of(
            new CellMatrix(3, "A", "05", "11", null, null, "1"),
            new CellMatrix(2, "A", "03", "11", null, null, "1"),
            new CellMatrix(1, "A", "01", "11", null, null, "1")));

    N4VesselQueryService service =
        new N4VesselQueryService(n4QueryRepository, cellMatrixRepository, vesselRepository);

    List<BayCellResponse> cells = service.getBayCells("VESSEL-1", "17", "A");

    assertThat(cells).containsExactly(
        BayCellResponse.empty("05", "86"),
        BayCellResponse.empty("05", "84"),
        BayCellResponse.empty("05", "82"),
        BayCellResponse.empty("03", "86"),
        BayCellResponse.empty("03", "84"),
        BayCellResponse.empty("03", "82"),
        BayCellResponse.empty("01", "86"),
        BayCellResponse.empty("01", "84"),
        BayCellResponse.empty("01", "82"));
  }

  @Test
  void matchesZeroPaddedN4BayToUnpaddedVesselConfiguration() {
    Vessel vessel = new Vessel(1, "OOCL ASIA", "A", "1", "0", "10", "82", "88", 0);
    when(vesselRepository.findByVesselIdAndDeckHoldAndBay("OOCL ASIA", "A", "01"))
        .thenReturn(Optional.empty());
    when(vesselRepository.findByVesselIdAndDeckHoldAndBay("OOCL ASIA", "A", "1"))
        .thenReturn(Optional.of(vessel));
    when(cellMatrixRepository.findByTypeAndRowBetweenOrderByIdDesc("A", "00", "10"))
        .thenReturn(List.of(
            new CellMatrix(2, "A", "01", "11", null, null, "1"),
            new CellMatrix(1, "A", "00", "11", null, null, "1")));

    N4VesselQueryService service =
        new N4VesselQueryService(n4QueryRepository, cellMatrixRepository, vesselRepository);

    assertThat(service.getBayCells("OOCL ASIA", "01", "A"))
        .contains(
            BayCellResponse.empty("01", "84"),
            BayCellResponse.empty("01", "82"))
        .doesNotContain(BayCellResponse.empty("01", "11"));
  }

  @Test
  void usesPreviousOddBayConfigurationForAnEvenWorkingBay() {
    Vessel vessel = new Vessel(1, "VESSEL-1", "A", "17", "00", "02", "82", "84", 0);
    when(vesselRepository.findByVesselIdAndDeckHoldAndBay("VESSEL-1", "A", "18"))
        .thenReturn(Optional.empty());
    when(vesselRepository.findByVesselIdAndDeckHoldAndBay("VESSEL-1", "A", "17"))
        .thenReturn(Optional.of(vessel));
    when(cellMatrixRepository.findByTypeAndRowBetweenOrderByIdDesc("A", "00", "02"))
        .thenReturn(List.of(
            new CellMatrix(2, "A", "02", "11", null, null, "1"),
            new CellMatrix(1, "A", "00", "11", null, null, "1")));

    N4VesselQueryService service =
        new N4VesselQueryService(n4QueryRepository, cellMatrixRepository, vesselRepository);

    assertThat(service.getBayCells("VESSEL-1", "18", "A"))
        .extracting(BayCellResponse::tier)
        .containsExactly("84", "82", "84", "82");
  }

  @Test
  void fallsBackToActiveDeckMatrixWhenVesselBayConfigurationIsMissing() {
    when(vesselRepository.findByVesselIdAndDeckHoldAndBay("VESSEL-1", "A", "17"))
        .thenReturn(Optional.empty());
    when(cellMatrixRepository.findByTypeAndActiveOrderByIdDesc("A", "1"))
        .thenReturn(List.of(
            new CellMatrix(3, "A", "05", "84", null, null, "1"),
            new CellMatrix(2, "A", "03", "84", null, null, "1"),
            new CellMatrix(1, "A", "01", "84", null, null, "1")));

    N4VesselQueryService service =
        new N4VesselQueryService(n4QueryRepository, cellMatrixRepository, vesselRepository);

    assertThat(service.getBayCells("VESSEL-1", "17", "A"))
        .hasSize(3 * 43)
        .contains(
            BayCellResponse.empty("05", "84"),
            BayCellResponse.empty("03", "42"),
            BayCellResponse.empty("01", "00"));
  }
}
