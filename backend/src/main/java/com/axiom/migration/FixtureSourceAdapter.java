package com.axiom.migration;

import com.axiom.common.NotFoundException;
import com.axiom.migration.SourceContract.SourceAdapter;
import com.axiom.migration.SourceContract.SourceAttachment;
import com.axiom.migration.SourceContract.SourceField;
import com.axiom.migration.SourceContract.SourceHandshake;
import com.axiom.migration.SourceContract.SourceObject;
import com.axiom.migration.SourceContract.SourceRecord;
import com.axiom.migration.SourceContract.SourceSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A deterministic local source that stands in for a real CRM.
 *
 * <h2>Why this exists rather than a mock in the test folder</h2>
 * Every other adapter in this module needs live vendor credentials nobody has in
 * CI, so without this the migration engine could only ever be exercised against
 * a stub written by the same test that asserts on it. A fixture that is a real
 * {@link SourceAdapter} in the main source set means the dry run, the importer,
 * the reconciler and the rollback all run their production code paths end to
 * end — against a source whose contents are known exactly, so a reconciliation
 * report can be checked rather than merely produced.
 *
 * <h2>The wave</h2>
 * A static file cannot change between two runs, and delta re-sync (FR-MIG-008)
 * is only meaningful if the source moves. {@code wave} on each record is the
 * simulated passage of time: a session with {@code fixtureWave = 1} sees only
 * wave-1 records; advancing the connection to wave 2 reveals the records a real
 * source would have accumulated during the parallel run. It is a test affordance
 * and is labelled as one on the connection API — it exists nowhere in the vendor
 * adapters, where time passes on its own.
 *
 * <h2>Determinism</h2>
 * Files are parsed once and cached. Record order is the file's order. No clock,
 * no randomness, no network: the same fixture produces the same report on every
 * machine, which is what makes the pre-flight report assertable.
 */
@Component
public class FixtureSourceAdapter implements SourceAdapter {

    public static final String VENDOR = "FIXTURE";

    private final ObjectMapper json;
    private final Map<String, Dataset> cache = new ConcurrentHashMap<>();

    public FixtureSourceAdapter(ObjectMapper json) {
        this.json = json;
    }

    // ------------------------------------------------------------------ port

    @Override public String vendor() { return VENDOR; }

    @Override public String displayName() { return "Local fixture source"; }

    @Override public boolean liveInteropAvailable() { return true; }

    @Override public List<String> requestedScopes() { return List.of("fixture:read"); }

    @Override
    public SourceHandshake connect(SourceSession session) {
        Dataset dataset = dataset(session);
        return new SourceHandshake(VENDOR, "READ_ONLY", dataset.connectedAs, objects(session));
    }

    @Override
    public List<SourceObject> objects(SourceSession session) {
        Dataset dataset = dataset(session);
        return dataset.objects.values().stream()
                .map(o -> new SourceObject(o.apiName, o.label, o.custom,
                        visible(o, session, null).size(), o.proposedTarget))
                .sorted(Comparator.comparing(SourceObject::apiName))
                .toList();
    }

    @Override
    public List<SourceField> fields(SourceSession session, String objectApiName) {
        return object(session, objectApiName).fields;
    }

    @Override
    public List<SourceRecord> records(SourceSession session, String objectApiName, Instant modifiedSince) {
        return visible(object(session, objectApiName), session, modifiedSince);
    }

