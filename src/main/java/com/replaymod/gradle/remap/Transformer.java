package com.replaymod.gradle.remap;

import com.intellij.codeInsight.CustomExceptionHandler;
import com.intellij.mock.MockProject;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.StandardFileSystems;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.local.CoreLocalFileSystem;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys;
import org.jetbrains.kotlin.cli.common.config.KotlinSourceRoot;
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer;
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace;
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM;
import org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot;
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class Transformer {
    private Map<String, Mapping> map;
    private String[] classpath;
    private boolean fail;

    public static void main(String[] args) throws IOException {
        Map<String, Mapping> mappings;
        if (args[0].isEmpty()) {
            mappings = new HashMap<>();
        } else {
            mappings = readMappings(new File(args[0]), args[1].equals("true"));
        }
        Transformer transformer = new Transformer(mappings);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        String[] classpath = new String[Integer.parseInt(args[2])];
        for (int i = 0; i < classpath.length; i++) {
            classpath[i] = reader.readLine();
        }
        transformer.setClasspath(classpath);

        Map<String, String> sources = new HashMap<>();
        while (true) {
            String name = reader.readLine();
            if (name == null || name.isEmpty()) {
                break;
            }

            String[] lines = new String[Integer.parseInt(reader.readLine())];
            for (int i = 0; i < lines.length; i++) {
                lines[i] = reader.readLine();
            }
            String source = String.join("\n", lines);

            sources.put(name, source);
        }

        Map<String, String> results = transformer.remap(sources);

        for (String name : sources.keySet()) {
            System.out.println(name);
            String[] lines = results.get(name).split("\n");
            System.out.println(lines.length);
            for (String line : lines) {
                System.out.println(line);
            }
        }

        if (transformer.fail) {
            System.exit(1);
        }
    }

    public Transformer(Map<String, Mapping> mappings) {
        this.map = mappings;
    }

    public String[] getClasspath() {
        return classpath;
    }

    public void setClasspath(String[] classpath) {
        this.classpath = classpath;
    }

    public Map<String, String> remap(Map<String, String> sources) throws IOException {
        Path tmpDir = Files.createTempDirectory("remap");
        try {
            for (Entry<String, String> entry : sources.entrySet()) {
                String unitName = entry.getKey();
                String source = entry.getValue();

                Path path = tmpDir.resolve(unitName);
                Files.createDirectories(path.getParent());
                Files.write(path, source.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
            }

            CompilerConfiguration config = new CompilerConfiguration();
            config.put(CommonConfigurationKeys.MODULE_NAME, "main");
            config.add(CLIConfigurationKeys.CONTENT_ROOTS, new JavaSourceRoot(tmpDir.toFile(), ""));
            config.add(CLIConfigurationKeys.CONTENT_ROOTS, new KotlinSourceRoot(tmpDir.toAbsolutePath().toString(), false));
            config.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, new PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, true));

            KotlinCoreEnvironment environment = KotlinCoreEnvironment.createForProduction(
                    Disposer.newDisposable(),
                    config,
                    EnvironmentConfigFiles.JVM_CONFIG_FILES
            );
            Extensions.getRootArea().registerExtensionPoint(CustomExceptionHandler.KEY.getName(), CustomExceptionHandler.class.getName(), ExtensionPoint.Kind.INTERFACE);

            MockProject project = (MockProject) environment.getProject();

            environment.updateClasspath(Stream.of(getClasspath()).map(it -> new JvmClasspathRoot(new File(it))).collect(Collectors.toList()));

            TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                    project,
                    Collections.emptyList(),
                    new NoScopeRecordCliBindingTrace(),
                    environment.getConfiguration(),
                    environment::createPackagePartProvider
            );

            CoreLocalFileSystem vfs = (CoreLocalFileSystem) VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL);
            Map<String, String> results = new HashMap<>();
            for (String name : sources.keySet()) {
                VirtualFile file = vfs.findFileByIoFile(tmpDir.resolve(name).toFile());
                assert file != null;
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                assert psiFile != null;

                String mapped = new PsiMapper(map, psiFile).remapFile();
                if (mapped == null) {
                    fail = true;
                    continue;
                }
                results.put(name, mapped);
            }
            return results;
        } finally {
            Files.walk(tmpDir).map(Path::toFile).sorted(Comparator.reverseOrder()).forEach(File::delete);
        }
    }

    public static class Mapping {
        public String oldName;
        public String newName;
        public Map<String, String> fields = new HashMap<>();
        public Map<String, String> methods = new HashMap<>();
    }

    public static Map<String, Mapping> readMappings(File mappingFile, boolean invert) throws IOException {
        Map<String, Mapping> mappings = new HashMap<>();
        Map<String, Mapping> revMappings = new HashMap<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(mappingFile.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.trim().startsWith("#") || line.trim().isEmpty()) continue;

            String[] parts = line.split(" ");
            if (parts.length < 2 || line.contains(";")) {
                throw new IllegalArgumentException("Failed to parse line " + lineNumber + " in " + mappingFile.getPath() + ".");
            }

            Mapping mapping = mappings.get(parts[0]);
            if (mapping == null) {
                mapping = new Mapping();
                mapping.oldName = mapping.newName = parts[0];
                mappings.put(mapping.oldName, mapping);
            }

            if (parts.length == 2) {
                // Class mapping
                mapping.newName = parts[1];
                // Possibly merge with reverse mapping
                Mapping revMapping = revMappings.remove(mapping.newName);
                if (revMapping != null) {
                    mapping.fields.putAll(revMapping.fields);
                    mapping.methods.putAll(revMapping.methods);
                }
                revMappings.put(mapping.newName, mapping);
            } else if (parts.length == 3 || parts.length == 4) {
                String fromName = parts[1];
                String toName;
                Mapping revMapping;
                if (parts.length == 4) {
                    toName = parts[3];
                    revMapping = revMappings.get(parts[2]);
                    if (revMapping == null) {
                        revMapping = new Mapping();
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
                throw new IllegalArgumentException("Failed to parse line " + lineNumber + " in " + mappingFile.getPath() + ".");
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
                it.fields = it.fields.entrySet().stream().collect(Collectors.toMap(Entry::getValue, Entry::getKey));
                it.methods = it.methods.entrySet().stream().collect(Collectors.toMap(Entry::getValue, Entry::getKey));
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
}
