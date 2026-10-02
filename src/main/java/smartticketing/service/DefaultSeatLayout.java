package smartticketing.service;

import smartticketing.entity.Screen;
import smartticketing.entity.Seat;
import smartticketing.entity.enums.SeatPosition;

import java.util.ArrayList;
import java.util.List;

/** 선호정보 화면과 동일한 A~J행, 1~12번의 고정 배치. */
final class DefaultSeatLayout {
    static final int SIZE = 120;

    private DefaultSeatLayout() {}

    static List<Seat> create(Screen screen) {
        var seats = new ArrayList<Seat>(SIZE);
        for (int row = 0; row < 10; row++) {
            for (int number = 1; number <= 12; number++) {
                var seat = new Seat();
                seat.setScreen(screen);
                seat.setSeatRow(String.valueOf((char) ('A' + row)));
                seat.setSeatNumber(number);
                seat.setSeatPosition(SeatPosition.valueOf((number >= 4 && number <= 9 ? "MIDDLE" : "SIDE")
                        + "_" + (row < 3 ? "FRONT" : row < 7 ? "MIDDLE" : "REAR")));
                seat.setAdjacencySegment(number <= 3 ? "left" : number <= 9 ? "center" : "right");
                seat.setPositionInSegment(number <= 3 ? number : number <= 9 ? number - 3 : number - 9);
                seats.add(seat);
            }
        }
        return seats;
    }
}
