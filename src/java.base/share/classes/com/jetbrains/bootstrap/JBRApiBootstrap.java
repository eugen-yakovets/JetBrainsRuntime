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

package com.jetbrains.bootstrap;

import com.jetbrains.internal.JBRApi;
import jdk.internal.loader.ClassLoaders;

import java.lang.invoke.MethodHandles;

public class JBRApiBootstrap {
    private JBRApiBootstrap() {}

    private static final String[] MODULES = {
            "com.jetbrains.base.JBRApiModule",
            "com.jetbrains.desktop.JBRApiModule"
    };

    public static synchronized Object bootstrap(MethodHandles.Lookup outerLookup, Class<?> jbrApiClass) {
        if (!jbrApiClass.getPackageName().equals("com.jetbrains") ||
                !jbrApiClass.getModule().getName().equals("jetbrains.api")) {
            throw new IllegalArgumentException("Invalid JBR API class: " + jbrApiClass.getName());
        }
        if (!outerLookup.hasFullPrivilegeAccess() ||
                outerLookup.lookupClass().getPackage() != jbrApiClass.getPackage()) {
            throw new IllegalArgumentException("Lookup must be full-privileged from the com.jetbrains package: " +
                    outerLookup.lookupClass().getName());
        }
        JBRApi.outerLookup = outerLookup;
        ClassLoader classLoader = ClassLoaders.platformClassLoader();
        try {
            for (String m : MODULES) Class.forName(m, true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new Error(e);
        }
        return JBRApi.getService(jbrApiClass);
    }

}
