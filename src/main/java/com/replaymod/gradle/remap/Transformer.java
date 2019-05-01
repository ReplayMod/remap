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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class Transformer {
    private Map<String, Mapping> map;
    private String[] classpath;
    private boolean fail;

    public static void main(String[] args) throws IOException, BadLocationException {
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

    public Map<String, String> remap(Map<String, String> sources) throws BadLocationException, IOException {
        ASTParser parser = ASTParser.newParser(AST.JLS8);
        Map<String, String> options = JavaCore.getDefaultOptions();
        JavaCore.setComplianceOptions("1.8", options);
        parser.setCompilerOptions(options);
        parser.setEnvironment(classpath, null, null, true);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);

        Path tmpDir = Files.createTempDirectory("remap");
        try {
            Map<String, String> filePathToName = new HashMap<>();
            String[] compilationUnits = new String[sources.size()];
            String[] encodings = new String[compilationUnits.length];
            int i = 0;
            for (Entry<String, String> entry : sources.entrySet()) {
                String unitName = entry.getKey();
                String source = entry.getValue();

                Path path = tmpDir.resolve(unitName);
                Files.createDirectories(path.getParent());
                Files.write(path, source.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
                String filePath = path.toString();
                filePathToName.put(filePath, unitName);
                compilationUnits[i] = filePath;
                encodings[i] = "UTF-8";

                i++;
            }
            Map<String, CompilationUnit> cus = new HashMap<>();
            parser.createASTs(compilationUnits, encodings, new String[0], new FileASTRequestor() {
                @Override
                public void acceptAST(String sourceFilePath, CompilationUnit cu) {
                    String unitName = filePathToName.get(sourceFilePath);
                    for (IProblem problem : cu.getProblems()) {
                        if (problem.isError()) {
                            System.err.println(unitName + ":" + problem.getSourceLineNumber() + ": " + problem.getMessage());
                        }
                    }
                    cus.put(unitName, cu);
                }
            }, null);

            Map<String, String> results = new HashMap<>();
            for (Entry<String, CompilationUnit> entry : cus.entrySet()) {
                String unitName = entry.getKey();
                CompilationUnit cu = entry.getValue();

                cu.recordModifications();
                if (remapClass(unitName, cu)) {
                    Document document = new Document(sources.get(unitName));
                    TextEdit edit = cu.rewrite(document, JavaCore.getDefaultOptions());
                    edit.apply(document);
                    results.put(unitName, document.get());
                } else {
                    results.put(unitName, sources.get(unitName));
                }
            }
            return results;
        } finally {
            Files.walk(tmpDir).map(Path::toFile).sorted(Comparator.reverseOrder()).forEach(File::delete);
        }
    }

    private static final String CLASS_MIXIN = "org.spongepowered.asm.mixin.Mixin";
    private static final String CLASS_ACCESSOR = "org.spongepowered.asm.mixin.gen.Accessor";
    private static final String CLASS_AT = "org.spongepowered.asm.mixin.injection.At";
    private static final String CLASS_INJECT = "org.spongepowered.asm.mixin.injection.Inject";
    private static final String CLASS_REDIRECT = "org.spongepowered.asm.mixin.injection.Redirect";

    // Note: Supports only Mixins with a single target (ignores others) and only ones specified via class literals
    private ITypeBinding getMixinTarget(IAnnotationBinding annotation) {
        for (IMemberValuePairBinding pair : annotation.getDeclaredMemberValuePairs()) {
            if (pair.getName().equals("value")) {
                return (ITypeBinding) ((Object[]) pair.getValue())[0];
            }
        }
        return null;
    }

    private boolean remapAccessors(CompilationUnit cu, Mapping mapping) {
        AtomicBoolean changed = new AtomicBoolean(false);
        AST ast = cu.getAST();

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                int annotationIndex = -1;
                Annotation annotationNode = null;
                IAnnotationBinding annotation = null;
                for (int i = 0; i < node.modifiers().size(); i++) {
                    Object obj = node.modifiers().get(i);
                    if (!(obj instanceof Annotation)) {
                        continue;
                    }
                    annotationNode = (Annotation) obj;
                    annotation = annotationNode.resolveAnnotationBinding();
                    if (annotation != null && annotation.getAnnotationType().getQualifiedName().equals(CLASS_ACCESSOR)) {
                        annotationIndex = i;
                        break;
                    }
                }
                if (annotationIndex == -1) return false;

                String targetByName = node.getName().getIdentifier();
                if (targetByName.startsWith("is")) {
                    targetByName = targetByName.substring(2);
                } else if (targetByName.startsWith("get") || targetByName.startsWith("set")) {
                    targetByName = targetByName.substring(3);
                } else {
                    targetByName = null;
                }
                if (targetByName != null) {
                    targetByName = targetByName.substring(0, 1).toLowerCase() + targetByName.substring(1);
                }

                String target = Arrays.stream(annotation.getDeclaredMemberValuePairs())
                        .filter(it -> it.getName().equals("value"))
                        .map(it -> (String) it.getValue())
                        .findAny()
                        .orElse(targetByName);

                if (target == null) {
                    throw new IllegalArgumentException("Cannot determine accessor target for " + node);
                }

                String mapped = mapping.fields.get(target);
                if (mapped != null && !mapped.equals(target)) {

                    Annotation newAnnotation;

                    // Update accessor target
                    if (mapped.equals(targetByName)) {
                        // Mapped name matches implied target, can just remove the explict target
                        newAnnotation = ast.newMarkerAnnotation();
                    } else {
                        // Mapped name does not match implied target, need to set the target as annotation value
                        SingleMemberAnnotation singleMemberAnnotation = ast.newSingleMemberAnnotation();
                        StringLiteral value = ast.newStringLiteral();
                        value.setLiteralValue(mapped);
                        singleMemberAnnotation.setValue(value);
                        newAnnotation = singleMemberAnnotation;
                    }

                    newAnnotation.setTypeName(ast.newName(annotationNode.getTypeName().getFullyQualifiedName()));
                    //noinspection unchecked
                    node.modifiers().set(annotationIndex, newAnnotation);

                    changed.set(true);
                }

                return false;
            }
        });

        return changed.get();
    }

    private boolean remapInjectsAndRedirects(CompilationUnit cu, Mapping mapping) {
        AtomicBoolean changed = new AtomicBoolean(false);

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                NormalAnnotation annotationNode = null;
                for (Object obj : node.modifiers()) {
                    if (!(obj instanceof NormalAnnotation)) {
                        continue;
                    }
                    annotationNode = (NormalAnnotation) obj;
                    IAnnotationBinding annotation = annotationNode.resolveAnnotationBinding();
                    if (annotation != null) {
                        String qualifiedName = annotation.getAnnotationType().getQualifiedName();
                        if (qualifiedName.equals(CLASS_INJECT) || qualifiedName.equals(CLASS_REDIRECT)) {
                            break;
                        }
                    }
                    annotationNode = null;
                }
                if (annotationNode == null) return false;

                //noinspection unchecked
                for (MemberValuePair pair : (List<MemberValuePair>) annotationNode.values()) {
                    if (!pair.getName().getIdentifier().equals("method")) continue;

                    Object expr = pair.getValue();
                    // Note: mixin supports multiple targets, we do not (yet)
                    if (!(expr instanceof StringLiteral)) continue;
                    StringLiteral methodNode = (StringLiteral) expr;
                    String method = methodNode.getLiteralValue();
                    String mapped = mapping.methods.get(method);
                    if (mapped != null && !mapped.equals(method)) {
                        methodNode.setLiteralValue(mapped);
                        changed.set(true);
                    }
                }

                return false;
            }
        });

        return changed.get();
    }

    private Mapping remapInternalType(String internalType, StringBuilder result) {
        if (internalType.charAt(0) == 'L') {
            String type = internalType.substring(1, internalType.length() - 1).replace('/', '.');
            Mapping mapping = map.get(type);
            if (mapping != null) {
                result.append('L').append(mapping.newName.replace('.', '/')).append(';');
                return mapping;
            }
        }
        result.append(internalType);
        return null;
    }

    private String remapFullyQualifiedMethodOrField(String signature) {
        int ownerEnd = signature.indexOf(';');
        int argsBegin = signature.indexOf('(');
        int argsEnd = signature.indexOf(')');
        boolean method = argsBegin != -1;
        if (!method) {
            argsBegin = argsEnd = signature.indexOf(':');
        }
        String owner = signature.substring(0, ownerEnd + 1);
        String name = signature.substring(ownerEnd + 1, argsBegin);
        String returnType = signature.substring(argsEnd + 1);

        StringBuilder builder = new StringBuilder(signature.length() + 32);
        Mapping mapping = remapInternalType(owner, builder);
        String mapped = null;
        if (mapping != null) {
            mapped = (method ? mapping.methods : mapping.fields).get(name);
        }
        builder.append(mapped != null ? mapped : name);
        if (method) {
            builder.append('(');
            String args = signature.substring(argsBegin + 1, argsEnd);
            for (int i = 0; i < args.length(); i++) {
                char c = args.charAt(i);
                if (c != 'L') {
                    builder.append(c);
                    continue;
                }
                int end = args.indexOf(';', i);
                String arg = args.substring(i, end + 1);
                remapInternalType(arg, builder);
                i = end;
            }
            builder.append(')');
        } else {
            builder.append(':');
        }
        remapInternalType(returnType, builder);
        return builder.toString();
    }

    private boolean remapAtTargets(CompilationUnit cu) {
        AtomicBoolean changed = new AtomicBoolean(false);

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(NormalAnnotation node) {
                IAnnotationBinding annotation = node.resolveAnnotationBinding();
                if (annotation == null) return true;
                String qualifiedName = annotation.getAnnotationType().getQualifiedName();
                if (!qualifiedName.equals(CLASS_AT)) return true;

                //noinspection unchecked
                for (MemberValuePair pair : (List<MemberValuePair>) node.values()) {
                    if (!pair.getName().getIdentifier().equals("target")) continue;

                    StringLiteral value = (StringLiteral) pair.getValue();
                    String signature = value.getLiteralValue();
                    String newSignature = remapFullyQualifiedMethodOrField(signature);
                    if (!newSignature.equals(signature)) {
                        value.setLiteralValue(newSignature);
                        changed.set(true);
                    }
                }

                return false;
            }
        });

        return changed.get();
    }

    private static String stripGenerics(String name) {
        int paramIndex = name.indexOf('<');
        return paramIndex != -1 ? name.substring(0, paramIndex) : name;
    }

    private boolean remapClass(String unitName, CompilationUnit cu) {
        AtomicBoolean changed = new AtomicBoolean(false);
        Map<String, Mapping> mixinMappings = new HashMap<>();

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                ITypeBinding type = node.resolveBinding();
                if (type == null) return false;

                IAnnotationBinding binding = null;
                for (Object modifier : node.modifiers()) {
                    if (modifier instanceof Annotation) {
                        binding = ((Annotation) modifier).resolveAnnotationBinding();
                        if (binding != null && !binding.getAnnotationType().getQualifiedName().equals(CLASS_MIXIN)) {
                            binding = null;
                        }
                    }
                }
                if (binding == null) return false;

                if (remapAtTargets(cu)) {
                    changed.set(true);
                }

                ITypeBinding target = getMixinTarget(binding);
                if (target == null) return false;

                Mapping mapping = map.get(target.getQualifiedName());
                if (mapping == null) return false;

                mixinMappings.put(type.getQualifiedName(), mapping);

                if (!mapping.fields.isEmpty()) {
                    if (remapAccessors(cu, mapping)) {
                        changed.set(true);
                    }
                }
                if (!mapping.methods.isEmpty()) {
                    if (remapInjectsAndRedirects(cu, mapping)) {
                        changed.set(true);
                    }
                }

                return false;
            }
        });

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(ImportDeclaration node) {
                String name = node.getName().getFullyQualifiedName();
                Mapping mapping = map.get(name);
                String mapped = mapping == null ? null : mapping.newName;
                if (mapped != null && !mapped.equals(name)) {
                    node.setName(node.getAST().newName(mapped));
                    changed.set(true);
                }
                return false;
            }
        });

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(ImportDeclaration node) {
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

            private boolean visitName(IBinding binding, SimpleName node) {
                String mapped;
                if (binding instanceof IVariableBinding) {
                    ITypeBinding declaringClass = ((IVariableBinding) binding).getDeclaringClass();
                    if (declaringClass == null) return true;
                    String name = stripGenerics(declaringClass.getQualifiedName());
                    if (name.isEmpty()) return true;
                    Mapping mapping = mixinMappings.get(name);
                    if (mapping == null) {
                        mapping = map.get(name);
                    }
                    if (mapping == null) return true;
                    mapped = mapping.fields.get(node.getIdentifier());
                    if (mapped != null) {
                        ASTNode parent = node.getParent();
                        if (!(parent instanceof FieldAccess // qualified access is fine
                                || parent instanceof QualifiedName // qualified access is fine
                                || parent instanceof VariableDeclarationFragment // shadow member declarations are fine
                                || parent instanceof SwitchCase) // referencing constants in case statements is fine
                        ) {
                            System.err.println(unitName + ": Implicit member reference to remapped field \"" + node.getIdentifier() + "\". " +
                                    "This can cause issues if the remapped reference becomes shadowed by a local variable and is therefore forbidden. " +
                                    "Use \"this." + node.getIdentifier() + "\" instead.");
                            fail = true;
                        }
                    }
                } else if (binding instanceof IMethodBinding) {
                    ITypeBinding declaringClass = ((IMethodBinding) binding).getDeclaringClass();
                    if (declaringClass == null) return true;
                    ArrayDeque<ITypeBinding> parentQueue = new ArrayDeque<>();
                    parentQueue.offer(declaringClass);
                    Mapping mapping = null;

                    String name = stripGenerics(declaringClass.getQualifiedName());
                    if (!name.isEmpty()) {
                        mapping = mixinMappings.get(name);
                    }
                    while (true) {
                        if (mapping != null) {
                            mapped = mapping.methods.get(node.getIdentifier());
                            if (mapped != null) {
                                break;
                            }
                            mapping = null;
                        }

                        ITypeBinding superClass = declaringClass.getSuperclass();
                        if (superClass != null) {
                            parentQueue.offer(superClass);
                        }
                        for (ITypeBinding anInterface : declaringClass.getInterfaces()) {
                            parentQueue.offer(anInterface);
                        }
                        while (mapping == null) {
                            declaringClass = parentQueue.poll();
                            if (declaringClass == null) return true;
                            name = stripGenerics(declaringClass.getQualifiedName());
                            if (name.isEmpty()) continue;
                            mapping = map.get(name);
                        }
                    }
                } else if (binding instanceof ITypeBinding) {
                    String name = stripGenerics(((ITypeBinding) binding).getQualifiedName());
                    if (name.isEmpty()) return true;
                    Mapping mapping = map.get(name);
                    if (mapping == null) return true;
                    mapped = mapping.newName;
                    mapped = mapped.substring(mapped.lastIndexOf('.') + 1);
                } else {
                    return true;
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
