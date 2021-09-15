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

import jdk.internal.org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import static jdk.internal.org.objectweb.asm.Opcodes.*;
import static java.lang.invoke.MethodHandles.Lookup;

class ASMUtils {

    private static final MethodHandle genericSignatureGetter;
    static {
        try {
            genericSignatureGetter = MethodHandles.privateLookupIn(Method.class, MethodHandles.lookup())
                    .findVirtual(Method.class, "getGenericSignature", MethodType.methodType(String.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new Error(e);
        }
    }

    protected record InternalMethodInfo(String name, String descriptor, String genericSignature,
                              Class<?>[] parameterTypes, Class<?> returnType, String[] exceptionNames) {}

    public static InternalMethodInfo getInternalMethodInfo(Method method) {
        try {
            return new InternalMethodInfo(
                    method.getName(),
                    Type.getMethodDescriptor(method),
                    (String) genericSignatureGetter.invoke(method),
                    method.getParameterTypes(),
                    method.getReturnType(),
                    getExceptionNames(method));
        } catch (Throwable e) {
            throw new Error(e);
        }
    }

    public static InternalMethodInfo getBridgeMethodInfo(InternalMethodInfo info, String targetDescriptor) {
        int sigInsertIndex = info.genericSignature() == null ? -1 : info.genericSignature().indexOf('(') + 1;
        return new InternalMethodInfo(
                info.name,
                "(" + targetDescriptor + info.descriptor.substring(1),
                sigInsertIndex == -1 ? null : info.genericSignature().substring(0, sigInsertIndex) +
                        targetDescriptor + info.genericSignature().substring(sigInsertIndex),
                info.parameterTypes(),
                info.returnType(),
                info.exceptionNames());
    }

    private static String[] getExceptionNames(Method method) {
        Class<?>[] exceptionTypes = method.getExceptionTypes();
        String[] exceptionNames = new String[exceptionTypes.length];
        for (int i = 0; i < exceptionTypes.length; i++) {
            exceptionNames[i] = Type.getInternalName(exceptionTypes[i]);
        }
        return exceptionNames;
    }

    public static int getParameterSize(Class<?> c) {
        if (c == Void.TYPE) {
            return 0;
        } else if (c == Long.TYPE || c == Double.TYPE) {
            return 2;
        }
        return 1;
    }

    public static int getLoadOpcode(Class<?> c) {
        if (c == Void.TYPE) {
            throw new InternalError("Unexpected void type of load opcode");
        }
        return ILOAD + getOpcodeOffset(c);
    }

    public static int getReturnOpcode(Class<?> c) {
        if (c == Void.TYPE) {
            return RETURN;
        }
        return IRETURN + getOpcodeOffset(c);
    }

    public static int getOpcodeOffset(Class<?> c) {
        if (c.isPrimitive()) {
            if (c == Long.TYPE) {
                return 1;
            } else if (c == Float.TYPE) {
                return 2;
            } else if (c == Double.TYPE) {
                return 3;
            }
            return 0;
        } else {
            return 4;
        }
    }
}
