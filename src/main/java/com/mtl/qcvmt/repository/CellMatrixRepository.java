package com.mtl.qcvmt.repository;

import com.mtl.qcvmt.entity.CellMatrix;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CellMatrixRepository extends JpaRepository<CellMatrix, Integer> {

  List<CellMatrix> findByTypeAndActiveOrderByIdDesc(String type, String active);

  List<CellMatrix> findByTypeAndRowBetweenOrderByRowAsc(String type, String rowStart, String rowEnd);

  List<CellMatrix> findByTypeAndRowBetweenOrderByIdDesc(String type, String rowStart, String rowEnd);
}
