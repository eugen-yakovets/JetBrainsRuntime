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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.file.StandardOpenOption.*;

public class Gensrc {

    private static Path src, templates, gensrc;

    /**
     * $0 - absolute path to jetbrains.api module
     * $1 - absolute path to generated sources dir
     */
    public static void main(String[] args) throws IOException {
        Path module = Path.of(args[0]);
        src = module.resolve("src");
        templates = module.resolve("templates");
        gensrc = Path.of(args[1]);
        JBR.generate();
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

    private static class JBR {
        private static void generate() throws IOException {
            String jbrFileName = "com/jetbrains/JBR.java";
            Path output = gensrc.resolve(jbrFileName);
            Files.createDirectories(output.getParent());
            String content = generate(Files.readString(templates.resolve(jbrFileName)));
            Files.writeString(output, content, CREATE, WRITE, TRUNCATE_EXISTING);
        }

        private static String generate(String content) throws IOException {
            Service[] interfaces = findServiceInterfaces();
            List<String> statements = new ArrayList<>();
            for (Service i : interfaces) statements.add(generateMethods(i));
            return replaceTemplate(content, "/*GENERATED_METHODS*/", statements);
        }

        private static Service[] findServiceInterfaces() throws IOException {
            Pattern javadocPattern = Pattern.compile("/\\*\\*((?:.|\n)*?)(\s|\n)*\\*/");
            return Files.list(src.resolve("com/jetbrains")).map(p -> {
                try {
                    String name = p.getFileName().toString().replace(".java", "");
                    String content = Files.readString(p);
                    int indexOfDeclaration = content.indexOf("public interface " + name);
                    if (indexOfDeclaration == -1) return null;
                    Matcher javadoc = javadocPattern.matcher(content.substring(0, indexOfDeclaration));
                    return new Service(name, javadoc.find() ? javadoc.group(1) : "");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).filter(Objects::nonNull).toArray(Service[]::new);
        }

        private static String generateMethods(Service service) {
            return """
                    private static class $__Holder {
                        private static final $ INSTANCE = api == null ? null : api.getService($.class);
                    }
                    /**
                     * @return true if current runtime has implementation for all methods in {@link $}
                     * (can fully implement given service).
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
                    """.replaceAll("\\$", service.name).replace("<JAVADOC>", service.javadoc);
        }

        private record Service(String name, String javadoc) {}
    }
}
