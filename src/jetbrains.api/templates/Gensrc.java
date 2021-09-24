/*
 * Copyright 2021 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.*;
import static java.util.regex.Pattern.compile;

/**
 * Codegen script for {@code jetbrains.api} module.
 * It produces "main" {@link com.jetbrains.JBR} class from template by
 * inspecting interfaces and implementation code and inserting
 * static utility methods for public services as well as some metadata
 * needed by JBR at runtime.
 */
public class Gensrc {

    private static Path srcroot, module, src, templates, gensrc;
    private static JBRModules modules;

    /**
     * <ul>
     *     <li>$0 - absolute path to {@code JetBrainsRuntime/src} dir</li>
     *     <li>$1 - absolute path to jbr-api output dir ({@code JetBrainsRuntime/build/<conf>/jbr-api})</li>
     * </ul>
     */
    public static void main(String[] args) throws IOException {
        srcroot = Path.of(args[0]);
        module = srcroot.resolve("jetbrains.api");
        src = module.resolve("src");
        templates = module.resolve("templates");
        Path output = Path.of(args[1]);
        gensrc = output.resolve("gensrc");
        modules = new JBRModules();
        JBR.generate();
    }

    private static String findRegex(String src, Pattern regex) {
        Matcher matcher = regex.matcher(src);
        if (!matcher.find()) throw new IllegalArgumentException("Regex not found: " + regex.pattern());
        return matcher.group(1);
    }

    private static String replaceTemplate(String src, String placeholder, Iterable<String> statements) {
        int placeholderIndex = src.indexOf(placeholder);
        int indent = 0;
        while (placeholderIndex - indent >= 1 && src.charAt(placeholderIndex - indent - 1) == ' ') indent++;
        int nextLineIndex = src.indexOf('\n', placeholderIndex + placeholder.length()) + 1;
        if (nextLineIndex == 0) nextLineIndex = placeholderIndex + placeholder.length();
        String before = src.substring(0, placeholderIndex - indent), after = src.substring(nextLineIndex);
        StringBuilder sb = new StringBuilder(before);
        boolean firstStatement = true;
        for (String s : statements) {
            if (!firstStatement) sb.append('\n');
            sb.append(s.indent(indent));
            firstStatement = false;
        }
        sb.append(after);
        return sb.toString();
    }

    /**
     * Code for generating {@link com.jetbrains.JBR} class.
     */
    private static class JBR {

        private static void generate() throws IOException {
            String jbrFileName = "com/jetbrains/JBR.java";
            Path output = gensrc.resolve(jbrFileName);
            Files.createDirectories(output.getParent());
            String content = generate(Files.readString(templates.resolve(jbrFileName)));
            Files.writeString(output, content, CREATE, WRITE, TRUNCATE_EXISTING);
        }

        private static String generate(String content) throws IOException {
            Service[] interfaces = findPublicServiceInterfaces();
            List<String> statements = new ArrayList<>();
            for (Service i : interfaces) statements.add(generateMethodsForService(i));
            content = replaceTemplate(content, "/*GENERATED_METHODS*/", statements);
            content = content.replace("/*KNOWN_SERVICES*/",
                    modules.services.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")));
            content = content.replace("/*KNOWN_PROXIES*/",
                    modules.proxies.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")));
            content = content.replace("/*API_VERSION*/", getApiVersion());
            return content;
        }

        private static String getApiVersion() throws IOException {
            Properties props = new Properties();
            props.load(Files.newInputStream(module.resolve("version.properties")));
            return props.getProperty("VERSION");
        }

        private static Service[] findPublicServiceInterfaces() {
            Pattern javadocPattern = Pattern.compile("/\\*\\*((?:.|\n)*?)(\s|\n)*\\*/");
            return modules.services.stream()
                    .map(fullName -> {
                        if (fullName.indexOf('$') != -1) return null; // Only top level services can be public
                        Path path = src.resolve(fullName.replace('.', '/') + ".java");
                        String name = fullName.substring(fullName.lastIndexOf('.') + 1);
                        try {
                            String content = Files.readString(path);
                            int indexOfDeclaration = content.indexOf("public interface " + name);
                            if (indexOfDeclaration == -1) return null;
                            Matcher javadoc = javadocPattern.matcher(content.substring(0, indexOfDeclaration));
                            return new Service(name, javadoc.find() ? javadoc.group(1) : "");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .filter(Objects::nonNull).toArray(Service[]::new);
        }

        private static String generateMethodsForService(Service service) {
            return """
                    private static class $__Holder {
                        private static final $ INSTANCE = api != null ? api.getService($.class) : null;
                    }
                    /**
                     * @return true if current runtime has implementation for all methods in {@link $}
                     * and its dependencies (can fully implement given service).
                     * @see #get$()
                     */
                    public static boolean is$Supported() {
                        return $__Holder.INSTANCE != null;
                    }
                    /**<JAVADOC>
                     * @return full implementation of $ service if any, or null otherwise
                     * @see #is$Supported()
                     */
                    public static $ get$() {
                        return $__Holder.INSTANCE;
                    }
                    """
                    .replaceAll("\\$", service.name)
                    .replace("<JAVADOC>", service.javadoc);
        }

        private record Service(String name, String javadoc) {}
    }

    /**
     * Finds and analyzes JBR API implementation modules and collects proxy definitions.
     */
    private static class JBRModules {

        private final Set<String> proxies = new HashSet<>(), services = new HashSet<>();

        private JBRModules() throws IOException {
            String[] moduleNames = findJBRApiModules();
            Path[] potentialModules = findPotentialJBRApiContributorModules();
            for (String moduleName : moduleNames) {
                Path module = findJBRApiModuleFile(moduleName, potentialModules);
                findInModule(Files.readString(module));
            }
        }

        private void findInModule(String content) {
            Pattern servicePattern = compile("(service|proxy)\s*\\(([^)]+)");
            Matcher matcher = servicePattern.matcher(content);
            while (matcher.find()) {
                String type = matcher.group(1);
                String parameters = matcher.group(2);
                String interfaceName = extractFromStringLiteral(parameters.substring(0, parameters.indexOf(',')));
                if (type.equals("service")) services.add(interfaceName);
                else proxies.add(interfaceName);
            }
        }

        private static String extractFromStringLiteral(String value) {
            value = value.strip();
            return value.substring(1, value.length() - 1);
        }

        private static Path findJBRApiModuleFile(String module, Path[] potentialPaths) throws FileNotFoundException {
            for (Path p : potentialPaths) {
                Path m = p.resolve("share/classes").resolve(module + ".java");
                if (Files.exists(m)) return m;
            }
            throw new FileNotFoundException("JBR API module file not found: " + module);
        }

        private static String[] findJBRApiModules() throws IOException {
            String bootstrap = Files.readString(
                    srcroot.resolve("java.base/share/classes/com/jetbrains/bootstrap/JBRApiBootstrap.java"));
            Pattern modulePattern = compile("\"([^\"]+)");
            return Stream.of(findRegex(bootstrap, compile("MODULES *=([^;]+)")).split(","))
                    .map(m -> findRegex(m, modulePattern).replace('.', '/')).toArray(String[]::new);
        }

        private static Path[] findPotentialJBRApiContributorModules() throws IOException {
            return Files.list(srcroot)
                    .filter(p -> Files.exists(p.resolve("share/classes/com/jetbrains"))).toArray(Path[]::new);
        }
    }
}
