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

package com.jetbrains;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;

public class JBR {

    private static final ServiceApi api;
    private static final Exception bootstrapException;
    static {
        ServiceApi a = null;
        Exception exception = null;
        try {
            a = (ServiceApi) Class.forName("com.jetbrains.bootstrap.JBRApiBootstrap")
                    .getMethod("bootstrap", MethodHandles.Lookup.class)
                    .invoke(null, MethodHandles.lookup());
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof Error error) throw error;
            else throw new Error(t);
        } catch (IllegalAccessException | NoSuchMethodException | ClassNotFoundException e) {
            exception = e;
        }
        api = a;
        bootstrapException = exception;
    }

    private JBR() {}

    /**
     * @return true when running on JBR which implements JBR API
     */
    public static boolean isAvailable() {
        return api != null;
    }

    /**
     * @return any found implementation of given {@code interFace},
     * even if runtime cannot implement all of its methods, or methods of its dependencies.
     * Therefore, some methods of returned service may throw {@link UnsupportedOperationException}
     */
    public static <T> T getServicePartialSupport(Class<T> interFace) {
        return api == null ? null : api.getServicePartialSupport(interFace);
    }

    private interface ServiceApi {

        <T> T getService(Class<T> interFace);

        <T> T getServicePartialSupport(Class<T> interFace);
    }

    // ========================== Generated metadata ==========================

    private static final class Metadata {
        private static final String[] KNOWN_SERVICES = {/*KNOWN_SERVICES*/};
        private static final String[] KNOWN_PROXIES = {/*KNOWN_PROXIES*/};
    }

    // ======================= Generated static methods =======================

    /*GENERATED_METHODS*/
}
