package com.mtl.qcvmt.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SequenceVO {

  private String currentPosSlot;
  private String plannedPosSlot;
  private String qtype;
  private String qdeck;
  private String qrow;
  private String status;
  private String bay;
  private String timeMove;
  private boolean isOog;
  private boolean isPowered;
  private boolean isTank;
  private boolean isDg;
  private boolean isQuad;
  private boolean isTandem;
  private boolean isTwin;
  private boolean isSingle;
}
