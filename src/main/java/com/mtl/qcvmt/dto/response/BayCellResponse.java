package com.mtl.qcvmt.dto.response;

public record BayCellResponse(
    String row,
    String tier,
    String active,
    String status,
    String text,
    boolean dg,
    boolean rowHighlighted) {

  public static BayCellResponse empty(String row, String tier) {
    return new BayCellResponse(row, tier, "1", "empty", "", false, false);
  }
}
