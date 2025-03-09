package com.ozansoyak.cargo_process_tracking.model;

import com.ozansoyak.cargo_process_tracking.model.enums.UserType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "users")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserEntity extends AbstractEntity {

    @Column(unique = true)
    private String email;

    private String password;

    @Enumerated(EnumType.STRING)
    private UserType userType;

    private Boolean isEnabled = false;

}
