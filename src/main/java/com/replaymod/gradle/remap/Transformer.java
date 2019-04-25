package com.replaymod.gradle.remap;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

class Transformer {
    private Map<String, Mapping> map;
    private String[] classpath;

    public static void main(String[] args) throws IOException, BadLocationException {
        Map<String, Mapping> mappings;
        if (args[0].isEmpty()) {
            mappings = new HashMap<>();
        } else {
            mappings = readMappings(Collections.singletonList(new File(args[0])), args[1].equals("true"));
        }
        Transformer transformer = new Transformer(mappings);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        String[] classpath = new String[Integer.parseInt(args[2])];
        for (int i = 0; i < classpath.length; i++) {
            classpath[i] = reader.readLine();
        }
        transformer.setClasspath(classpath);

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

            String result = transformer.remap(name, source);

            System.out.println(name);
            lines = result.split("\n");
            System.out.println(lines.length);
            for (String line : lines) {
                System.out.println(line);
            }
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

    public String remap(String unitName, String source) throws BadLocationException {
        Document document = new Document(source);
        ASTParser parser = ASTParser.newParser(AST.JLS8);
        Map<String, String> options = JavaCore.getDefaultOptions();
        JavaCore.setComplianceOptions("1.8", options);
        parser.setCompilerOptions(options);
        parser.setUnitName(unitName);
        parser.setSource(document.get().toCharArray());
        parser.setEnvironment(classpath, null, null, true);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        for (IProblem problem : cu.getProblems()) {
            if (problem.isError()) {
                System.err.println(unitName + ":" + problem.getSourceLineNumber() + ": " + problem.getMessage());
            }
        }
        cu.recordModifications();
        if (remapClass(cu)) {
            TextEdit edit = cu.rewrite(document, JavaCore.getDefaultOptions());
            edit.apply(document);
            return document.get();
        } else {
            return source;
        }
    }

    private boolean remapClass(CompilationUnit cu) {
        AtomicBoolean changed = new AtomicBoolean(false);
        Map<String, String> mappedImports = new HashMap<>();
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(ImportDeclaration node) {
                String name = node.getName().getFullyQualifiedName();
                Mapping mapping = map.get(name);
                String mapped = mapping == null ? null : mapping.newName;
                if (mapped != null && !mapped.equals(name)) {
                    node.setName(node.getAST().newName(mapped));
                    changed.set(true);
                    String simpleName = name.substring(name.lastIndexOf('.') + 1);
                    String simpleMapped = mapped.substring(mapped.lastIndexOf('.') + 1);
                    if (!simpleName.equals(simpleMapped)) {
                        mappedImports.put(simpleName, simpleMapped);
                    }
                }
                return false;
            }

            @Override
            public boolean visit(QualifiedName node) {
                String name = node.getFullyQualifiedName();
                Mapping mapping = map.get(name);
                String mapped = mapping == null ? null : mapping.newName;
                if (mapped != null && !mapped.equals(name)) {
                    node.setQualifier(node.getAST().newName(mapped.substring(0, mapped.lastIndexOf('.'))));
                    node.setName(node.getAST().newSimpleName(mapped.substring(mapped.lastIndexOf('.') + 1)));
                    changed.set(true);
                    return false;
                } else {
                    return true;
                }
            }

            @Override
            public boolean visit(SimpleName node) {
                return visitName(node.resolveBinding(), node);
            }

            @Override
            public boolean visit(MethodInvocation node) {
                return visitName(node.resolveMethodBinding(), node.getName());
            }

            private boolean visitName(IBinding binding, SimpleName node) {
                String mapped;
                if (binding instanceof IVariableBinding) {
                    ITypeBinding declaringClass = ((IVariableBinding) binding).getDeclaringClass();
                    if (declaringClass == null) return true;
                    String name = declaringClass.getQualifiedName();
                    if (name.isEmpty()) return true;
                    Mapping mapping = map.get(name);
                    if (mapping == null) return true;
                    mapped = mapping.fields.get(node.getIdentifier());
                } else if (binding instanceof IMethodBinding) {
                    ITypeBinding declaringClass = ((IMethodBinding) binding).getDeclaringClass();
                    if (declaringClass == null) return true;
                    String name = declaringClass.getQualifiedName();
                    if (name.isEmpty()) return true;
                    Mapping mapping = map.get(name);
                    if (mapping == null) return true;
                    mapped = mapping.methods.get(node.getIdentifier());
                } else if (binding instanceof ITypeBinding) {
                    String name = ((ITypeBinding) binding).getQualifiedName();
                    if (name.isEmpty()) return true;
                    Mapping mapping = map.get(name);
                    if (mapping == null) return true;
                    mapped = mapping.newName;
                    mapped = mapped.substring(mapped.lastIndexOf('.') + 1);
                } else {
                    mapped = mappedImports.get(node.getIdentifier());
                }

                if (mapped != null && !mapped.equals(node.getIdentifier())) {
                    node.setIdentifier(mapped);
                    changed.set(true);
                }
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.getBody() != null && node.getLength() == node.getBody().getLength()) {
                    // Body exists but is same length as overall definition? -> method was probably generated by lombok
                    return false;
                }
                return super.visit(node);
            }
        });
        return changed.get();
    }

    public static class Mapping {
        public String oldName;
        public String newName;
        public Map<String, String> fields = new HashMap<>();
        public Map<String, String> methods = new HashMap<>();
    }

    public static Map<String, Mapping> readMappings(List<File> mappingFiles, boolean invert) throws IOException {
        Map<String, Mapping> mappings = new HashMap<>();
        for (File mappingFile : mappingFiles) {
            Map<String, Mapping> currentMappings = new HashMap<>(mappings);
            int lineNumber = 0;
            for (String line : Files.readAllLines(mappingFile.toPath(), StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.trim().startsWith("#") || line.trim().isEmpty()) continue;

                String[] parts = line.split(" ");
                if (parts.length < 2 || line.contains(";")) {
                    throw new IllegalArgumentException("Failed to parse line " + lineNumber + " in " + mappingFile.getPath() + ".");
                }

                Mapping mapping = currentMappings.get(parts[0]);
                if (mapping == null) {
                    mapping = new Mapping();
                    mapping.oldName = mapping.newName = parts[0];
                    currentMappings.put(mapping.oldName, mapping);
                    mappings.put(mapping.newName, mapping);
                }

                if (parts.length == 2) {
                    // Class mapping
                    mappings.remove(mapping.newName);
                    mapping.newName = parts[1];
                    mappings.put(mapping.newName, mapping);
                } else if (parts[1].endsWith("()")) {
                    // Method mapping
                    String name = parts[1].substring(0, parts[1].length() - 2);
                    String newName = parts[2].substring(0, parts[2].length() - 2);
                    String oldName = mapping.methods.remove(name);
                    if (oldName == null) oldName = name;
                    mapping.methods.put(oldName, newName);
                } else {
                    // Field mapping
                    String name = parts[1];
                    String newName = parts[2];
                    String oldName = mapping.fields.remove(name);
                    if (oldName == null) oldName = name;
                    mapping.fields.put(oldName, newName);
                }
            }
        }
        if (invert) {
            mappings.values().forEach(it -> {
                String oldName = it.oldName;
                it.oldName = it.newName;
                it.newName = oldName;
                it.fields = it.fields.entrySet().stream().collect(Collectors.toMap(Entry::getValue, Entry::getKey));
                it.methods = it.methods.entrySet().stream().collect(Collectors.toMap(Entry::getValue, Entry::getKey));
            });
        }
        return mappings.entrySet().stream().collect(Collectors.toMap(e -> e.getValue().oldName, Entry::getValue));
    }
}
