package com.ozansoyak.cargo_process_tracking.repository;

import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface CargoRepository extends JpaRepository<Cargo, Long> {

    Optional<Cargo> findByTrackingNumber(String trackingNumber);

    boolean existsByTrackingNumber(String trackingNumber);

    // Belirli bir durumdaki kargo sayısını döndürür
    long countByCurrentStatus(CargoStatus status);

    // Belirtilen durumlardan herhangi birine sahip kargo sayısını döndürür
    long countByCurrentStatusIn(Collection<CargoStatus> statuses);
}