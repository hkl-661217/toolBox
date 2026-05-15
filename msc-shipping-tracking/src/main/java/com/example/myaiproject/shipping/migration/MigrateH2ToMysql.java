package com.example.myaiproject.shipping.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;

/**
 * Standalone migrator: reads every row from each H2 table and inserts it
 * verbatim (same ids) into the MySQL target. Run once during the H2 -> MySQL
 * cutover. The Spring Boot fat jar already bundles both drivers.
 *
 * Invocation (from the runbook):
 *   java -cp app.jar -Dloader.main=com.example.myaiproject.shipping.migration.MigrateH2ToMysql \
 *        org.springframework.boot.loader.launch.PropertiesLauncher \
 *        'jdbc:h2:file:/path/to/shipping-tracking;MODE=MySQL;DATABASE_TO_UPPER=false;IFEXISTS=TRUE;ACCESS_MODE_DATA=r' \
 *        'jdbc:mysql://127.0.0.1:3306/msc_shipping_tracking?...' \
 *        msc_shipping_app \
 *        '<password>'
 */
public class MigrateH2ToMysql {

    // FK-safe order: parent before child. notification_account is independent.
    private static final String[] TABLES = {
            "shipping_tracking_binding",
            "shipping_tracking_snapshot",
            "shipping_tracking_change_log",
            "shipping_tracking_notification_account",
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: MigrateH2ToMysql <h2-url> <mysql-url> <mysql-user> <mysql-pass>");
            System.exit(2);
        }
        String h2Url = args[0];
        String mysqlUrl = args[1];
        String mysqlUser = args[2];
        String mysqlPass = args[3];

        Class.forName("org.h2.Driver");
        Class.forName("com.mysql.cj.jdbc.Driver");

        try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "");
             Connection mysql = DriverManager.getConnection(mysqlUrl, mysqlUser, mysqlPass)) {

            try (Statement s = mysql.createStatement()) {
                s.execute("SET foreign_key_checks = 0");
            }

            int total = 0;
            for (String table : TABLES) {
                total += copyTable(h2, mysql, table);
            }

            try (Statement s = mysql.createStatement()) {
                s.execute("SET foreign_key_checks = 1");
            }

            for (String table : TABLES) {
                resetAutoIncrement(mysql, table);
            }

            System.out.printf("Migration done. Copied %d row(s) across %d tables.%n", total, TABLES.length);
        }
    }

    private static int copyTable(Connection h2, Connection mysql, String table) throws Exception {
        String[] columns = readColumnNames(h2, table);
        String columnList = String.join(", ", columns);
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.length, "?"));
        String insertSql = "insert into " + table + " (" + columnList + ") values (" + placeholders + ")";

        int rows = 0;
        try (Statement read = h2.createStatement();
             ResultSet rs = read.executeQuery("select " + columnList + " from " + table);
             PreparedStatement insert = mysql.prepareStatement(insertSql)) {

            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                for (int i = 1; i <= columns.length; i++) {
                    bindValue(insert, i, rs, i, meta.getColumnType(i));
                }
                insert.addBatch();
                rows++;
                if (rows % 200 == 0) {
                    insert.executeBatch();
                }
            }
            insert.executeBatch();
        }
        System.out.printf("  %s: %d row(s)%n", table, rows);
        return rows;
    }

    private static String[] readColumnNames(Connection h2, String table) throws Exception {
        try (Statement s = h2.createStatement();
             ResultSet rs = s.executeQuery("select * from " + table + " where 1 = 0")) {
            ResultSetMetaData m = rs.getMetaData();
            String[] cols = new String[m.getColumnCount()];
            for (int i = 0; i < cols.length; i++) {
                cols[i] = m.getColumnName(i + 1).toLowerCase();
            }
            return cols;
        }
    }

    private static void bindValue(PreparedStatement ps, int psIdx,
                                  ResultSet rs, int rsIdx, int sqlType) throws Exception {
        switch (sqlType) {
            case Types.TIMESTAMP_WITH_TIMEZONE:
            case Types.TIMESTAMP: {
                OffsetDateTime odt = rs.getObject(rsIdx, OffsetDateTime.class);
                if (odt == null) {
                    ps.setNull(psIdx, Types.TIMESTAMP);
                } else {
                    // MySQL DATETIME(6) stores no offset — write as local instant.
                    ps.setObject(psIdx, odt.toLocalDateTime());
                }
                break;
            }
            case Types.CLOB:
            case Types.NCLOB:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR: {
                String text = rs.getString(rsIdx);
                if (text == null) {
                    ps.setNull(psIdx, Types.LONGVARCHAR);
                } else {
                    ps.setString(psIdx, text);
                }
                break;
            }
            case Types.BOOLEAN:
            case Types.BIT: {
                boolean v = rs.getBoolean(rsIdx);
                if (rs.wasNull()) {
                    ps.setNull(psIdx, Types.BOOLEAN);
                } else {
                    ps.setBoolean(psIdx, v);
                }
                break;
            }
            default: {
                Object v = rs.getObject(rsIdx);
                if (v == null) {
                    ps.setNull(psIdx, sqlType);
                } else {
                    ps.setObject(psIdx, v);
                }
            }
        }
    }

    private static void resetAutoIncrement(Connection mysql, String table) throws Exception {
        long next;
        try (Statement s = mysql.createStatement();
             ResultSet rs = s.executeQuery("select coalesce(max(id), 0) + 1 from " + table)) {
            rs.next();
            next = rs.getLong(1);
        }
        try (Statement s = mysql.createStatement()) {
            s.execute("alter table " + table + " auto_increment = " + next);
        }
        System.out.printf("  %s: auto_increment -> %d%n", table, next);
    }
}
