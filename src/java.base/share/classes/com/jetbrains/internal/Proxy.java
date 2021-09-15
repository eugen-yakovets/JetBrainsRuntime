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

import java.lang.invoke.MethodHandle;

class Proxy {
    static final Proxy NULL = new Proxy(null, null) {
        @Override
        boolean areAllMethodsImplemented() { return false; }
        @Override
        MethodHandle getConstructor() { return null; }
        @Override
        Object getInstance() { return null; }
    };

    private final ProxyDescriptor descriptor;
    private final Class<?> interFace;

    private volatile ProxyGenerator generator;
    private volatile Boolean allMethodsImplemented;

    private volatile MethodHandle constructor;

    private volatile Object instance;

    Proxy(ProxyDescriptor descriptor, Class<?> interFace) {
        this.descriptor = descriptor;
        this.interFace = interFace;
    }

    private void initGenerator() {
        if (generator != null) return;
        generator = new ProxyGenerator(interFace, descriptor);
        allMethodsImplemented = generator.areAllMethodsImplemented();
    }

    boolean areAllMethodsImplemented() {
        if (allMethodsImplemented != null) return allMethodsImplemented;
        synchronized (this) {
            if (allMethodsImplemented == null) initGenerator();
            return allMethodsImplemented;
        }
    }

    MethodHandle getConstructor() {
        if (constructor != null) return constructor;
        synchronized (this) {
            if (constructor == null) {
                initGenerator();
                constructor = generator.generate();
                generator = null;
            }
            return constructor;
        }
    }

    Object getInstance() {
        if (instance != null) return instance;
        if (!descriptor.service) return null;
        synchronized (this) {
            if (instance == null) {
                try {
                    instance = getConstructor().invoke();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            }
            return instance;
        }
    }
}
