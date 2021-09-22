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

package com.jetbrains.internal;

import java.lang.reflect.*;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

class ProxyDependencyManager {

    private static final ConcurrentMap<Class<?>, Set<Class<?>>> cache = new ConcurrentHashMap<>();

    /**
     * @return all proxy interfaces that are used (directly or indirectly) by given interface, including itself
     */
    static Set<Class<?>> getDependencies(Class<?> interFace) {
        Set<Class<?>> dependencies = cache.get(interFace);
        if (dependencies != null) return dependencies;
        step(null, interFace);
        return cache.get(interFace);
    }

    private static void step(Node parent, Class<?> clazz) {
        if (!clazz.getPackageName().startsWith("com.jetbrains")) return;
        if (parent != null && parent.findAndMergeCycle(clazz) != null) {
            return;
        }
        Set<Class<?>> cachedDependencies = cache.get(clazz);
        if (cachedDependencies != null) {
            if (parent != null) parent.cycle.dependencies.addAll(cachedDependencies);
            return;
        }
        Node node = new Node(parent, clazz);
        ClassUsagesFinder.visitUsages(clazz, c -> step(node, c));
        Class<?> correspondingProxyInterface = JBRApi.getProxyInterfaceByTargetName(clazz.getName());
        if (correspondingProxyInterface != null) {
            step(node, correspondingProxyInterface);
        }
        if (parent != null && parent.cycle != node.cycle) {
            parent.cycle.dependencies.addAll(node.cycle.dependencies);
        }
        if (node.cycle.origin.equals(clazz)) {
            for (Class<?> c : node.cycle.members) {
                cache.put(c, node.cycle.dependencies);
            }
        }
    }

    private static class Node {
        private final Node parent;
        private final Class<?> clazz;
        private Cycle cycle;

        private Node(Node parent, Class<?> clazz) {
            this.parent = parent;
            this.clazz = clazz;
            cycle = new Cycle(clazz);
        }

        private Cycle findAndMergeCycle(Class<?> clazz) {
            if (this.clazz.equals(clazz)) return cycle;
            if (parent == null) return null;
            Cycle c = parent.findAndMergeCycle(clazz);
            if (c != null && c != cycle) {
                c.members.addAll(cycle.members);
                c.dependencies.addAll(cycle.dependencies);
                cycle = c;
            }
            return c;
        }
    }

    private static class Cycle {
        private final Class<?> origin;
        private final Set<Class<?>> members = new HashSet<>();
        private final Set<Class<?>> dependencies = new HashSet<>();

        private Cycle(Class<?> origin) {
            this.origin = origin;
            members.add(origin);
            if (JBRApi.isKnownProxyInterface(origin)) {
                dependencies.add(origin);
            }
        }
    }

    private static class ClassUsagesFinder {

        private static void visitUsages(Class<?> c, Consumer<Class<?>> action) {
            collect(c.getGenericSuperclass(), action);
            for (java.lang.reflect.Type t : c.getGenericInterfaces()) collect(t, action);
            for (Field f : c.getDeclaredFields()) collect(f.getGenericType(), action);
            for (Method m : c.getDeclaredMethods()) {
                collect(m.getGenericParameterTypes(), action);
                collect(m.getGenericReturnType(), action);
                collect(m.getGenericExceptionTypes(), action);
            }
        }

        private static void collect(java.lang.reflect.Type type, Consumer<Class<?>> action) {
            if (type instanceof Class<?> c) {
                while (c.isArray()) c = Objects.requireNonNull(c.getComponentType());
                if (!c.isPrimitive()) action.accept(c);
            } else if (type instanceof TypeVariable<?> v) {
                collect(v.getBounds(), action);
            } else if (type instanceof WildcardType w) {
                collect(w.getUpperBounds(), action);
                collect(w.getLowerBounds(), action);
            } else if (type instanceof ParameterizedType p) {
                collect(p.getActualTypeArguments(), action);
                collect(p.getRawType(), action);
                collect(p.getOwnerType(), action);
            } else if (type instanceof GenericArrayType a) {
                collect(a.getGenericComponentType(), action);
            }
        }

        private static void collect(java.lang.reflect.Type[] types, Consumer<Class<?>> action) {
            for (java.lang.reflect.Type t : types) collect(t, action);
        }
    }
}
