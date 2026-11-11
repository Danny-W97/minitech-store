package org.demo.deliveryservice.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pickup_locations")
public class PickupLocation {
    @Id
    @Column(length = 40)
    private String code;
    @Column(nullable = false, length = 80)
    private String name;
    @Column(nullable = false)
    private boolean active;

    protected PickupLocation() {}
    public PickupLocation(String code, String name, boolean active) {
        this.code = code;
        this.name = name;
        this.active = active;
    }
    public String getCode() { return code; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
}
