package dev.reconcilekit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "runtime_probe")
class RuntimeProbe {
    @Id private Long id;
    private String value;
    protected RuntimeProbe() {}
}
