package smartticketing.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

import java.util.regex.Pattern;

/** Hide only routine queue/hold expiry polling SELECTs; keep other SQL and diagnostics. */
public class QueueExpirySqlFilter extends TurboFilter {
    private static final Pattern ALIAS = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\.");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern EXPIRY_QUERY = Pattern.compile(
            "select id,created_at,opportunity_expires_at,queue_number,request_group_id,"
            + "showtime_id,status,updated_at,user_id from waiting_queues(?: [a-zA-Z_][a-zA-Z0-9_]*)?"
            + " where status=\\? and opportunity_expires_at is not null and opportunity_expires_at<=\\?");
    private static final Pattern HOLD_EXPIRY_QUERY = Pattern.compile(
            "select group_id from booking_group_holds(?: [a-zA-Z_][a-zA-Z0-9_]*)?"
            + " where expires_at<=\\? order by expires_at,group_id limit \\?");

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                              Object[] params, Throwable throwable) {
        if (!"org.hibernate.SQL".equals(logger.getName()) || level != Level.DEBUG
                || throwable != null || format == null) return FilterReply.NEUTRAL;
        String sql = WHITESPACE.matcher(ALIAS.matcher(format).replaceAll("")).replaceAll(" ").trim();
        sql = sql.replaceAll("\\s*,\\s*", ",");
        return EXPIRY_QUERY.matcher(sql).matches() || HOLD_EXPIRY_QUERY.matcher(sql).matches()
                ? FilterReply.DENY : FilterReply.NEUTRAL;
    }
}
