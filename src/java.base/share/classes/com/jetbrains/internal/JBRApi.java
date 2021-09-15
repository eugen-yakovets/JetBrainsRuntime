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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

import static java.lang.invoke.MethodHandles.Lookup;

public class JBRApi {

    private static final Map<String, ProxyDescriptor> proxyDescriptors = new HashMap<>();
    private static final ConcurrentMap<Class<?>, Proxy> proxies = new ConcurrentHashMap<>();

    public static Lookup outerLookup;

    public static ModuleRegistry registerModule(Lookup lookup, BiFunction<String, Module, Module> addExports) {
        addExports.apply(lookup.lookupClass().getPackageName(), outerLookup.lookupClass().getModule());
        return new ModuleRegistry(lookup);
    }

    @SuppressWarnings("unchecked")
    public static <T> T getService(Class<T> interFace) {
        Proxy p = getProxy(interFace);
        return p.areAllMethodsImplemented() ? (T) p.getInstance() : null;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getServicePartialSupport(Class<T> interFace) {
        return (T) getProxy(interFace).getInstance();
    }

    private static Proxy getProxy(Class<?> interFace) {
        return proxies.computeIfAbsent(interFace, i -> {
            ProxyDescriptor descriptor = proxyDescriptors.get(interFace.getName());
            return descriptor == null ? Proxy.NULL : new Proxy(descriptor, i);
        });
    }

    public static class ModuleRegistry {

        private final Lookup lookup;
        private ProxyDescriptor lastProxy;

        private ModuleRegistry(Lookup lookup) {
            this.lookup = lookup;
        }

        private ModuleRegistry addProxy(String interfaceName, String target, boolean singleton) {
            lastProxy = new ProxyDescriptor(lookup, interfaceName, target, singleton);
            proxyDescriptors.put(interfaceName, lastProxy);
            return this;
        }

        public ModuleRegistry proxy(String interfaceName, String target) {
            Objects.requireNonNull(target);
            return addProxy(interfaceName, target, false);
        }

        public ModuleRegistry service(String interfaceName, String target) {
            return addProxy(interfaceName, target, true);
        }

        public ModuleRegistry withStatic(String methodName, String clazz) {
            return withStatic(methodName, clazz, methodName);
        }

        public ModuleRegistry withStatic(String interfaceMethodName, String clazz, String methodName) {
            lastProxy.addStatic(interfaceMethodName, clazz, methodName);
            return this;
        }
    }
}
