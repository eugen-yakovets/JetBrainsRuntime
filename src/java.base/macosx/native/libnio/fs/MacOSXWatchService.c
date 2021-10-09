/*
 * Copyright 2000-2021 JetBrains s.r.o.
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

#include "jni.h"
#include "jni_util.h"
#include "jvm.h"
#include "jlong.h"
#include "nio_util.h"

#include <stdlib.h>
#include <string.h>

#include <CoreFoundation/CoreFoundation.h>
#include <CoreServices/CoreServices.h>

static jboolean tracingEnabled; // Set with -Djava.nio.watchservice.macosx.trace=true

extern CFStringRef toCFString(JNIEnv *env, jstring javaString);

JNIEXPORT void JNICALL
Java_sun_nio_fs_MacOSXWatchService_initIDs(JNIEnv* env, __unused jclass clazz) {

    jfieldID tracingEnabledFieldID = (*env)->GetStaticFieldID(env, clazz, "tracingEnabled", "Z");
    if (tracingEnabledFieldID == NULL) {
        (*env)->ExceptionDescribe(env);
    }

    tracingEnabled = (*env)->GetStaticBooleanField(env, clazz, tracingEnabledFieldID);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
    }
}

static jboolean convertToJavaStringArray(JNIEnv* env, const void *eventPaths, const size_t eventIndex, const jsize numEventsToReport, jobjectArray javaEventPathsArray) {
    char **eventPathsArray = &((char **) eventPaths)[eventIndex];
    for (jsize i = 0; i < numEventsToReport; i++) {
        const jstring path = JNU_NewStringPlatform(env, eventPathsArray[i]);
        if (path == NULL) {
            if ((*env)->ExceptionCheck(env) && tracingEnabled) {
                    (*env)->ExceptionDescribe(env);
            }
            return FALSE;
        }
        (*env)->SetObjectArrayElement(env, javaEventPathsArray, i, path);
    }

    return TRUE;
}

static jboolean createJavaArray(JNIEnv* env, const jsize numEventsToReport, jobjectArray* javaEventPathsArray) {
    *javaEventPathsArray = (*env)->NewObjectArray(env, (jsize) numEventsToReport, JNU_ClassString(env), NULL);
    if ((*env)->ExceptionCheck(env)) {
        if (tracingEnabled) {
            (*env)->ExceptionDescribe(env);
        }
        return FALSE;
    }
    return TRUE;
}

static void callback(__unused ConstFSEventStreamRef streamRef,
                     void *clientCallBackInfo,
                     size_t numEventsTotal,
                     void *eventPaths,
                     const FSEventStreamEventFlags eventFlags[],
                     const FSEventStreamEventId eventIds[]) {
    // NB: we are invoked on the corresponding run loop's thread
    if (tracingEnabled) {
        printf("Callback fired!\n");
        printf("handler thread 0x%p\n", clientCallBackInfo);
    }

    JNIEnv *env = (JNIEnv *) JNU_GetEnv(jvm, JNI_VERSION_1_2);
    if (!env) { // Shouldn't happen as run loop starts from Java code
        return;
    }

    // We can get more events at once than the number of Java array elements,
    // so report them in chunks.
    const size_t MAX_EVENTS_TO_REPORT_AT_ONCE = (INT_MAX - 2);

    jboolean OK = TRUE;
    for(size_t eventIndex = 0; OK && (eventIndex < numEventsTotal); ) {
        const size_t numEventsRemaining = (numEventsTotal - eventIndex);
        const jsize  numEventsToReport  = (numEventsRemaining > MAX_EVENTS_TO_REPORT_AT_ONCE)
                                        ? MAX_EVENTS_TO_REPORT_AT_ONCE
                                        : numEventsTotal;

        const jboolean localFramePushed = ((*env)->PushLocalFrame(env, numEventsToReport + 5) == JNI_OK);
        OK = localFramePushed;

        jobjectArray javaEventPathsArray = NULL;
        if (OK) {
            OK = createJavaArray(env, numEventsToReport, &javaEventPathsArray);
        }

        if (OK) {
            OK = convertToJavaStringArray(env, eventPaths, eventIndex, numEventsToReport, javaEventPathsArray);
        }

        const jobject handlerThreadObject = (jobject) clientCallBackInfo;
        jboolean hasException = FALSE;
        // TODO: cache method reference?
        JNU_CallMethodByName(env, &hasException, handlerThreadObject, "callback", "(JJ[Ljava/lang/String;JJ)V",
                             (jlong) streamRef, numEventsToReport, javaEventPathsArray,
                             (jlong)&eventFlags[eventIndex], (jlong)&eventIds[eventIndex]);

        if (tracingEnabled) {
            if (hasException) {
                (*env)->ExceptionDescribe(env);
            }
        }

        if (localFramePushed) {
            (*env)->PopLocalFrame(env, NULL);
        }

        eventIndex += numEventsToReport;
    }
}

/**
 * Creates new FSEventStream
 */
