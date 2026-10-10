package io.fprelay.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PanelTest {
    private static TenantsFile fileAt(Path p) {
        PanelProperties props = new PanelProperties();
        props.setTenantsFile(p.toString());
        return new TenantsFile(props);
    }

    @Test
    @DisplayName("a missing file reads as empty rather than throwing")
    void missingFileIsEmpty(@TempDir Path dir) {
        assertTrue(fileAt(dir.resolve("nope.yml")).readAll().isEmpty());
    }

    @Test
    @DisplayName("upsert writes a tenant the relay's own parser can read back")
    void writeThenRead(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("tenants.yml");
        TenantsFile f = fileAt(p);
        f.upsert("EDGE.Example.COM", Map.of(
                "sgtm", "http://sgtm-tagging:8080",
                "serving_path", "/AbC123xyZ",
                "segment", "Seg0Example",
                "key", "k123",
                "site_origins", List.of("https://example.com")));

        Map<String, Object> all = f.readAll();
        assertTrue(all.containsKey("edge.example.com"), "host must be normalised on write");

        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) all.get("edge.example.com");
        assertEquals("/AbC123xyZ", row.get("serving_path"));
        assertEquals("Seg0Example", row.get("segment"));
        assertEquals(List.of("https://example.com"), row.get("site_origins"));

        String raw = Files.readString(p);
        assertTrue(raw.startsWith("#"), "the file should still explain what it is");
        assertTrue(raw.contains("tenants:"), "top-level key the relay looks for");
    }

    @Test
    @DisplayName("editing one field leaves the others alone")
    void partialUpdatePreservesRest(@TempDir Path dir) {
        TenantsFile f = fileAt(dir.resolve("t.yml"));
        f.upsert("a.example.com", Map.of("segment", "SEG1", "key", "KEY1", "serving_path", "/p"));
        f.upsert("a.example.com", Map.of("segment", "SEG2"));

        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) f.readAll().get("a.example.com");
        assertEquals("SEG2", row.get("segment"), "updated");
        assertEquals("KEY1", row.get("key"), "untouched keys must survive");
        assertEquals("/p", row.get("serving_path"), "untouched keys must survive");
    }

    @Test
    @DisplayName("a blank value removes the key instead of writing an empty string")
    void blankRemoves(@TempDir Path dir) {
        TenantsFile f = fileAt(dir.resolve("t.yml"));
        f.upsert("a.example.com", Map.of("segment", "SEG1", "collect_prefix", "/metrics"));
        Map<String, Object> update = new HashMap<>();
        update.put("collect_prefix", "");
        f.upsert("a.example.com", update);

        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) f.readAll().get("a.example.com");
        assertFalse(row.containsKey("collect_prefix"),
                "an empty prefix must be absent, not present-and-empty");
        assertEquals("SEG1", row.get("segment"));
    }

    @Test
    @DisplayName("adding a second tenant does not disturb the first")
    void multipleTenants(@TempDir Path dir) {
        TenantsFile f = fileAt(dir.resolve("t.yml"));
        f.upsert("a.example.com", Map.of("segment", "A"));
        f.upsert("b.example.com", Map.of("segment", "B"));
        assertEquals(2, f.readAll().size());
        assertTrue(f.delete("a.example.com"));
        assertEquals(1, f.readAll().size());
        assertFalse(f.delete("a.example.com"), "deleting twice reports nothing removed");
    }

    @Test
    @DisplayName("a host is required")
    void hostRequired(@TempDir Path dir) {
        TenantsFile f = fileAt(dir.resolve("t.yml"));
        assertThrows(IllegalArgumentException.class, () -> f.upsert("  ", Map.of("segment", "X")));
    }

    private static Map<String, Object> sample(String startedAt, long loader, long envelope,
                                              long nativ, long delivered) {
        Map<String, Object> m = new HashMap<>();
        m.put("started_at", startedAt);
        m.put("loader", loader);
        m.put("envelope", envelope);
        m.put("native", nativ);
        m.put("delivered", delivered);
        return m;
    }

    @Test
    @DisplayName("the very first sample for a tenant is a baseline, not a delta")
    void firstSampleIsBaseline() {
        assertTrue(StatsPoller.isBaseline(null, "T1", 10, 20, 5, 25));
    }

    @Test
    @DisplayName("normal growth is differenced")
    void growthIsDelta() {
        Map<String, Object> prev = sample("T1", 10, 20, 5, 25);
        assertFalse(StatsPoller.isBaseline(prev, "T1", 12, 26, 5, 31));
    }

    @Test
    @DisplayName("a relay restart re-baselines instead of writing a negative minute")
    void relayRestartRebaselines() {
        Map<String, Object> prev = sample("T1", 10_000, 50_000, 40, 60_000);

        assertTrue(StatsPoller.isBaseline(prev, "T2", 3, 7, 0, 10));

        assertTrue(StatsPoller.isBaseline(prev, "T1", 3, 7, 0, 10));
        assertTrue(StatsPoller.isBaseline(prev, "T1", 10_000, 49_999, 40, 60_000));
    }

    @Test
    @DisplayName("an unchanged sample is still a delta of zero, not a baseline")
    void idleIsZeroDelta() {
        Map<String, Object> prev = sample("T1", 10, 20, 5, 25);
        assertFalse(StatsPoller.isBaseline(prev, "T1", 10, 20, 5, 25));
    }
}
