package com.SmartTicketing.SmartTicketing.dto.user;

import com.SmartTicketing.SmartTicketing.entity.enums.SeatPosition;
import com.SmartTicketing.SmartTicketing.entity.enums.TheaterBrand;
import java.util.List;

public record PreferenceOptionResponse(List<TheaterOption> theaters, List<SeatOption> seats) {
    public record TheaterOption(Long id, String name, TheaterBrand brand, String address) { }
    public record SeatOption(SeatPosition value, String label) { }
}
