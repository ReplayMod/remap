package com.replaymod.gradle.remap.legacy;

import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.impl.MappingSetModelFactoryImpl;
import org.cadixdev.lorenz.impl.model.TopLevelClassMappingImpl;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.lorenz.model.TopLevelClassMapping;

import java.util.Optional;

public class LegacyMappingSetModelFactory extends MappingSetModelFactoryImpl {
    @Override
    public TopLevelClassMapping createTopLevelClassMapping(MappingSet parent, String obfuscatedName, String deobfuscatedName) {
        return new TopLevelClassMappingImpl(parent, obfuscatedName, deobfuscatedName) {
            private MethodSignature stripDesc(MethodSignature signature) {
                // actual descriptor isn't included in legacy format
                return MethodSignature.of(signature.getName(), "()V");
            }

            @Override
            public boolean hasMethodMapping(MethodSignature signature) {
                return super.hasMethodMapping(signature) || super.hasMethodMapping(stripDesc(signature));
            }

            @Override
            public Optional<MethodMapping> getMethodMapping(MethodSignature signature) {
                Optional<MethodMapping> maybeMapping = super.getMethodMapping(signature);
                if (!maybeMapping.isPresent() || !maybeMapping.get().hasMappings()) {
                    maybeMapping = super.getMethodMapping(stripDesc(signature));
                }
                return maybeMapping;
            }
        };
    }
}
