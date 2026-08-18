package com.mtl.qcvmt.dto.response;

import com.mtl.qcvmt.dto.colorset.ColorSetResponse;
import com.mtl.qcvmt.dto.vessel.VesselResponse;
import java.util.List;

public record TerminalView(List<VesselResponse>vessels,WorkQueueResult workQueue,List<ColorSetResponse>colorSets,List<RobContainer>robContainers,List<BayCellResponse>cells,String vesselId,String bay,String deckHold,String qType,int remainContainers,int sequenceCount,int dgCount,int poweredCount,int tankCount,int oogCount,int twinCount,int tandemCount,int quadCount,boolean refueling,String queueStatus){}