JNIEXPORT jlong JNICALL
Java_sun_nio_fs_MacOSXWatchService_createNewEventStreamFor(JNIEnv* env, __unused jclass clazz,
                                                           jstring dir, jdouble latencyInSeconds,
                                                           jint flags, jobject localHandlerThreadObject) {
    const CFStringRef path = toCFString(env, dir);
    CHECK_NULL_RETURN(path, 0);
    const CFArrayRef pathsToWatch = CFArrayCreate(NULL, (const void **) &path, 1, NULL);
    CHECK_NULL_RETURN(pathsToWatch, 0);

    jobject handlerThreadObject = (*env)->NewGlobalRef(env, localHandlerThreadObject);
    FSEventStreamContext payload = {0};
    payload.info = (void *) handlerThreadObject;

    // TODO: cache field ref and not lookup it every time

    const FSEventStreamRef stream = FSEventStreamCreate(
            NULL,           // allocator
            &callback,
            &payload,
            pathsToWatch,
            kFSEventStreamEventIdSinceNow,
            (CFAbsoluteTime) latencyInSeconds,
            flags
    );

    if (stream != NULL) {
        jboolean hasException = FALSE;
        JNU_SetFieldByName(env, &hasException, handlerThreadObject, "globalThisRef", "J", handlerThreadObject);
        if (hasException) {
            return 0;
        }
    }

    if (tracingEnabled) {
        printf("handler thread 0x%p\n", handlerThreadObject);
        printf("Created event stream 0x%p\n", stream);
    }
    return (jlong)stream; // TODO: may return 0!
}


/**
 * Schedules the given FSEventStrem on the run loop of the current thread. Starts the stream
 * so that the run loop can receive events from the stream.
 */
JNIEXPORT void JNICALL
Java_sun_nio_fs_MacOSXWatchService_scheduleEventLoop(__unused JNIEnv* env,  __unused jclass clazz, long eventStreamRef)
{
    FSEventStreamRef stream = (FSEventStreamRef) eventStreamRef;
    FSEventStreamScheduleWithRunLoop(stream, CFRunLoopGetCurrent(), kCFRunLoopDefaultMode);
    FSEventStreamStart(stream);

    if (tracingEnabled) {
        printf("Scheduled stream 0x%p on thread 0x%p\n", stream, CFRunLoopGetCurrent());
    }
}

/**
 * Returns the CFRunLoop object for the current thread.
 */
JNIEXPORT jlong JNICALL
Java_sun_nio_fs_MacOSXWatchService_CFRunLoopGetCurrent(__unused JNIEnv* env, __unused jclass clazz)
{
    CFRunLoopRef currentRunLoop = CFRunLoopGetCurrent();
    if (tracingEnabled) {
        printf("Get current run loop: 0x%p\n", currentRunLoop);
    }
    return (jlong)currentRunLoop;
}

/**
 * Simply calls CFRunLoopRun() to run current thread's run loop indefinitely.
 */
JNIEXPORT void JNICALL
Java_sun_nio_fs_MacOSXWatchService_CFRunLoopRun(__unused JNIEnv* env, __unused jclass clazz)
{
    if (tracingEnabled) {
        printf("Running run loop on 0x%p\n", CFRunLoopGetCurrent());
    }
    CFRunLoopRun();
    if (tracingEnabled) {
        printf("Run loop done on 0x%p\n", CFRunLoopGetCurrent());
    }
}

/**
 * Simply calls CFRunLoopStop to force the given run loop to stop running.
 */
JNIEXPORT void JNICALL
Java_sun_nio_fs_MacOSXWatchService_runLoopStop(JNIEnv* env, __unused jclass clazz, long runLoopRef, jlong handlerThreadObject)
{
    if (tracingEnabled) {
        printf("Stopping run loop 0x%p\n", (CFRunLoopRef) runLoopRef);
    }

    CFRunLoopStop((CFRunLoopRef)runLoopRef);

    (*env)->DeleteGlobalRef(env, (jobject)handlerThreadObject);
}

/**
 * Performs the steps necessary to dispose of the given FSEventStreamRef.
 * The stream must have been started and scheduled with a run loop.
 */
JNIEXPORT void JNICALL
Java_sun_nio_fs_MacOSXWatchService_FSEventStreamInvalidate(__unused JNIEnv* env, __unused jclass clazz, jlong eventStreamRef)
{
    const FSEventStreamRef streamRef = (FSEventStreamRef)eventStreamRef;
    FSEventStreamStop(streamRef);       // Unregister with the FS Events service. No more callbacks from this stream
    FSEventStreamInvalidate(streamRef); // Unschedule from any runloops
    FSEventStreamRelease(streamRef);    // Decrement the stream's refcount

}

