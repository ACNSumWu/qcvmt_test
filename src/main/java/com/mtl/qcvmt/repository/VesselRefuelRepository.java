package com.mtl.qcvmt.repository;

import com.mtl.qcvmt.entity.VesselRefuel;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VesselRefuelRepository extends JpaRepository<VesselRefuel, Integer> {

  Optional<VesselRefuel> findByVesselId(String vesselId);

  boolean existsByVesselIdAndIsRefuel(String vesselId, String isRefuel);
}
