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

public class Proxy<INTERFACE> {
    static final Proxy<?> NULL = new Proxy<>();

    public boolean areAllMethodsImplemented() { return false; }
    public boolean isFullySupported() { return false; }
    public INTERFACE newInstance(Object target) { return null; }
    public INTERFACE getInstance() { return null; }

    static class Impl<INTERFACE> extends Proxy<INTERFACE> {
        private final ProxyInfo info;

        private volatile ProxyGenerator generator;
        private volatile Boolean allMethodsImplemented;

        private volatile Boolean fullySupported;

        private volatile MethodHandle constructor;

        private volatile INTERFACE instance;

        Impl(ProxyInfo info) {
            this.info = info;
        }

        private void initGenerator() {
            if (generator != null) return;
            generator = new ProxyGenerator(info);
            allMethodsImplemented = generator.areAllMethodsImplemented();
        }

        @Override
        public boolean areAllMethodsImplemented() {
            if (allMethodsImplemented != null) return allMethodsImplemented;
            synchronized (this) {
                if (allMethodsImplemented == null) initGenerator();
                return allMethodsImplemented;
            }
        }

        @Override
        public boolean isFullySupported() {
            if (fullySupported != null) return fullySupported;
            synchronized (this) {
                if (fullySupported == null) {
                    for (Class<?> d : ProxyDependencyManager.getDependencies(info.interFace)) {
                        if (!JBRApi.getProxy(d).areAllMethodsImplemented()) {
                            fullySupported = false;
                            return false;
                        }
                    }
                    fullySupported = true;
                }
                return fullySupported;
            }
        }

        private MethodHandle getConstructor() {
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

        @SuppressWarnings("unchecked")
        @Override
        public INTERFACE newInstance(Object target) {
            if (info.type == ProxyInfo.Type.SERVICE) return null;
            try {
                return (INTERFACE) getConstructor().invoke(target);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public INTERFACE getInstance() {
            if (instance != null) return instance;
            if (info.type != ProxyInfo.Type.SERVICE) return null;
            synchronized (this) {
                if (instance == null) {
                    try {
                        instance = (INTERFACE) getConstructor().invoke();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }
                return instance;
            }
        }
    }
}