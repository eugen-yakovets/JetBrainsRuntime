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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

import static java.lang.invoke.MethodHandles.Lookup;

public class JBRApi {

    private static final Map<String, RegisteredProxyInfo> registeredProxyInfoByInterfaceName = new HashMap<>();
    private static final Map<String, RegisteredProxyInfo> registeredProxyInfoByTargetName = new HashMap<>();
    private static final ConcurrentMap<Class<?>, Proxy<?>> proxyByInterface = new ConcurrentHashMap<>();

    static Lookup outerLookup;
    static Set<String> knownServices, knownProxies;

    public static void init(Lookup outerLookup) {
        JBRApi.outerLookup = outerLookup;
        try {
            Class<?> metadataClass = outerLookup.findClass("com.jetbrains.JBR$Metadata");
            knownServices = Set.of((String[]) outerLookup.findStaticVarHandle(metadataClass,
                    "KNOWN_SERVICES", String[].class).get());
            knownProxies = Set.of((String[]) outerLookup.findStaticVarHandle(metadataClass,
                    "KNOWN_PROXIES", String[].class).get());
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            knownServices = Set.of();
            knownProxies = Set.of();
        }
    }

    public static <T> T getService(Class<T> interFace) {
        Proxy<T> p = getProxy(interFace);
        return p.isFullySupported() ? p.getInstance() : null;
    }

    public static <T> T getServicePartialSupport(Class<T> interFace) {
        return getProxy(interFace).getInstance();
    }

    @SuppressWarnings("unchecked")
    public static <T> Proxy<T> getProxy(Class<T> interFace) {
        return (Proxy<T>) proxyByInterface.computeIfAbsent(interFace, i -> {
            RegisteredProxyInfo info = registeredProxyInfoByInterfaceName.get(i.getName());
            if (info == null) return Proxy.NULL;
            ProxyInfo resolved = ProxyInfo.resolve(info);
            return resolved != null ? new Proxy.Impl<T>(resolved) : Proxy.NULL;
        });
    }

    public static ModuleRegistry registerModule(Lookup lookup, BiFunction<String, Module, Module> addExports) {
        addExports.apply(lookup.lookupClass().getPackageName(), outerLookup.lookupClass().getModule());
        return new ModuleRegistry(lookup);
    }

    static boolean isKnownProxyInterface(Class<?> clazz) {
        String name = clazz.getName();
        return registeredProxyInfoByInterfaceName.containsKey(name) ||
                knownServices.contains(name) || knownProxies.contains(name);
    }

    static Class<?> getProxyInterfaceByTargetName(String targetName) {
        RegisteredProxyInfo info = registeredProxyInfoByTargetName.get(targetName);
        if (info == null) return null;
        try {
            return (info.type() == ProxyInfo.Type.CLIENT_PROXY ? info.apiModule() : outerLookup)
                    .findClass(info.interfaceName());
        } catch (ClassNotFoundException | IllegalAccessException e) {
            return null;
        }
    }

    public static class ModuleRegistry {

        private final Lookup lookup;
        private RegisteredProxyInfo lastProxy;

        private ModuleRegistry(Lookup lookup) {
            this.lookup = lookup;
        }

        private ModuleRegistry addProxy(String interfaceName, String target, ProxyInfo.Type type) {
            lastProxy = new RegisteredProxyInfo(lookup, interfaceName, target, type, new ArrayList<>());
            registeredProxyInfoByInterfaceName.put(interfaceName, lastProxy);
            if (target != null) registeredProxyInfoByTargetName.put(target, lastProxy);
            return this;
        }

        public ModuleRegistry proxy(String interfaceName, String target) {
            Objects.requireNonNull(target);
            return addProxy(interfaceName, target, ProxyInfo.Type.PROXY);
        }

        public ModuleRegistry service(String interfaceName, String target) {
            return addProxy(interfaceName, target, ProxyInfo.Type.SERVICE);
        }

        public ModuleRegistry clientProxy(String interfaceName, String target) {
            Objects.requireNonNull(target);
            return addProxy(interfaceName, target, ProxyInfo.Type.CLIENT_PROXY);
        }

        public ModuleRegistry withStatic(String methodName, String clazz) {
            return withStatic(methodName, clazz, methodName);
        }

        public ModuleRegistry withStatic(String interfaceMethodName, String clazz, String methodName) {
            lastProxy.staticMethods().add(
                    new RegisteredProxyInfo.StaticMethodMapping(interfaceMethodName, clazz, methodName));
            return this;
        }
    }
}
