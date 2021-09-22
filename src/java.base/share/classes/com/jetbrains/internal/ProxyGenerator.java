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

import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.jetbrains.internal.ASMUtils.*;
import static java.lang.invoke.MethodHandles.Lookup;
import static jdk.internal.org.objectweb.asm.Opcodes.*;

/**
 * There are 2 proxy dispatch modes:
 * <ul>
 *     <li>interface -> proxy -> bridge -> method handle -> implementation code</li>
 *     <li>interface -> proxy -> method handle -> implementation code</li>
 * <ul/>
 * Generated proxy is always located in client-side jetbrains.api module and optional bridge is located in the
 * same module with target implementation code. Bridge allows proxy to safely call hidden non-static implementation
 * methods and is only needed for jetbrains.api -> JBR calls. For JBR -> jetbrains.api calls, proxy can invoke
 * method handle directly.
 */
class ProxyGenerator {

    private static final AtomicInteger nameCounter = new AtomicInteger();

    private final ProxyInfo info;
    private final boolean generateBridge;
    private final String proxyName, bridgeName, targetDescriptor;
    private final ClassVisitor proxyWriter, bridgeWriter;
    private final List<MethodHandle> handles = new ArrayList<>();
    private final List<Exception> exceptions = new ArrayList<>();
    private boolean allMethodsImplemented = true;

