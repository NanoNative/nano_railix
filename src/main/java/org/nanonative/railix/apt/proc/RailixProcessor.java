package org.nanonative.railix.apt.proc;

import org.nanonative.railix.apt.RailActor;
import org.nanonative.railix.apt.RailField;
import org.nanonative.railix.apt.Railix;
import org.nanonative.railix.name.Names;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@SupportedAnnotationTypes({
        "org.nanonative.railix.apt.Railix",
        "org.nanonative.railix.apt.RailField",
        "org.nanonative.railix.apt.RailActor"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class RailixProcessor extends AbstractProcessor {
    private Elements elements;
    private final Set<String> processed = new HashSet<>();

    @Override
    public synchronized void init(final ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.elements = processingEnv.getElementUtils();
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }
        for (Element e : roundEnv.getElementsAnnotatedWith(Railix.class)) {
            if (e.getKind() != ElementKind.INTERFACE) {
                error(e, "@Railix must annotate an interface");
                continue;
            }
            generateRail((TypeElement) e);
        }
        return true;
    }

    private void generateRail(final TypeElement iface) {
        if (!validateCommonInterface(iface, "@Railix")) {
            return;
        }
        final String pkg = packageOf(iface);
        final String simpleName = iface.getSimpleName().toString();
        final String genName = simpleName + "Rail";
        final String fqcn = pkg.isEmpty() ? genName : (pkg + "." + genName);
        if (!processed.add(fqcn)) {
            return;
        }
        final List<ExecutableElement> methods = interfaceMethods(iface);
        if (!validateNoGenerics(iface, "@Railix")) {
            return;
        }

        final List<ExecutableElement> ctxMethods = new ArrayList<>();
        final List<ExecutableElement> actorMethods = new ArrayList<>();

        for (ExecutableElement m : methods) {
            if (!validateMethodSignature(m, "@Railix")) {
                return;
            }
            if (m.getAnnotation(RailActor.class) != null) {
                actorMethods.add(m);
                if (m.getAnnotation(RailField.class) != null) {
                    error(m, "@Railix methods must not use @RailField together with @RailActor: " + m.getSimpleName());
                    return;
                }
                if (!validateLookupReturnType(m, "@Railix")) {
                    return;
                }
                if (!validateActorReturnTypeMatchesAnnotation(m)) {
                    return;
                }
            } else {
                ctxMethods.add(m);
                if (!validateLookupReturnType(m, "@Railix")) {
                    return;
                }
            }
        }

        final StringBuilder src = new StringBuilder(8192);
        if (!pkg.isEmpty()) {
            src.append("package ").append(pkg).append(";\n\n");
        }
        src.append("import org.nanonative.railix.Rail;\n");
        src.append("import java.util.Optional;\n\n");

        src.append("public final class ").append(genName).append(" extends Rail {\n");
        src.append("  public ").append(genName).append("() { super(); }\n");
        src.append("  public ").append(genName).append("(final boolean concurrentMeta) { super(concurrentMeta); }\n");
        src.append("  public static ").append(genName).append(" of() { return new ").append(genName)
                .append("(); }\n\n");
        src.append("  private final Ctx ctx = new Ctx();\n");
        src.append("  public Ctx ctx() { return ctx; }\n\n");

        if (!actorMethods.isEmpty()) {
            src.append("  private final Actors actors = new Actors();\n");
            src.append("  @Override\n");
            src.append("  public Actors actors() { return actors; }\n\n");
        }

        src.append("  public final class Ctx {\n");
        src.append("    private Ctx() {}\n\n");

        for (ExecutableElement m : ctxMethods) {
            final String methodName = m.getSimpleName().toString();
            final TypeMirror rt = m.getReturnType();
            final String type = rt.toString();
            final String key = fieldKey(m, methodName);
            final String constName = constName(key);

            src.append("    public static final String ").append(constName).append(" = \"").append(escape(key))
                    .append("\";\n");
            src.append("    public ").append(type).append(" ").append(methodName).append("() { return ")
                    .append(ctxReadExpr(type, constName)).append("; }\n");
            src.append("    public Optional<").append(type).append("> ").append(methodName)
                    .append("Opt() { return ").append(ctxReadOptExpr(type, constName)).append("; }\n");
            src.append("    public ").append(genName).append(" ").append(methodName).append("(final ").append(type)
                    .append(" value) { ").append(genName).append(".this.ctxSet(value, ").append(constName)
                    .append("); return ").append(genName).append(".this; }\n\n");
        }

        src.append("  }\n");

        if (!actorMethods.isEmpty()) {
            src.append("\n");
            src.append("  public final class Actors extends org.nanonative.railix.Actors {\n");
            src.append("    private Actors() { super(); }\n\n");

            for (ExecutableElement m : actorMethods) {
                final String methodName = m.getSimpleName().toString();
                final TypeMirror rt = m.getReturnType();
                final String type = rt.toString();
                final String key = actorKey(m, methodName);

                src.append("    public ").append(type).append(" ").append(methodName).append("() { return get(\"")
                        .append(escape(key)).append("\", ").append(type).append(".class); }\n");
                src.append("    public Optional<").append(type).append("> ").append(methodName)
                        .append("Opt() { return getOpt(\"").append(escape(key)).append("\", ").append(type)
                        .append(".class); }\n\n");
            }

            src.append("  }\n");
        }

        src.append("}\n");
        writeSourceFile(iface, pkg, genName, src.toString());
    }

    private boolean validateCommonInterface(final TypeElement iface, final String annotation) {
        if (iface.getModifiers().contains(Modifier.PRIVATE)) {
            error(iface, annotation + " interfaces must not be private");
            return false;
        }
        return true;
    }

    private boolean validateNoGenerics(final TypeElement iface, final String annotation) {
        if (!iface.getTypeParameters().isEmpty()) {
            error(iface, annotation + " interfaces must not declare generic type parameters");
            return false;
        }
        return true;
    }

    private boolean validateMethodSignature(final ExecutableElement m, final String annotation) {
        if (m.getModifiers().contains(Modifier.DEFAULT)) {
            error(m, annotation + " methods must not be default methods: " + m.getSimpleName());
            return false;
        }
        if (m.getModifiers().contains(Modifier.STATIC)) {
            error(m, annotation + " methods must not be static: " + m.getSimpleName());
            return false;
        }
        if (!m.getTypeParameters().isEmpty()) {
            error(m, annotation + " methods must not declare type parameters: " + m.getSimpleName());
            return false;
        }
        if (!m.getParameters().isEmpty()) {
            error(m, annotation + " methods must not have parameters: " + m.getSimpleName());
            return false;
        }
        if (m.getReturnType().getKind() == TypeKind.VOID) {
            error(m, annotation + " methods must not return void: " + m.getSimpleName());
            return false;
        }
        return true;
    }

    private List<ExecutableElement> interfaceMethods(final TypeElement iface) {
        final List<ExecutableElement> out = new ArrayList<>();
        for (Element e : iface.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD) {
                out.add((ExecutableElement) e);
            }
        }
        return out;
    }

    private String fieldKey(final ExecutableElement m, final String fallbackMethodName) {
        final RailField ann = m.getAnnotation(RailField.class);
        if (ann == null || ann.value() == null || ann.value().isBlank()) {
            return Names.methodKey(fallbackMethodName);
        }
        return Names.sanitize(ann.value(), Names.methodKey(fallbackMethodName));
    }

    private String actorKey(final ExecutableElement m, final String fallbackMethodName) {
        final RailActor ann = m.getAnnotation(RailActor.class);
        if (ann == null || ann.name() == null || ann.name().isBlank()) {
            return Names.methodKey(fallbackMethodName);
        }
        return Names.sanitize(ann.name(), Names.methodKey(fallbackMethodName));
    }

    private String packageOf(final TypeElement type) {
        final PackageElement pkg = elements.getPackageOf(type);
        if (pkg == null || pkg.isUnnamed()) {
            return "";
        }
        return pkg.getQualifiedName().toString();
    }

    private void writeSourceFile(final TypeElement originating, final String pkg, final String simpleName,
            final String content) {
        final String fqcn = pkg.isEmpty() ? simpleName : (pkg + "." + simpleName);
        try {
            final JavaFileObject file = processingEnv.getFiler().createSourceFile(fqcn, originating);
            try (Writer w = file.openWriter()) {
                w.write(content);
            }
        } catch (final IOException e) {
            error(originating, "Failed to write generated source " + fqcn + ": " + e.getMessage());
        }
    }

    private void error(final Element e, final String msg) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, e);
    }

    private boolean validateLookupReturnType(final ExecutableElement m, final String annotation) {
        final TypeMirror rt = m.getReturnType();
        if (rt.getKind().isPrimitive()) {
            error(m, annotation + " methods must not return primitive types: " + m.getSimpleName());
            return false;
        }
        if (!(rt instanceof DeclaredType dt)) {
            error(m, annotation + " methods must return a declared type: " + m.getSimpleName());
            return false;
        }
        if (!dt.getTypeArguments().isEmpty()) {
            error(m, annotation + " methods must not use generic return types: " + m.getSimpleName());
            return false;
        }
        if (dt.toString().equals(Optional.class.getName())) {
            error(m, annotation
                    + " methods must not return Optional; use plain return type and the generated *Opt() accessor.");
            return false;
        }
        return true;
    }

    private boolean validateActorReturnTypeMatchesAnnotation(final ExecutableElement m) {
        final RailActor ann = m.getAnnotation(RailActor.class);
        if (ann == null) {
            return true;
        }

        TypeMirror expected = null;
        try {
            final Class<?> c = ann.value();
            final TypeElement t = elements.getTypeElement(c.getCanonicalName());
            expected = t == null ? null : t.asType();
        } catch (final MirroredTypeException ex) {
            expected = ex.getTypeMirror();
        }

        final String expectedStr = expected == null ? "" : expected.toString();
        final String actualStr = m.getReturnType().toString();
        if (!expectedStr.equals(actualStr)) {
            error(m, "@RailActor return type must match @RailActor(value): " + m.getSimpleName()
                    + " returns " + actualStr + " but annotation declares " + expectedStr);
            return false;
        }
        return true;
    }

    private static String escape(final String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String ctxReadExpr(final String type, final String constName) {
        return switch (type) {
            case "java.lang.String", "String" -> "ctxMap().asString(" + constName + ")";
            case "java.lang.Integer", "Integer" -> "ctxMap().asInt(" + constName + ")";
            case "java.lang.Long", "Long" -> "ctxMap().asLong(" + constName + ")";
            case "java.lang.Boolean", "Boolean" -> "ctxMap().asBoolean(" + constName + ")";
            case "java.lang.Double", "Double" -> "ctxMap().asDouble(" + constName + ")";
            case "java.lang.Float", "Float" -> "ctxMap().asFloat(" + constName + ")";
            default -> "ctxMap().asOpt(" + type + ".class, " + constName + ").orElse(null)";
        };
    }

    private static String ctxReadOptExpr(final String type, final String constName) {
        return "Optional.ofNullable(" + ctxReadExpr(type, constName) + ")";
    }

    private static String constName(final String key) {
        final String upper = key.toUpperCase(Locale.ROOT);
        if (upper.isEmpty()) {
            return "K";
        }
        final char c = upper.charAt(0);
        if (Character.isLetter(c) || c == '_') {
            return upper;
        }
        return "K_" + upper;
    }
}
