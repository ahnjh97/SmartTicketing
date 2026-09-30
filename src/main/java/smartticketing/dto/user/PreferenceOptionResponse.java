package smartticketing.dto.user;

import smartticketing.entity.enums.SeatPosition;
import smartticketing.entity.enums.TheaterBrand;

import java.util.List;

public record PreferenceOptionResponse(List<TheaterOption> theaters, List<SeatOption> seats) {
    public record TheaterOption(Long id, String name, TheaterBrand brand, String address) {
    }

    public record SeatOption(SeatPosition value, String label) {
    }
}
