import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Reads only the Seoul theater catalog. Never creates, updates or drops source data. */
class Snapshot {
    private static String csv(String value) {
        return "\"" + (value == null ? "" : value).replace("\"", "\"\"") + "\"";
    }

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (var connection = DriverManager.getConnection(
                System.getenv("BENCH_SOURCE_DB_URL"), System.getenv("BENCH_SOURCE_DB_USERNAME"),
                System.getenv("BENCH_SOURCE_DB_PASSWORD"))) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                    SELECT kakao_place_id, name, brand, address, latitude, longitude
                    FROM theaters
                    WHERE is_active = 1 AND latitude IS NOT NULL AND longitude IS NOT NULL
                      AND address LIKE '서울%'
                    ORDER BY kakao_place_id
                    """)) {
                statement.setQueryTimeout(30);
                try (var rows = statement.executeQuery();
                     var writer = Files.newBufferedWriter(Path.of(args[0]), StandardCharsets.UTF_8)) {
                    writer.write("id,name,brand,address,latitude,longitude\n");
                    while (rows.next()) {
                        for (int i = 1; i <= 6; i++) {
                            if (i > 1) writer.write(",");
                            writer.write(csv(rows.getString(i)));
                        }
                        writer.write("\n");
                    }
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
