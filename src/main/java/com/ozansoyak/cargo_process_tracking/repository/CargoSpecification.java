package com.ozansoyak.cargo_process_tracking.repository;

import com.ozansoyak.cargo_process_tracking.dto.CargoSearchCriteria;
import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class CargoSpecification {

    public static Specification<Cargo> findByCriteria(CargoSearchCriteria criteria) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Takip Numarası Filtresi (Tam eşleşme)
            if (StringUtils.hasText(criteria.getTrackingNo())) {
                predicates.add(criteriaBuilder.equal(root.get("trackingNumber"), criteria.getTrackingNo().trim()));
            }

            // Müşteri Bilgisi Filtresi (Gönderici Adı, Alıcı Adı veya Telefonlarından birinde arama - case-insensitive)
            if (StringUtils.hasText(criteria.getCustomerInfo())) {
                String likePattern = "%" + criteria.getCustomerInfo().toLowerCase() + "%";
                Predicate senderNameLike = criteriaBuilder.like(criteriaBuilder.lower(root.get("senderName")), likePattern);
                Predicate receiverNameLike = criteriaBuilder.like(criteriaBuilder.lower(root.get("receiverName")), likePattern);
                Predicate senderPhoneLike = criteriaBuilder.like(root.get("senderPhone"), "%" + criteria.getCustomerInfo() + "%");
                Predicate receiverPhoneLike = criteriaBuilder.like(root.get("receiverPhone"), "%" + criteria.getCustomerInfo() + "%");
                predicates.add(criteriaBuilder.or(senderNameLike, receiverNameLike, senderPhoneLike, receiverPhoneLike));
            }

            // Durum Filtresi
            if (StringUtils.hasText(criteria.getStatusFilter())) {
                try {
                    CargoStatus status = CargoStatus.valueOf(criteria.getStatusFilter().toUpperCase());
                    predicates.add(criteriaBuilder.equal(root.get("currentStatus"), status));
                } catch (IllegalArgumentException e) {

                }
            }

            query.orderBy(criteriaBuilder.desc(root.get("id")));
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}