    ProxyGenerator(ProxyInfo info) {
        this.info = info;
        generateBridge = info.type != ProxyInfo.Type.CLIENT_PROXY;
        int nameId = nameCounter.getAndIncrement();
        proxyName = JBRApi.outerLookup.lookupClass().getPackageName().replace('.', '/') + "/" +
                info.interFace.getSimpleName() + "$$JBRApiProxy$" + nameId;
        bridgeName = generateBridge ? info.apiModule.lookupClass().getPackageName().replace('.', '/') + "/" +
                info.interFace.getSimpleName() + "$$JBRApiBridge$" + nameId : null;
        targetDescriptor = info.target == null ? "" :
                "L" + Type.getInternalName(info.target.lookupClass()) + ";";

        proxyWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        bridgeWriter = generateBridge ? new ClassWriter(ClassWriter.COMPUTE_MAXS) : new ClassVisitor(ASM_VERSION) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(api) {};
            }
        };
        proxyWriter.visit(BYTECODE_VERSION, ACC_SUPER | ACC_FINAL | ACC_SYNTHETIC, proxyName, null,
                "java/lang/Object", new String[] {Type.getInternalName(info.interFace)});
        bridgeWriter.visit(BYTECODE_VERSION, ACC_SUPER | ACC_FINAL | ACC_SYNTHETIC | ACC_PUBLIC, bridgeName, null,
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
        Lookup bridge = !generateBridge ? null : MethodHandles.privateLookupIn(
                info.apiModule.defineClass(((ClassWriter) bridgeWriter).toByteArray()), info.apiModule);
        Lookup proxy = JBRApi.outerLookup.defineHiddenClass(((ClassWriter) proxyWriter).toByteArray(), true, Lookup.ClassOption.STRONG);
        Lookup handlesHolder = generateBridge ? bridge : proxy;
        for (int i = 0; i < handles.size(); i++) {
            handlesHolder.findStaticVarHandle(handlesHolder.lookupClass(), "h" + i, MethodHandle.class).set(handles.get(i));
        }
        return proxy;
    }

    /**
     * Proxy constructor is no-arg for services and single-arg for proxies with its target type
     */
    private MethodHandle findConstructor(Lookup proxy) throws NoSuchMethodException, IllegalAccessException {
        if (info.target == null) {
            return proxy.findConstructor(proxy.lookupClass(), MethodType.methodType(void.class));
        } else {
            MethodHandle c = proxy.findConstructor(proxy.lookupClass(), MethodType.methodType(void.class, info.target.lookupClass()));
            if (info.type == ProxyInfo.Type.SERVICE) {
                return MethodHandles.foldArguments(c,
                        info.target.findConstructor(info.target.lookupClass(), MethodType.methodType(void.class)));
            }
            return c;
        }
    }

    private void generateConstructor() {
        if (info.target != null) {
            proxyWriter.visitField(ACC_PRIVATE | ACC_FINAL, "target", targetDescriptor, null, null);
        }
        MethodVisitor p = proxyWriter.visitMethod(ACC_PRIVATE, "<init>", "(" + targetDescriptor + ")V", null, null);
        p.visitVarInsn(ALOAD, 0);
        if (info.target != null) {
            p.visitInsn(DUP);
            p.visitVarInsn(ALOAD, 1);
            p.visitFieldInsn(PUTFIELD, proxyName, "target", targetDescriptor);
        }
        p.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        p.visitInsn(RETURN);
        p.visitMaxs(-1, -1);
    }

    private void generateMethods() {
        for (Method method : info.interFace.getMethods()) {
            int mod = method.getModifiers();
            if (!Modifier.isAbstract(mod)) continue;
            MethodType methodType = MethodType.methodType(method.getReturnType(), method.getParameterTypes());

            Exception e1 = null;
            if (info.target != null) {
                try {
                    MethodHandle handle = info.target.findVirtual(
                            info.target.lookupClass(), method.getName(), methodType);
                    generateMethod(method, handle, true);
                    continue;
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    e1 = e;
                }
            }

            Exception e2 = null;
            ProxyInfo.StaticMethodMapping mapping = info.staticMethods.get(method.getName());
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
            generateUnsupportedMethod(proxyWriter, method);
            allMethodsImplemented = false;
        }
    }

    private void generateMethod(Method interfaceMethod, MethodHandle handle, boolean passInstance) {
        InternalMethodInfo methodInfo = getInternalMethodInfo(interfaceMethod);
        String expandedDescriptor = !passInstance ? methodInfo.descriptor() :
                expandMethodHandleDescriptorForInstance(methodInfo.descriptor(), targetDescriptor);
        String expandedSignature = !passInstance ? methodInfo.genericSignature() :
                expandMethodHandleSignatureForInstance(methodInfo.genericSignature(), targetDescriptor);
        String handleName = "h" + handles.size();
        handles.add(handle);

        (generateBridge ? bridgeWriter : proxyWriter)
                .visitField(ACC_PRIVATE | ACC_STATIC, handleName, "Ljava/lang/invoke/MethodHandle;", null, null);
        MethodVisitor p = proxyWriter.visitMethod(ACC_PUBLIC | ACC_FINAL, methodInfo.name(),
                methodInfo.descriptor(), methodInfo.genericSignature(), methodInfo.exceptionNames());
        MethodVisitor b = bridgeWriter.visitMethod(ACC_PUBLIC | ACC_FINAL | ACC_STATIC, methodInfo.name(),
                expandedDescriptor, expandedSignature, methodInfo.exceptionNames());
        (generateBridge ? b : p)
                .visitFieldInsn(GETSTATIC, (generateBridge ? bridgeName : proxyName), handleName, "Ljava/lang/invoke/MethodHandle;");
        if (passInstance) {
            p.visitVarInsn(ALOAD, 0);
            p.visitFieldInsn(GETFIELD, proxyName, "target", targetDescriptor);
            b.visitVarInsn(ALOAD, 0);
        }
        int lvIndex = 1;
        for (Class<?> param : methodInfo.parameterTypes()) {
            int opcode = getLoadOpcode(param);
            p.visitVarInsn(opcode, lvIndex);
            b.visitVarInsn(opcode, lvIndex - (passInstance ? 0 : 1));
            lvIndex += getParameterSize(param);
        }
        if (generateBridge) p.visitMethodInsn(INVOKESTATIC, bridgeName, methodInfo.name(), expandedDescriptor, false);
        (generateBridge ? b : p).visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invoke", expandedDescriptor, false);
        int returnOpcode = getReturnOpcode(methodInfo.returnType());
        p.visitInsn(returnOpcode);
        b.visitInsn(returnOpcode);
        p.visitMaxs(-1, -1);
        b.visitMaxs(-1, -1);
    }
}
