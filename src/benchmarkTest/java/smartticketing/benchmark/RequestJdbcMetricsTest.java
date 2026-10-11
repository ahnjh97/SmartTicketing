package smartticketing.benchmark;

import org.junit.jupiter.api.*;
import java.sql.*;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestJdbcMetricsTest {
    @AfterEach void clear() { RequestJdbcMetrics.CURRENT.remove(); }

    @Test void countsOnlyRequestThreadAndRowsActuallyConsumed() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, false);
        var measured = RequestJdbcMetrics.wrap(source, DataSource.class, null);
        // Background execution has no request context and must not leak into later requests.
        measured.getConnection().prepareStatement("select * from showtime_seats").executeQuery();
        var counts = new RequestJdbcMetrics.Counts(); RequestJdbcMetrics.CURRENT.set(counts);
        var result = measured.getConnection().prepareStatement("select * from showtime_seats").executeQuery();
        while(result.next()) { }
        assertEquals(1, counts.statements); assertEquals(2, counts.rows); assertEquals(2, counts.inventoryRows);
        var thread = new Thread(() -> { assertNull(RequestJdbcMetrics.CURRENT.get()); });
        thread.start(); thread.join();
    }

    @Test void rankClassifierRecognizesBothVersionsButNotQueueListing() {
        assertTrue(RequestJdbcMetrics.rankQuery("select q.* from waiting_queues q where coalesce(q.zone_queue_number,q.queue_number)<?"));
        assertTrue(RequestJdbcMetrics.rankQuery("select q.id,count(a.id) from waiting_queues q left join waiting_queues a on coalesce(a.zone_queue_number,a.queue_number)<coalesce(q.zone_queue_number,q.queue_number)"));
        assertFalse(RequestJdbcMetrics.rankQuery("select * from waiting_queues where request_group_id=?"));
    }

    @Test void preservesSqlExceptionAndCountsFailedAttempt() throws Exception {
        var statement = mock(PreparedStatement.class);
        when(statement.executeQuery()).thenThrow(new SQLException("expected"));
        var counts = new RequestJdbcMetrics.Counts(); RequestJdbcMetrics.CURRENT.set(counts);
        var measured = RequestJdbcMetrics.wrap(statement, PreparedStatement.class, "select 1");
        assertThrows(SQLException.class, measured::executeQuery);
        assertEquals(1, counts.statements);
        assertTrue(measured.isWrapperFor(PreparedStatement.class));
        assertSame(measured, measured.unwrap(PreparedStatement.class));
    }

    @Test void filterEmitsCountersAndCleansContext() throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/showtimes");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        new RequestJdbcMetrics.RequestFilter().doFilter(request, response, (req, res) -> {
            RequestJdbcMetrics.CURRENT.get().statements = 3;
            res.getWriter().write("{\"ok\":true}");
        });
        assertEquals("3", response.getHeader("X-Bench-Sql"));
        assertEquals("jdbc-request-v1", response.getHeader("X-Bench-Instrumentation"));
        assertEquals("{\"ok\":true}", response.getContentAsString());
        assertNull(RequestJdbcMetrics.CURRENT.get());
    }
}
