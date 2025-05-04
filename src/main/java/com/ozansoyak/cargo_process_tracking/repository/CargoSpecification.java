package com.ozansoyak.cargo_process_tracking.repository; // veya repository.specification

import com.ozansoyak.cargo_process_tracking.dto.CargoSearchCriteria;
import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils; // StringUtils ekledik

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
                Predicate senderPhoneLike = criteriaBuilder.like(root.get("senderPhone"), "%" + criteria.getCustomerInfo() + "%"); // Telefon exact match veya like olabilir
                Predicate receiverPhoneLike = criteriaBuilder.like(root.get("receiverPhone"), "%" + criteria.getCustomerInfo() + "%");
                predicates.add(criteriaBuilder.or(senderNameLike, receiverNameLike, senderPhoneLike, receiverPhoneLike));
            }

            // Durum Filtresi
            if (StringUtils.hasText(criteria.getStatusFilter())) {
                try {
                    // Gelen string'i Enum'a çevirmeye çalış
                    CargoStatus status = CargoStatus.valueOf(criteria.getStatusFilter().toUpperCase());
                    predicates.add(criteriaBuilder.equal(root.get("currentStatus"), status));
                } catch (IllegalArgumentException e) {
                    // Geçersiz bir durum adı gelirse filtreleme yapma veya hata yönetimi eklenebilir
                    // log.warn("Geçersiz durum filtresi: {}", criteria.getStatusFilter());
                }
            }

            // Tarih Filtresi (Şimdilik sadece oluşturma tarihine göre > o gün başlangıcı şeklinde)
            // Gerçek uygulamada createdAt veya lastUpdatedAt gibi bir alan olmalı
            // if (criteria.getDate() != null) {
            //     LocalDateTime startOfDay = criteria.getDate().atStartOfDay();
            //     LocalDateTime endOfDay = startOfDay.plusDays(1);
            //     // Varsayılan bir tarih alanı 'createdAt' olduğunu varsayalım
            //     predicates.add(criteriaBuilder.between(root.get("createdAt"), startOfDay, endOfDay));
            // }

            // Sorguyu oluştur
            query.orderBy(criteriaBuilder.desc(root.get("id"))); // Veya başka bir alana göre sırala (lastUpdatedAt?)
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}