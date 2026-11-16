package dev.dinhj.adaptivequote.catalog;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VehicleLookup {
    private final VpicClient vpic;

    public VehicleLookup(VpicClient vpic) {
        this.vpic = vpic;
    }

    public VehicleReference.Match match(VehicleDetails vehicle) {
        return vpic.findExact(vehicle)
                .map(model -> new VehicleReference.Match(VehicleReference.Status.VPIC_EXACT, List.of(model)))
                .orElseGet(() -> VehicleReference.match(vehicle));
    }
}
