package smartticketing.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Connection;
import java.sql.SQLException;

/** Spatial search projection, synchronized with theater coordinates in the same DB transaction. */
@Component("theaterSpatialSchema")
public class TheaterSpatialSchema implements InitializingBean {
    private final JdbcTemplate jdbc;

    // Hibernate must finish creating/updating theaters before the dependent spatial table.
    public TheaterSpatialSchema(JdbcTemplate jdbc, EntityManagerFactory factory) { this.jdbc=jdbc; }

    @Override public void afterPropertiesSet() {
        jdbc.execute((ConnectionCallback<Void>) connection -> { install(connection); return null; });
    }

    public static void install(Connection connection) throws SQLException {
        try (var statement=connection.createStatement()) {
            try (var result=statement.executeQuery("select get_lock(concat('theater-spatial:',md5(database())),30)")) {
                if (!result.next() || result.getInt(1)!=1) throw new SQLException("Cannot acquire theater spatial schema lock");
            }
            try {
                statement.execute("""
                        create table if not exists theater_locations (
                            theater_id bigint not null primary key,
                            location point not null srid 0,
                            spatial index idx_theater_locations_point (location),
                            constraint fk_theater_locations_theater foreign key (theater_id)
                                references theaters(id) on delete cascade
                        ) engine=InnoDB
                        """);
                for (String operation : new String[]{"insert","update"}) {
                    String name="theaters_location_after_"+operation;
                    boolean exists;
                    try (var query=connection.prepareStatement("select count(*) from information_schema.triggers where trigger_schema=database() and trigger_name=?")) {
                        query.setString(1,name);
                        try (var result=query.executeQuery()) { result.next(); exists=result.getInt(1)>0; }
                    }
                    if (!exists) statement.execute("create trigger "+name+" after "+operation+" on theaters for each row " + """
                            begin
                                if NEW.latitude between -90 and 90 and NEW.longitude between -180 and 180 then
                                    insert into theater_locations(theater_id,location)
                                    values (NEW.id,point(if(NEW.longitude=-180,180,NEW.longitude),NEW.latitude))
                                    on duplicate key update location=point(if(NEW.longitude=-180,180,NEW.longitude),NEW.latitude);
                                else
                                    delete from theater_locations where theater_id=NEW.id;
                                end if;
                            end
                            """);
                }
                // Triggers are installed first; INSERT SELECT locks source rows while backfilling.
                statement.executeUpdate("""
                        insert into theater_locations(theater_id,location)
                        select id,point(if(longitude=-180,180,longitude),latitude) from theaters
                        where latitude between -90 and 90 and longitude between -180 and 180
                        on duplicate key update location=values(location)
                        """);
            } finally {
                statement.execute("select release_lock(concat('theater-spatial:',md5(database())))");
            }
        }
    }
}
