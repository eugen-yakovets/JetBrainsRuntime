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

import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.invoke.MethodHandles.Lookup;
import static jdk.internal.org.objectweb.asm.Opcodes.*;
import static com.jetbrains.internal.ASMUtils.*;

class ProxyGenerator {

    private static final AtomicInteger nameCounter = new AtomicInteger();

    private final ClassWriter proxyWriter, bridgeWriter;
    private final Class<?> interFace;
    private final ProxyDescriptor descriptor;
    private final String proxyName, bridgeName, targetDescriptor;
    private final List<MethodHandle> handles = new ArrayList<>();
    private final List<Exception> exceptions = new ArrayList<>();
    private boolean allMethodsImplemented = true;

    ProxyGenerator(Class<?> interFace, ProxyDescriptor descriptor) {
        this.interFace = interFace;
        this.descriptor = descriptor;
        int nameId = nameCounter.getAndIncrement();
        proxyName = JBRApi.outerLookup.lookupClass().getPackageName().replace('.', '/') + "/" +
                interFace.getSimpleName() + "$$JBRApiProxy$" + nameId;
        bridgeName = descriptor.apiModule.lookupClass().getPackageName().replace('.', '/') + "/" +
                interFace.getSimpleName() + "$$JBRApiBridge$" + nameId;
        targetDescriptor = descriptor.target == null ? "" :
                "L" + Type.getInternalName(descriptor.target.lookupClass()) + ";";

        proxyWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        bridgeWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        proxyWriter.visit(61, ACC_SUPER | ACC_FINAL | ACC_SYNTHETIC, proxyName, null,
                "java/lang/Object", new String[] {Type.getInternalName(interFace)});
        bridgeWriter.visit(61, ACC_SUPER | ACC_FINAL | ACC_SYNTHETIC | ACC_PUBLIC, bridgeName, null,
                "java/lang/Object", null);
        generateConstructor();
        generateMethods();
    }

    boolean areAllMethodsImplemented() {
        return allMethodsImplemented;
    }

