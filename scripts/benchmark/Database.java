import java.sql.DriverManager;

/** Only creates/drops a freshly generated benchmark schema; credentials stay in the environment. */
class Database {
    public static void main(String[] args) throws Exception {
        String action = args[0], name = args[1];
        if (!name.matches("nearby_bench_[a-f0-9]{32}") ||
                !(action.equals("CREATE") || action.equals("DROP"))) {
            throw new IllegalArgumentException("Invalid benchmark schema/action");
        }
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (var connection = DriverManager.getConnection(
                System.getenv("BENCH_MYSQL_URL"), System.getenv("BENCH_MYSQL_USER"),
                System.getenv("BENCH_MYSQL_PASSWORD")); var statement = connection.createStatement()) {
            statement.executeUpdate(action + " DATABASE `" + name + "`" +
                    (action.equals("CREATE") ? " CHARACTER SET utf8mb4" : ""));
            // Diagnostic output cannot turn a successful CREATE into an apparent failed CREATE.
            if (action.equals("CREATE")) {
                try (var result = statement.executeQuery("SELECT VERSION()")) {
                    if (result.next()) System.out.println("MySQL version: " + result.getString(1));
                } catch (java.sql.SQLException ignored) {
                    System.out.println("MySQL version unavailable");
                }
            }
        }
    }
}
