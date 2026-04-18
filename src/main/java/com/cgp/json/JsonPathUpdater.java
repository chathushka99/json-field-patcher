package com.cgp.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ultra-low-latency JSON field updater for fintech workloads.
 *
 * <p>Design goals:
 * <ul>
 *     <li>Single parse / single serialization</li>
 *     <li>In-place JSON tree mutation</li>
 *     <li>Compiled path caching for repeated operations</li>
 *     <li>Iterative navigation (no recursion)</li>
 *     <li>Thread-safe ObjectMapper reuse</li>
 * </ul>
 *
 * <p>Typical usage scenarios:
 * <ul>
 *     <li>Payment message enrichment</li>
 *     <li>JSON gateway field patching</li>
 *     <li>Risk model parameter injection</li>
 *     <li>Compliance record updates</li>
 * </ul>
 */
public final class JsonPathUpdater {

    public static final int FIELD_TYPE_ARRAY = 1;
    public static final int FIELD_TYPE_SCALAR = 2;
    public static final int FIELD_TYPE_ARRAY2 = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ConcurrentHashMap<String, PathSegment[]> PATH_CACHE = new ConcurrentHashMap<>(256);

    private JsonPathUpdater() {
        // noop
    }

    /**
     * Updates a field inside a JSON document.
     *
     * @param jsonString JSON document
     * @param path       dot-notation path (ex: ch[0].ch_bd.$date)
     * @param values     new values
     * @param fieldType  scalar or array
     * @return updated JSON document
     */
    public static String updateField(final String jsonString, final String path, final List<String> values, final int fieldType) throws JsonProcessingException {
        if (jsonString == null || path == null || values == null) {
            throw new IllegalArgumentException("jsonString, path and values must not be null");
        }

        final ObjectNode root = (ObjectNode) MAPPER.readTree(jsonString);
        final PathSegment[] segments = compilePath(path);
        final ObjectNode parent = navigateToParent(root, segments);
        final PathSegment leaf = segments[segments.length - 1];

        writeLeaf(parent, leaf, values, fieldType);

        return MAPPER.writeValueAsString(root);
    }

    /**
     * Reads a field from JSON using a dot-notation path.
     *
     * @throws JsonProcessingException if the JSON string is invalid
     */
    public static List<String> readField(final String jsonString, final String path) throws JsonProcessingException {
        if (jsonString == null || path == null) {
            return Collections.emptyList();
        }

        final JsonNode root = MAPPER.readTree(jsonString);
        final PathSegment[] segments = compilePath(path);
        final JsonNode node = navigateToNode(root, segments);

        return extractValues(node);
    }

    // path compilation //

    static PathSegment[] compilePath(final String path) {
        return PATH_CACHE.computeIfAbsent(path, JsonPathUpdater::parsePath);
    }

    private static PathSegment[] parsePath(final String path) {
        final String[] parts = path.split("\\.");
        final PathSegment[] segments = new PathSegment[parts.length];

        for (int i = 0; i < parts.length; i++) {
            final String part = parts[i];
            final int open = part.indexOf('[');
            final int close = part.indexOf(']');

            if (open >= 0 && close > open) {
                final String field = part.substring(0, open);
                final int index = Integer.parseInt(part.substring(open + 1, close));
                segments[i] = new PathSegment(field, index);
            } else {
                segments[i] = new PathSegment(part, -1);
            }
        }

        return segments;
    }

    private static ObjectNode navigateToParent(final ObjectNode root, final PathSegment[] segments) {
        ObjectNode current = root;

        for (int i = 0; i < segments.length - 1; i++) {
            final PathSegment seg = segments[i];

            if (seg.isArray) {
                final ArrayNode arrayNode = ensureArray(current, seg.field, seg.arrayIndex);
                current = ensureObjectAt(arrayNode, seg.arrayIndex);
            } else {
                final JsonNode child = current.get(seg.field);

                if (child == null || !child.isObject()) {
                    final ObjectNode obj = MAPPER.createObjectNode();
                    current.set(seg.field, obj);
                    current = obj;
                } else {
                    current = (ObjectNode) child;
                }
            }
        }

        return current;
    }

    private static ArrayNode ensureArray(final ObjectNode node, final String field, final int minIndex) {
        final JsonNode existing = node.get(field);
        final ArrayNode array;

        if (existing instanceof ArrayNode arrayNode) {
            array = arrayNode;
        } else {
            array = MAPPER.createArrayNode();
            node.set(field, array);
        }

        while (array.size() <= minIndex) {
            array.addObject();
        }

        return array;
    }

    private static ObjectNode ensureObjectAt(final ArrayNode array, final int index) {
        final JsonNode node = array.get(index);

        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }

        final ObjectNode obj = MAPPER.createObjectNode();
        array.set(index, obj);

        return obj;
    }

    // leaf writing //

    private static void writeLeaf(final ObjectNode parent, final PathSegment leaf, final List<String> values, final int fieldType) {
        final boolean isArrayField = fieldType == FIELD_TYPE_ARRAY || fieldType == FIELD_TYPE_ARRAY2;

        if (leaf.isArray) {
            final ArrayNode array = ensureArray(parent, leaf.field, leaf.arrayIndex);
            array.set(leaf.arrayIndex, MAPPER.getNodeFactory().textNode(values.getFirst()));
            return;
        }
        if (isArrayField) {
            final ArrayNode array = MAPPER.createArrayNode();
            for (final String v : values) {
                array.add(v);
            }
            parent.set(leaf.field, array);
            return;
        }
        parent.put(leaf.field, values.getFirst());
    }

    private static JsonNode navigateToNode(final JsonNode root, final PathSegment[] segments) {
        JsonNode current = root;

        for (final PathSegment seg : segments) {
            if (current == null) {
                return null;
            }

            final JsonNode child = current.get(seg.field);

            if (child == null) {
                return null;
            }
            if (seg.isArray) {
                if (!child.isArray() || child.size() <= seg.arrayIndex) {
                    return null;
                }
                current = child.get(seg.arrayIndex);
            } else {
                current = child;
            }
        }

        return current;
    }

    private static List<String> extractValues(final JsonNode node) {
        if (node == null) {
            return Collections.emptyList();
        }
        if (node.isValueNode()) {
            return Collections.singletonList(node.asText());
        }
        if (!node.isArray()) {
            return Collections.emptyList();
        }

        final List<String> result = new ArrayList<>(node.size());

        node.forEach(n -> result.add(n.asText()));

        return result;
    }

    // path segment model //

    static final class PathSegment {

        final String field;
        final int arrayIndex;
        final boolean isArray;

        PathSegment(final String field, final int arrayIndex) {
            this.field = field;
            this.arrayIndex = arrayIndex;
            this.isArray = arrayIndex >= 0;
        }

        @Override
        public String toString() {
            return isArray ? field + "[" + arrayIndex + "]" : field;
        }
    }

// Demo //

    static void main() throws Exception {
        final String json = """
                {
                  "ch": [
                    {
                      "ch_g": "1",
                      "ch_bd": { "$date": "1900-01-01" }
                    }
                  ]
                }
                """;
        final String updated = updateField(
                json,
                "ch[0].ch_bd.$date",
                List.of("1990-01-01"),
                FIELD_TYPE_SCALAR
        );

        System.out.println("Updated: " + updated);

        final List<String> values = readField(updated, "ch[0].ch_bd.$date");
        System.out.println("Read: " + values);

        final String withNew = updateField(
                json,
                "ch[0].kyc.status",
                List.of("VERIFIED"),
                FIELD_TYPE_SCALAR
        );
        System.out.println("New path: " + withNew);
    }
}