    MethodHandle generate() {
        try {
            return findConstructor(defineClasses());
        } catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private Lookup defineClasses() throws IllegalAccessException, NoSuchFieldException {
        Class<?> bridge = descriptor.apiModule.defineClass(bridgeWriter.toByteArray());
        for (int i = 0; i < handles.size(); i++) {
            Field f = bridge.getDeclaredField("h" + i);
            f.setAccessible(true);
            f.set(null, handles.get(i));
        }
        return JBRApi.outerLookup.defineHiddenClass(proxyWriter.toByteArray(), true, Lookup.ClassOption.STRONG);
    }

    /**
     * Proxy constructor is no-arg for services and single-arg for proxies with its target type
     */
    private MethodHandle findConstructor(Lookup proxy) throws NoSuchMethodException, IllegalAccessException {
        if (descriptor.target == null) {
            return proxy.findConstructor(proxy.lookupClass(), MethodType.methodType(void.class));
        } else {
            MethodHandle c = proxy.findConstructor(proxy.lookupClass(), MethodType.methodType(void.class, descriptor.target.lookupClass()));
            if (descriptor.service) {
                return MethodHandles.foldArguments(c,
                        descriptor.target.findConstructor(descriptor.target.lookupClass(), MethodType.methodType(void.class)));
            }
            return c;
        }
    }

    private void generateConstructor() {
        if (descriptor.target != null) {
            proxyWriter.visitField(ACC_PRIVATE | ACC_FINAL, "target", targetDescriptor, null, null);
        }
        MethodVisitor p = proxyWriter.visitMethod(ACC_PRIVATE, "<init>", "(" + targetDescriptor + ")V", null, null);
        p.visitVarInsn(ALOAD, 0);
        if (descriptor.target != null) {
            p.visitInsn(DUP);
            p.visitVarInsn(ALOAD, 1);
            p.visitFieldInsn(PUTFIELD, proxyName, "target", targetDescriptor);
        }
        p.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        p.visitInsn(RETURN);
        p.visitMaxs(-1, -1);
    }

    private void generateMethods() {
        for (Method method : interFace.getMethods()) {
            int mod = method.getModifiers();
            if (!Modifier.isAbstract(mod)) continue;
            MethodType methodType = MethodType.methodType(method.getReturnType(), method.getParameterTypes());

            Exception e1 = null;
            if (descriptor.target != null) {
                try {
                    MethodHandle handle = descriptor.target.findVirtual(
                            descriptor.target.lookupClass(), method.getName(), methodType);
                    generateMethod(method, handle, true);
                    continue;
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    e1 = e;
                }
            }

            Exception e2 = null;
            ProxyDescriptor.StaticMethodMapping mapping = descriptor.staticMethods.get(method.getName());
            if (mapping != null) {
                try {
                    MethodHandle staticHandle =
                            mapping.lookup().findStatic(mapping.lookup().lookupClass(), mapping.methodName(), methodType);
                    if (staticHandle != null) {
                        generateMethod(method, staticHandle, false);
                        continue;
                    }
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    e2 = e;
                }
            }

            if (e1 != null) exceptions.add(e1);
            if (e2 != null) exceptions.add(e2);
            generateUnsupportedMethod(method);
            allMethodsImplemented = false;
        }
    }

    private void generateMethod(Method interfaceMethod, MethodHandle handle, boolean passTarget) {
        InternalMethodInfo methodInfo = getInternalMethodInfo(interfaceMethod);
        InternalMethodInfo bridgeMethodInfo = passTarget ? getBridgeMethodInfo(methodInfo, targetDescriptor) : methodInfo;
        String handleName = "h" + handles.size();
        handles.add(handle);

        bridgeWriter.visitField(ACC_PRIVATE | ACC_STATIC, handleName, "Ljava/lang/invoke/MethodHandle;", null, null).visitEnd();
        MethodVisitor p = proxyWriter.visitMethod(ACC_PUBLIC | ACC_FINAL, methodInfo.name(),
                methodInfo.descriptor(), methodInfo.genericSignature(), methodInfo.exceptionNames());
        MethodVisitor b = bridgeWriter.visitMethod(ACC_PUBLIC | ACC_FINAL | ACC_STATIC, bridgeMethodInfo.name(),
                bridgeMethodInfo.descriptor(), bridgeMethodInfo.genericSignature(), bridgeMethodInfo.exceptionNames());
        b.visitFieldInsn(GETSTATIC, bridgeName, handleName, "Ljava/lang/invoke/MethodHandle;");
        if (passTarget) {
            b.visitVarInsn(ALOAD, 0);
            p.visitVarInsn(ALOAD, 0);
            p.visitFieldInsn(GETFIELD, proxyName, "target", targetDescriptor);
        }
        int lvIndex = 1;
        for (Class<?> param : methodInfo.parameterTypes()) {
            int opcode = getLoadOpcode(param);
            b.visitVarInsn(opcode, lvIndex - (passTarget ? 0 : 1));
            p.visitVarInsn(opcode, lvIndex);
            lvIndex += getParameterSize(param);
        }
        b.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invoke", bridgeMethodInfo.descriptor(), false);
        p.visitMethodInsn(INVOKESTATIC, bridgeName, bridgeMethodInfo.name(), bridgeMethodInfo.descriptor(), false);
        b.visitInsn(getReturnOpcode(bridgeMethodInfo.returnType()));
        p.visitInsn(getReturnOpcode(methodInfo.returnType()));
        b.visitMaxs(-1, -1);
        p.visitMaxs(-1, -1);
    }

    private void generateUnsupportedMethod(Method interfaceMethod) {
        InternalMethodInfo methodInfo = getInternalMethodInfo(interfaceMethod);
        MethodVisitor p = proxyWriter.visitMethod(ACC_PUBLIC | ACC_FINAL, methodInfo.name(),
                methodInfo.descriptor(), methodInfo.genericSignature(), methodInfo.exceptionNames());
        p.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
        p.visitInsn(DUP);
        p.visitLdcInsn("No implementation found for this method");
        p.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false);
        p.visitInsn(ATHROW);
        p.visitMaxs(-1, -1);
    }
}
