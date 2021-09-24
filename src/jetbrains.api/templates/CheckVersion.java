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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

public class CheckVersion {

    private static Path module, gensrc;

    /**
     * <ul>
     *     <li>$0 - absolute path to {@code JetBrainsRuntime/src/jetbrains.api} dir</li>
     *     <li>$1 - absolute path to jbr-api output dir ({@code JetBrainsRuntime/build/<conf>/jbr-api})</li>
     * </ul>
     */
    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        module = Path.of(args[0]);
        Path output = Path.of(args[1]);
        gensrc = output.resolve("gensrc");
        Path bin = output.resolve("bin");
        Path versionFile = module.resolve("version.properties");

        Properties props = new Properties();
        props.load(Files.newInputStream(versionFile));
        String hash = SourceHash.calculate();

        if (hash.equals(props.getProperty("HASH"))) {
            Files.writeString(bin.resolve("jbr-api.version"), props.getProperty("VERSION"),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }
        System.err.println("================================================================================");
        System.err.println("Error: jetbrains.api code was changed, update hash and increment version in " + versionFile);
        System.err.println("HASH = " + hash);
        System.err.println("================================================================================");
        System.exit(-1);
    }

    private static class SourceHash {

        private static String calculate() throws NoSuchAlgorithmException, IOException {
            MessageDigest hash = MessageDigest.getInstance("MD5");
            calculate(module.resolve("src"), hash);
            calculate(gensrc, hash);
            byte[] digest = hash.digest();
            StringBuilder result = new StringBuilder();
            for (byte b : digest) {
                result.append(String.format("%X", b));
            }
            return result.toString();
        }

        private static void calculate(Path dir, MessageDigest hash) throws IOException {
            for (Entry f : findFiles(dir)) {
                hash.update(f.name.getBytes(StandardCharsets.UTF_8));
                hash.update(Files.readString(f.path).getBytes(StandardCharsets.UTF_8));
            }
        }

        private static List<Entry> findFiles(Path dir) throws IOException {
            List<Entry> files = new ArrayList<>();
            FileVisitor<Path> fileFinder = new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path abs = file.toAbsolutePath();
                    Path rel = dir.relativize(abs);
                    StringBuilder name = new StringBuilder();
                    for (int i = 0; i < rel.getNameCount(); i++) {
                        if (!name.isEmpty()) name.append('/');
                        name.append(rel.getName(i));
                    }
                    files.add(new Entry(abs, name.toString()));
                    return FileVisitResult.CONTINUE;
                }
            };
            Files.walkFileTree(dir, fileFinder);
            files.sort(Comparator.comparing(Entry::name));
            return files;
        }

        private record Entry(Path path, String name) {}
    }
}