    /**
     * The records the source would show right now, in file order.
     *
     * <p>A record id may appear more than once in the file, at different waves.
     * That is how the fixture expresses "this record was edited in the source
     * during the parallel run": the highest wave at or below the session's wave
     * wins, exactly as a real source shows one current version of a record. Any
     * other rule would let delta re-sync see two versions of the same record and
     * "not duplicating" would become an accident of ordering.
     */
    private List<SourceRecord> visible(FixtureObject object, SourceSession session, Instant modifiedSince) {
        int wave = Math.max(1, session.fixtureWave());
        Map<String, FixtureRecord> current = new LinkedHashMap<>();
        for (FixtureRecord r : object.records) {
            if (r.wave > wave) continue;
            FixtureRecord held = current.get(r.record.sourceId());
            if (held == null || r.wave >= held.wave) current.put(r.record.sourceId(), r);
        }
        List<SourceRecord> out = new ArrayList<>();
        for (FixtureRecord r : current.values()) {
            if (modifiedSince != null && r.record.modifiedAt() != null
                    && !r.record.modifiedAt().isAfter(modifiedSince)) {
                continue;
            }
            out.add(r.record);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers used by the engine

    /**
     * Fields the source itself treats as monetary. Reconciliation sums these
     * (FR-MIG-006) — counts catch missing records, sums catch value corruption.
     */
    public List<String> moneyFields(SourceSession session, String objectApiName) {
        return object(session, objectApiName).moneyFields;
    }

    /** Field name to the source object api name it references. */
    public Map<String, String> references(SourceSession session, String objectApiName) {
        return object(session, objectApiName).references;
    }

    /** Every fixture key on the classpath that this build ships. */
    public static List<String> shippedKeys() {
        return List.of("acme-legacy", "axiom-sample");
    }

    // ------------------------------------------------------------------ parsing

    private FixtureObject object(SourceSession session, String objectApiName) {
        FixtureObject object = dataset(session).objects.get(objectApiName);
        if (object == null) {
            throw new NotFoundException("Source object " + objectApiName + " is not present in fixture "
                    + session.fixtureKey());
        }
        return object;
    }

    private Dataset dataset(SourceSession session) {
        String key = session.fixtureKey();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A fixture connection requires a fixture key");
        }
        return cache.computeIfAbsent(key, this::load);
    }

    private Dataset load(String key) {
        ClassPathResource resource = new ClassPathResource("migration-fixtures/" + key + ".json");
        if (!resource.exists()) {
            throw new NotFoundException("No migration fixture named " + key);
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = json.readTree(in);
            Map<String, FixtureObject> objects = new LinkedHashMap<>();
            for (JsonNode o : root.path("objects")) {
                FixtureObject fo = readObject(o);
                objects.put(fo.apiName, fo);
            }
            return new Dataset(text(root, "label", key), text(root, "connectedAs", "fixture@axiom.internal"), objects);
        } catch (IOException ex) {
            throw new IllegalStateException("Migration fixture " + key + " could not be read", ex);
        }
    }

    private FixtureObject readObject(JsonNode o) {
        String apiName = o.path("apiName").asText();

        List<SourceField> fields = new ArrayList<>();
        for (JsonNode f : o.path("fields")) {
            fields.add(new SourceField(
                    f.path("apiName").asText(),
                    text(f, "label", f.path("apiName").asText()),
                    text(f, "dataType", "TEXT"),
                    f.path("custom").asBoolean(false),
                    f.path("nullable").asBoolean(true),
                    f.hasNonNull("sampleValue") ? f.get("sampleValue").asText() : null));
        }

        Map<String, String> references = new LinkedHashMap<>();
        o.path("references").fields().forEachRemaining(e -> references.put(e.getKey(), e.getValue().asText()));

        List<String> moneyFields = new ArrayList<>();
        o.path("moneyFields").forEach(m -> moneyFields.add(m.asText()));

        List<FixtureRecord> records = new ArrayList<>();
        for (JsonNode r : o.path("records")) {
            Map<String, String> values = new LinkedHashMap<>();
            r.path("values").fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue().asText()));

            List<SourceAttachment> attachments = new ArrayList<>();
            for (JsonNode a : r.path("attachments")) {
                attachments.add(new SourceAttachment(
                        a.path("fileName").asText(),
                        text(a, "contentType", "application/octet-stream"),
                        a.path("byteSize").asLong(0),
                        text(a, "externalRef", null),
                        text(a, "author", null),
                        instant(a, "createdAt")));
            }

            SourceRecord record = new SourceRecord(
                    apiName,
                    r.path("id").asText(),
                    text(r, "label", r.path("id").asText()),
                    values,
                    references,
                    instant(r, "createdAt"),
                    instant(r, "modifiedAt"),
                    text(r, "actor", null),
                    List.copyOf(attachments));
            records.add(new FixtureRecord(Math.max(1, r.path("wave").asInt(1)), record));
        }

        return new FixtureObject(
                apiName,
                text(o, "label", apiName),
                o.path("custom").asBoolean(false),
                text(o, "proposedTarget", null),
                List.copyOf(fields),
                Map.copyOf(references),
                List.copyOf(moneyFields),
                List.copyOf(records));
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? fallback : v.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        String raw = text(node, field, null);
        return raw == null ? null : Instant.parse(raw);
    }

    private record Dataset(String label, String connectedAs, Map<String, FixtureObject> objects) {}

    private record FixtureObject(String apiName, String label, boolean custom, String proposedTarget,
                                 List<SourceField> fields, Map<String, String> references,
                                 List<String> moneyFields, List<FixtureRecord> records) {}

    private record FixtureRecord(int wave, SourceRecord record) {}
}
