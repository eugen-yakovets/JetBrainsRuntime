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

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;

import static java.lang.invoke.MethodHandles.Lookup;

/**
 * Proxy info, like {@link RegisteredProxyInfo}, but with all classes and lookup contexts resolved.
 * Contains all necessary information to create a {@linkplain Proxy proxy}.
 */
class ProxyInfo {

    final Lookup apiModule;
    final Type type;
    final Lookup interFaceLookup;
    final Class<?> interFace;
    final Lookup target;
    final Map<String, StaticMethodMapping> staticMethods = new HashMap<>();

    private ProxyInfo(RegisteredProxyInfo i) {
        this.apiModule = i.apiModule();
        type = i.type();
        interFaceLookup = lookup(getInterfaceLookup(), i.interfaceName());
        interFace = interFaceLookup == null ? null : interFaceLookup.lookupClass();
        target = i.target() == null ? null : lookup(getTargetLookup(), i.target());
        for (RegisteredProxyInfo.StaticMethodMapping m : i.staticMethods()) {
            Lookup l = lookup(getTargetLookup(), m.clazz());
            if (l != null) {
                staticMethods.put(m.interfaceMethodName(), new StaticMethodMapping(l, m.methodName()));
            }
        }
    }

    /**
     * Resolve all classes and lookups for given {@link RegisteredProxyInfo}.
     */
    static ProxyInfo resolve(RegisteredProxyInfo i) {
        ProxyInfo info = new ProxyInfo(i);
        if (info.interFace == null || (info.target == null && info.staticMethods.isEmpty())) return null;
        if (!info.interFace.isInterface()) {
            if (info.type == Type.CLIENT_PROXY) {
                throw new RuntimeException("Tried to create client proxy for non-interface: " + info.interFace);
            } else {
                return null;
            }
        }
        return info;
    }

    Lookup getInterfaceLookup() {
        return type == Type.CLIENT_PROXY ? apiModule : JBRApi.outerLookup;
    }

    Lookup getTargetLookup() {
        return type == Type.CLIENT_PROXY ? JBRApi.outerLookup : apiModule;
    }

    private Lookup lookup(Lookup lookup, String clazz) {
        try {
            return MethodHandles.privateLookupIn(lookup.findClass(clazz), lookup);
        } catch (ClassNotFoundException | IllegalAccessException e) {
            if (lookup == JBRApi.outerLookup) return null;
            else throw new RuntimeException(e);
        }
    }

    record StaticMethodMapping(Lookup lookup, String methodName) {}

    /**
     * Proxy type, see {@link Proxy}
     */
    enum Type {
        PROXY,
        SERVICE,
        CLIENT_PROXY
    }
}
