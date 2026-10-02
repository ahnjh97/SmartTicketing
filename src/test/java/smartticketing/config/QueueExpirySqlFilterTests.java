package smartticketing.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueueExpirySqlFilterTests {
    private static final String QUERY = """
            select
                wq1_0.id, wq1_0.created_at, wq1_0.opportunity_expires_at,
                wq1_0.queue_number, wq1_0.request_group_id, wq1_0.showtime_id,
                wq1_0.status, wq1_0.updated_at, wq1_0.user_id
            from waiting_queues wq1_0
            where wq1_0.status=?
                and wq1_0.opportunity_expires_at is not null
                and wq1_0.opportunity_expires_at<=?
            """;

    @Test
    void configuredFilterDropsOnlyExpiryPollingAndPreservesSqlAndErrors() throws Exception {
        var context = new LoggerContext();
        try {
            var configuration = new JoranConfigurator();
            configuration.setContext(context);
            configuration.doConfigure(getClass().getResource("/logback-spring.xml"));
            assertThat(context.getTurboFilterList()).anyMatch(QueueExpirySqlFilter.class::isInstance);
            var logger = context.getLogger("org.hibernate.SQL");
            logger.setLevel(Level.DEBUG);
            logger.setAdditive(false);
            var output = new ListAppender<ILoggingEvent>();
            output.setContext(context); output.start(); logger.addAppender(output);

            logger.debug(QUERY);
            logger.debug(QUERY.replace("wq1_0", "q2_0"));
            String holdQuery = """
                    select bgh1_0.group_id
                    from booking_group_holds bgh1_0
                    where bgh1_0.expires_at<=?
                    order by bgh1_0.expires_at, bgh1_0.group_id
                    limit ?
                    """;
            logger.debug(holdQuery);
            logger.debug(holdQuery.replace("bgh1_0", "h2_0"));
            assertThat(output.list).isEmpty();

            logger.debug("select * from users where id=?");
            logger.debug("update waiting_queues set status=? where id=?");
            logger.debug("select * from waiting_queues where user_id=?");
            logger.debug(QUERY + " and user_id=?");
            logger.error(QUERY);
            logger.debug(QUERY, new IllegalStateException("diagnostic"));
            assertThat(output.list).hasSize(6);
            logger.debug("delete from booking_group_holds where group_id=?");
            logger.debug("select * from booking_group_holds where group_id=? for update");
            logger.debug(holdQuery + " for update");
            logger.error(holdQuery);
            logger.debug(holdQuery, new IllegalStateException("hold diagnostic"));
            assertThat(output.list).hasSize(11);

            var application = context.getLogger("smartticketing.service.WaitingQueueService");
            application.setLevel(Level.DEBUG); application.setAdditive(false); application.addAppender(output);
            application.debug(QUERY);
            assertThat(output.list).hasSize(12);
        } finally { context.stop(); }
    }
}
