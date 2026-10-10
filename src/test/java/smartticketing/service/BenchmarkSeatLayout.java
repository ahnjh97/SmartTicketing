package smartticketing.service;

import smartticketing.entity.Screen;
import smartticketing.entity.Seat;
import java.util.List;

/** Test-only bridge: benchmarks use the production layout without duplicating it. */
public final class BenchmarkSeatLayout {
    public static int size() { return DefaultSeatLayout.SIZE; }
    public static List<Seat> create(Screen screen) { return DefaultSeatLayout.create(screen); }
}
