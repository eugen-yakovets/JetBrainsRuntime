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

class ProxyDescriptor {

    final String interfaceName;
    final boolean service;
    final Map<String, StaticMethodMapping> staticMethods = new HashMap<>();
    final MethodHandles.Lookup target;
    final MethodHandles.Lookup apiModule;

    ProxyDescriptor(MethodHandles.Lookup apiModule, String interfaceName, String target, boolean service) {
        this.apiModule = apiModule;
        this.interfaceName = interfaceName;
        this.service = service;
        this.target = target == null ? null : look(target);
    }

    private MethodHandles.Lookup look(String clazz) {
        try {
            return MethodHandles.privateLookupIn(
                    Class.forName(clazz, false, apiModule.lookupClass().getClassLoader()), apiModule);
        } catch (IllegalAccessException | ClassNotFoundException e) {
            throw new Error(e);
        }
    }

    void addStatic(String interfaceMethodName, String clazz, String methodName) {
        staticMethods.put(interfaceMethodName, new StaticMethodMapping(look(clazz), methodName));
    }

    record StaticMethodMapping(MethodHandles.Lookup lookup, String methodName) {}
}
