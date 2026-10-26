package org.tool.storage;

import org.tool.model.AlertEvent;
import org.tool.model.AlertRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Local SQLite storage for alert rules, the event log and user settings (file: ~/.argus/argus.db).
 * Rule changes are written in one transaction, so a crash can never leave a half-saved rule set.
 */
public class SettingsRepository {
    private final String url;

    public SettingsRepository() throws SQLException {
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
            s.execute("CREATE TABLE IF NOT EXISTS settings(name TEXT PRIMARY KEY, val TEXT)");
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM rules");
            rs.next();
            if (rs.getInt(1) == 0) {
                s.execute("INSERT INTO rules(metric,op,threshold,seconds,enabled) VALUES "
                        + "('CPU','>',90,10,1),('MEMORY','>',85,30,1),('DISK','>',90,60,0),('BATTERY','<',20,5,1)");
            }
        }
    }

    // ---------------------------------------------------------------- alert rules

    public List<AlertRule> loadRules() {
        List<AlertRule> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id,metric,op,threshold,seconds,enabled FROM rules ORDER BY id")) {
            while (rs.next()) {
                out.add(new AlertRule(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getDouble(4), rs.getInt(5), rs.getInt(6) == 1));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    /**
     * Replaces the stored rules with exactly this list, all or nothing (one transaction).
     * A rule with id 0 is new and gets the next free id.
     */
    public void saveRules(List<AlertRule> rules) {
        try (Connection c = DriverManager.getConnection(url)) {
            c.setAutoCommit(false);
            try (Statement clear = c.createStatement();
                 PreparedStatement withId = c.prepareStatement(
                         "INSERT INTO rules(id,metric,op,threshold,seconds,enabled) VALUES (?,?,?,?,?,?)");
                 PreparedStatement fresh = c.prepareStatement(
                         "INSERT INTO rules(metric,op,threshold,seconds,enabled) VALUES (?,?,?,?,?)")) {
                clear.executeUpdate("DELETE FROM rules");
                for (AlertRule r : rules) {
                    PreparedStatement ps = r.id() > 0 ? withId : fresh;
                    int i = 1;
                    if (r.id() > 0) ps.setInt(i++, r.id());
                    ps.setString(i++, r.metric());
                    ps.setString(i++, r.op());
                    ps.setDouble(i++, r.threshold());
                    ps.setInt(i++, r.seconds());
                    ps.setInt(i, r.enabled() ? 1 : 0);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ---------------------------------------------------------------- event log

    public void saveEvent(AlertEvent event) {
        run("INSERT INTO logs(ts,message) VALUES (?,?)", event.timestamp(), event.message());
    }

    /** The newest events first. */
    public List<AlertEvent> recentEvents(int n) {
        List<AlertEvent> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("SELECT ts,message FROM logs ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, n);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(new AlertEvent(rs.getString(1), rs.getString(2)));
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    // ---------------------------------------------------------------- settings

    /** Reads a saved setting, or `fallback` if it was never saved. */
    public String getSetting(String name, String fallback) {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("SELECT val FROM settings WHERE name=?")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString(1);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return fallback;
    }

    public void setSetting(String name, String value) {
        run("INSERT OR REPLACE INTO settings(name,val) VALUES (?,?)", name, value);
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
