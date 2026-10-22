package org.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** SQLite storage for alert rules and the trigger log (file: ~/.argus/argus.db). */
public class Db {
    private final String url;

    public Db() throws SQLException {
        Path dir = Path.of(System.getProperty("user.home"), ".argus");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new SQLException("Cannot create " + dir, e);
        }
        url = "jdbc:sqlite:" + dir.resolve("argus.db");
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS rules(id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "metric TEXT, op TEXT, threshold REAL, seconds INTEGER, enabled INTEGER)");
            s.execute("CREATE TABLE IF NOT EXISTS logs(id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, message TEXT)");
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM rules");
            rs.next();
            if (rs.getInt(1) == 0) {
                s.execute("INSERT INTO rules(metric,op,threshold,seconds,enabled) VALUES "
                        + "('CPU','>',90,10,1),('MEMORY','>',85,30,1),('DISK','>',90,60,0),('BATTERY','<',20,5,1)");
            }
        }
    }

    public List<Rule> rules() {
        List<Rule> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id,metric,op,threshold,seconds,enabled FROM rules ORDER BY id")) {
            while (rs.next()) {
                out.add(new Rule(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getDouble(4), rs.getInt(5), rs.getInt(6) == 1));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    public void addRule(String metric, String op, double threshold, int seconds) {
        run("INSERT INTO rules(metric,op,threshold,seconds,enabled) VALUES (?,?,?,?,1)",
                metric, op, threshold, seconds);
    }

    public void deleteRule(int id) {
        run("DELETE FROM rules WHERE id=?", id);
    }

    public void setEnabled(int id, boolean enabled) {
        run("UPDATE rules SET enabled=? WHERE id=?", enabled ? 1 : 0, id);
    }

    public void addLog(String message) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        run("INSERT INTO logs(ts,message) VALUES (?,?)", ts, message);
    }

    public List<String> recentLogs(int n) {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("SELECT ts,message FROM logs ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, n);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(rs.getString(1) + "   " + rs.getString(2));
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    private void run(String sql, Object... args) {
        try (Connection c = DriverManager.getConnection(url); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}