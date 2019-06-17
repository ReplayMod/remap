package com.replaymod.gradle.remap.legacy;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.MappingsReader;
import org.cadixdev.lorenz.model.ClassMapping;

import java.util.Map;

public class LegacyMappingsReader extends MappingsReader {
    private final Map<String, LegacyMapping> map;

    public LegacyMappingsReader(Map<String, LegacyMapping> map) {
        this.map = map;
    }

    @Override
    public MappingSet read() {
        return read(MappingSet.create(new LegacyMappingSetModelFactory()));
    }

    @Override
    public MappingSet read(MappingSet mappings) {
        if (!(mappings.getModelFactory() instanceof LegacyMappingSetModelFactory)) {
            throw new IllegalArgumentException("legacy mappings must use legacy model factory, use read() instead");
        }
        for (LegacyMapping legacyMapping : map.values()) {
            ClassMapping classMapping = mappings.getOrCreateClassMapping(legacyMapping.oldName)
                    .setDeobfuscatedName(legacyMapping.newName);
            for (Map.Entry<String, String> entry : legacyMapping.fields.entrySet()) {
                classMapping.getOrCreateFieldMapping(entry.getKey())
                        .setDeobfuscatedName(entry.getValue());
            }
            for (Map.Entry<String, String> entry : legacyMapping.methods.entrySet()) {
                classMapping.getOrCreateMethodMapping(entry.getKey(), "()V")
                        .setDeobfuscatedName(entry.getValue());
            }
        }
        return mappings;
    }

    @Override
    public void close() {
    }
}
