package com.replaymod.gradle.remap.legacy;

import org.cadixdev.lorenz.MappingSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LegacyMapping {
    public static MappingSet readMappingSet(Path mappingFile, boolean invert) throws IOException {
        return new LegacyMappingsReader(readMappings(mappingFile, invert)).read();
    }

    public static Map<String, LegacyMapping> readMappings(Path mappingFile, boolean invert) throws IOException {
        Map<String, LegacyMapping> mappings = new HashMap<>();
        Map<String, LegacyMapping> revMappings = new HashMap<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(mappingFile, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.trim().startsWith("#") || line.trim().isEmpty()) continue;

            String[] parts = line.split(" ");
            if (parts.length < 2 || line.contains(";")) {
                throw new IllegalArgumentException("Failed to parse line " + lineNumber + " in " + mappingFile + ".");
            }

            LegacyMapping mapping = mappings.get(parts[0]);
            if (mapping == null) {
                mapping = new LegacyMapping();
                mapping.oldName = mapping.newName = parts[0];
                mappings.put(mapping.oldName, mapping);
            }

            if (parts.length == 2) {
                // Class mapping
                mapping.newName = parts[1];
                // Possibly merge with reverse mapping
                LegacyMapping revMapping = revMappings.remove(mapping.newName);
                if (revMapping != null) {
                    mapping.fields.putAll(revMapping.fields);
                    mapping.methods.putAll(revMapping.methods);
                }
                revMappings.put(mapping.newName, mapping);
            } else if (parts.length == 3 || parts.length == 4) {
                String fromName = parts[1];
                String toName;
                LegacyMapping revMapping;
                if (parts.length == 4) {
                    toName = parts[3];
                    revMapping = revMappings.get(parts[2]);
                    if (revMapping == null) {
                        revMapping = new LegacyMapping();
                        revMapping.oldName = revMapping.newName = parts[2];
                        revMappings.put(revMapping.newName, revMapping);
                    }
                } else {
                    toName = parts[2];
                    revMapping = mapping;
                }
                if (fromName.endsWith("()")) {
                    // Method mapping
                    fromName = fromName.substring(0, fromName.length() - 2);
                    toName = toName.substring(0, toName.length() - 2);
                    mapping.methods.put(fromName, toName);
                    revMapping.methods.put(fromName, toName);
                } else {
                    // Field mapping
                    mapping.fields.put(fromName, toName);
                    revMapping.fields.put(fromName, toName);
                }
            } else {
                throw new IllegalArgumentException("Failed to parse line " + lineNumber + " in " + mappingFile + ".");
            }
        }
        if (invert) {
            Stream.concat(
                    mappings.values().stream(),
                    revMappings.values().stream()
            ).distinct().forEach(it -> {
                String oldName = it.oldName;
                it.oldName = it.newName;
                it.newName = oldName;
                it.fields = it.fields.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
                it.methods = it.methods.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
            });
        }
        return Stream.concat(
                mappings.values().stream(),
                revMappings.values().stream()
        ).collect(Collectors.toMap(mapping -> mapping.oldName, Function.identity(), (mapping, other) -> {
            if (!other.oldName.equals(other.newName)) {
                if (!mapping.oldName.equals(mapping.newName)
                        && !other.oldName.equals(mapping.oldName)
                        && !other.newName.equals(mapping.newName)) {
                    throw new IllegalArgumentException("Conflicting mappings: "
                            + mapping.oldName + " -> " + mapping.newName
                            + " and " + other.oldName + " -> " + other.newName);
                }
                mapping.oldName = other.oldName;
                mapping.newName = other.newName;
            }
            mapping.fields.putAll(other.fields);
            mapping.methods.putAll(other.methods);
            return mapping;
        }));
    }

    public String oldName;
    public String newName;
    public Map<String, String> fields = new HashMap<>();
    public Map<String, String> methods = new HashMap<>();
}
