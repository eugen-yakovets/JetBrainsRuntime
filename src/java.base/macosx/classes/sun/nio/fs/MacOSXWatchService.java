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

package sun.nio.fs;

import jdk.internal.misc.Unsafe;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

class MacOSXWatchService extends AbstractWatchService {
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    protected static final long SIZEOF_FS_EVENT_STREAM_EVENT_FLAGS = 4L; // aka uint32

    // for testing and debugging purposes, control with -Djava.nio.watchservice.macosx.trace=true
    private static boolean tracingEnabled = false;

    private static final int kFSEventStreamCreateFlagNone       = 0x00000000;
    private static final int kFSEventStreamCreateFlagNoDefer    = 0x00000002;
    private static final int kFSEventStreamCreateFlagWatchRoot  = 0x00000004;
    private static final int kFSEventStreamCreateFlagFileEvents = 0x00000010;

    private final List<CFRunLoopThread> runLoops = new ArrayList<>();

    MacOSXWatchService() {

    }

    @Override
    WatchKey register(Path dir, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
        checkIsOpen();

        final UnixPath unixDir = (UnixPath)dir;
        checkPath(unixDir);

        final EnumSet<FSEventKind>   eventSet    = FSEventKind.setOf(events);
        final EnumSet<WatchModifier> modifierSet = WatchModifier.setOf(modifiers);
        synchronized (closeLock()) {
            checkIsOpen();

            final CFRunLoopThread runLoop = new CFRunLoopThread();

            final long eventStreamRef = MacOSXWatchService.createNewEventStreamFor(
                    dir.toString(),
                    WatchModifier.sensitivityOf(modifierSet),
                    getFSEventStreamCreateFlagsFor(eventSet, modifierSet),
                    runLoop);

            if (eventStreamRef == 0) {
                throw new IOException("Unable to create FSEventStream"); // TODO: test if can happen
            }

            final MacOSXWatchKey watchKey = new MacOSXWatchKey(unixDir, this, eventSet,
                                                               modifierSet, eventStreamRef, runLoop);
            runLoop.setDaemon(true);
            runLoop.start();
            synchronized (runLoops) {
                runLoops.add(runLoop);
            }

            return watchKey;
        }
    }

    void cancel(final MacOSXWatchKey watchKey) {
        synchronized (runLoops) {
            Optional<CFRunLoopThread> runLoop = runLoops.stream().filter(r -> r.getWatchKey() == watchKey).findFirst();
            assert (runLoop.isPresent());

            runLoop.get().close(); // also invalidates the key
            runLoops.remove(runLoop.get());
        }
    }

    @Override
    void implClose() {
        synchronized (runLoops) {
            runLoops.forEach(CFRunLoopThread::close); // also invalidates the corresponding keys
            runLoops.clear();
        }
    }

    private static class CFRunLoopThread extends Thread {
        private long runLoopRef;    // CFRunLoopRef from CFRunLoopGetCurrent()
        private long globalThisRef; // 'this' saved as NewGlobalRef() in the JNI code
        private MacOSXWatchKey watchKey;

        private final Object isStoppedLock = new Object();
        private boolean      isStopped     = false;

        public CFRunLoopThread() {
            super("FileSystemWatcher");
        }

        private void setWatchKey(final MacOSXWatchKey watchKey) {
            this.watchKey = watchKey;
        }

        @Override
        public void run() {
            assert(watchKey != null);

            synchronized (isStoppedLock) {
                if (isStopped) return;
                runLoopRef = CFRunLoopGetCurrent();
                scheduleEventLoop(watchKey.getEventStreamRef());
            }

            try {
                CFRunLoopRun();
            } finally {
                // TODO: test this by throwing OOM from native callback
                if (tracingEnabled) {
                    System.out.printf("Run loop %1d terminated", runLoopRef);
                }
                close();
            }
        }

        MacOSXWatchKey getWatchKey() {
            assert(watchKey != null);
            return watchKey;
        }

        private void callback(final long eventStreamRef, final long numEvents,
                              final String[] paths, final long eventFlagsPtr, final long eventIdsPtr) {
            assert(eventStreamRef == watchKey.getEventStreamRef());
            assert(watchKey != null);

            if (tracingEnabled) System.out.println("numEvents=" + numEvents);
            if (numEvents > 0 && paths != null) {
                watchKey.handleEvents(paths, eventFlagsPtr);
            }
        }

        public void close() {
            assert(watchKey != null);
            synchronized (isStoppedLock) {
                if (isStopped) return;
                isStopped = true;

                watchKey.invalidate();

                if (runLoopRef != 0) {
                    runLoopStop(runLoopRef, globalThisRef);
                }
            }
        }
    }

    private void checkIsOpen() {
        if (!isOpen())
            throw new ClosedWatchServiceException();
    }

    private int getFSEventStreamCreateFlagsFor(final EnumSet<FSEventKind> eventSet, final EnumSet<WatchModifier> modifierSet) {
        return    kFSEventStreamCreateFlagNoDefer
                | kFSEventStreamCreateFlagFileEvents
                | kFSEventStreamCreateFlagWatchRoot;
    }

    private void checkPath(UnixPath dir) throws IOException {
        if (dir == null)
            throw new NullPointerException("No path to watch");

        UnixFileAttributes attrs;
        try {
            attrs = UnixFileAttributes.get(dir, true);
        } catch (UnixException x) {
            throw x.asIOException(dir);
        }
        if (!attrs.isDirectory()) {
            throw new NotDirectoryException(dir.getPathForExceptionMessage());
        }
    }


    private enum FSEventKind {
        CREATE, MODIFY, DELETE, OVERFLOW;

        public static FSEventKind of(final WatchEvent.Kind<?> watchEventKind) {
            if (StandardWatchEventKinds.ENTRY_CREATE == watchEventKind) {
                return CREATE;
            } else if (StandardWatchEventKinds.ENTRY_MODIFY == watchEventKind) {
                return MODIFY;
            } else if (StandardWatchEventKinds.ENTRY_DELETE == watchEventKind) {
                return DELETE;
            } else if (StandardWatchEventKinds.OVERFLOW == watchEventKind) {
                return OVERFLOW;
            } else {
                throw new UnsupportedOperationException(watchEventKind.name());
            }
        }

        public static EnumSet<FSEventKind> setOf(final WatchEvent.Kind<?>[] events) {
            final EnumSet<FSEventKind> eventSet = EnumSet.noneOf(FSEventKind.class);
            for (final WatchEvent.Kind<?> event: events) {
                if (event == null)
                    throw new NullPointerException("An element in event set is 'null'");

                eventSet.add(FSEventKind.of(event));
            }

            if (eventSet.isEmpty())
                throw new IllegalArgumentException("No events to register");

            return eventSet;
        }

    }

    private enum WatchModifier {
        FILE_TREE, SENSITIVITY_HIGH, SENSITIVITY_MEDIUM, SENSITIVITY_LOW;

        public static WatchModifier of(final WatchEvent.Modifier watchEventModifier) {
            if (ExtendedOptions.FILE_TREE.matches(watchEventModifier)) {
                return FILE_TREE;
            } if (ExtendedOptions.SENSITIVITY_HIGH.matches(watchEventModifier)) {
                return SENSITIVITY_HIGH;
            } if (ExtendedOptions.SENSITIVITY_MEDIUM.matches(watchEventModifier)) {
                return SENSITIVITY_MEDIUM;
            } if (ExtendedOptions.SENSITIVITY_LOW.matches(watchEventModifier)) {
                return SENSITIVITY_LOW;
            } else {
                throw new UnsupportedOperationException(watchEventModifier.name());
            }
        }

        public static EnumSet<WatchModifier> setOf(final WatchEvent.Modifier[] modifiers) {
            final EnumSet<WatchModifier> modifierSet = EnumSet.noneOf(WatchModifier.class);
            for (final WatchEvent.Modifier modifier : modifiers) {
                if (modifier == null)
                    throw new NullPointerException("An element in modifier set is 'null'");

                modifierSet.add(WatchModifier.of(modifier));
            }

            return modifierSet;
        }

        public static double sensitivityOf(final EnumSet<WatchModifier> modifiers) {
            if (modifiers.contains(SENSITIVITY_HIGH)) {
                return 0.1;
            } else if (modifiers.contains(SENSITIVITY_LOW)) {
                return 1;
            } else {
                return 0.5; // aka SENSITIVITY_MEDIUM
            }
        }
    }

    private static class MacOSXWatchKey extends AbstractWatchKey {
        private final EnumSet<FSEventKind>   eventsToWatch;
        private final boolean                watchFileTree;

        private final Object eventStreamRefLock = new Object();
        private long eventStreamRef; // FSEventStreamRef as returned by FSEventStreamCreate()

        private final Path realRootPath;
        private final int realRootPathLength;

        MacOSXWatchKey(final UnixPath dir, final MacOSXWatchService watchService,
                       EnumSet<FSEventKind> eventsToWatch, EnumSet<WatchModifier> modifierSet,
                       final long eventStreamRef,
                       final CFRunLoopThread thread) throws IOException {
            super(dir, watchService);
            this.eventsToWatch = eventsToWatch;
            this.watchFileTree = modifierSet.contains(WatchModifier.FILE_TREE); // TODO: ignore subdir events
            this.eventStreamRef = eventStreamRef;
            this.realRootPath = dir.toRealPath().normalize();
            this.realRootPathLength = realRootPath.toString().length() + 1;
            thread.setWatchKey(this);
        }

        private static final long kFSEventStreamEventFlagMustScanSubDirs = 0x00000001;
        private static final long kFSEventStreamEventFlagUserDropped = 0x00000002;
        private static final long kFSEventStreamEventFlagKernelDropped = 0x00000004;
        private static final long kFSEventStreamEventFlagRootChanged = 0x00000020;
        private static final long kFSEventStreamEventFlagItemCreated = 0x00000100;
        private static final long kFSEventStreamEventFlagItemRemoved = 0x00000200;
        private static final long kFSEventStreamEventFlagItemInodeMetaMod = 0x00000400;
        private static final long kFSEventStreamEventFlagItemRenamed = 0x00000800;
        private static final long kFSEventStreamEventFlagItemModified = 0x00001000;
        private static final long kFSEventStreamEventFlagItemFinderInfoMod = 0x00002000;
        private static final long kFSEventStreamEventFlagItemChangeOwner = 0x00004000;
        private static final long kFSEventStreamEventFlagItemXattrMod = 0x00008000;
        private static final long kFSEventStreamEventFlagItemIsFile = 0x00010000;
        private static final long kFSEventStreamEventFlagItemIsDir = 0x00020000;
        private static final long kFSEventStreamEventFlagItemIsSymlink = 0x00040000;

        void handleEvents(final String[] paths, long eventFlagsPtr) {
            for(final String pathName : paths) {
                // path is absolute, but we need to report events relative to the watch root
                Path path = null;
                if (pathName != null) {
                    final String relativePathName = (pathName.length() > realRootPathLength)
                            ? pathName.substring(realRootPathLength)
                            : pathName;
                    path = Path.of(relativePathName);
                    if (tracingEnabled) System.out.println("Event path name: " + path);
                }
                // TODO: what to do with symlinks? should we report events under symlinked dirs?

                if (!watchFileTree && path != null) {
                    if (path.getNameCount() > 1) {
                        if (tracingEnabled) System.out.println("Skipping event for nested file/dir");
                    }
                }

                final int flags = unsafe.getInt(eventFlagsPtr);
                if (tracingEnabled) System.out.printf("event flags 0x%1$08X\n", flags);

                if ((flags & kFSEventStreamEventFlagMustScanSubDirs) != 0) {
                    if (tracingEnabled) {
                        if ((flags & kFSEventStreamEventFlagUserDropped) != 0) {
                            System.out.println("OVERFLOW because of UserDropped");
                        } else if ((flags & kFSEventStreamEventFlagKernelDropped) != 0) {
                            System.out.println("OVERFLOW because of KernelDropped");
                        }
                        else {
                            System.out.println("OVERFLOW for unspecified reason");
                        }
                    }
                    signalEvent(StandardWatchEventKinds.OVERFLOW, path);
                }

                if (pathName != null) {
                    // TODO: Path that is the relative path between the directory registered with the watch service
                    if ((flags & kFSEventStreamEventFlagRootChanged) != 0) {
                        System.out.println("watch root changed, path = " + path);
                        cancel();
                    }

                    boolean alsoReportModifyForParent = false;
                    if ((flags & kFSEventStreamEventFlagItemCreated) != 0
                            && (flags & kFSEventStreamEventFlagItemModified) == 0
                            && (flags & kFSEventStreamEventFlagItemRemoved) == 0) {
                        if (tracingEnabled) System.out.println("Created: " + path);
                        signalEvent(StandardWatchEventKinds.ENTRY_CREATE, path);
                        alsoReportModifyForParent = true;
                    }

                    if ((flags & kFSEventStreamEventFlagItemRemoved) != 0
                            && (flags & kFSEventStreamEventFlagItemCreated) == 0) {
                        if (tracingEnabled) System.out.println("Removed: " + path);
                        signalEvent(StandardWatchEventKinds.ENTRY_DELETE, path);
                        alsoReportModifyForParent = true;
                    }

                    if ((flags & kFSEventStreamEventFlagItemRenamed) != 0) {
                        // Identical "renamed" events are reported in sequence: first for deleted and next for created
                        if (tracingEnabled) System.out.println("Renamed: " + path);
                        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                            signalEvent(StandardWatchEventKinds.ENTRY_CREATE, path);
                            // TODO: this one isn't reported
                        } else {
                            signalEvent(StandardWatchEventKinds.ENTRY_DELETE, path);
                        }
                        alsoReportModifyForParent = true;
                    }

                    if ((flags & kFSEventStreamEventFlagItemModified) != 0
                            && (flags & kFSEventStreamEventFlagItemCreated) == 0) {
                        if (tracingEnabled) System.out.println("Modified: " + path);
                        // Careful, reported with kFSEventStreamEventFlagItemCreated flag ALSO set
                        signalEvent(StandardWatchEventKinds.ENTRY_MODIFY, path);
                    }

                    if ((flags & kFSEventStreamEventFlagItemInodeMetaMod) != 0
                            || (flags & kFSEventStreamEventFlagItemChangeOwner) != 0
                            || (flags & kFSEventStreamEventFlagItemFinderInfoMod) != 0
                            || (flags & kFSEventStreamEventFlagItemXattrMod) != 0) {
                        if (tracingEnabled) System.out.println("Modified meta: " + path);
                        signalEvent(StandardWatchEventKinds.ENTRY_MODIFY, path);
                    }

                    if (alsoReportModifyForParent) {
                        // TODO: collapse "modify" for parents of renamed
                        final Path parentPath = path.getParent();
                        signalEvent(StandardWatchEventKinds.ENTRY_MODIFY, parentPath);
                    }
                }
                eventFlagsPtr += SIZEOF_FS_EVENT_STREAM_EVENT_FLAGS;
            }
        }

        @Override
        public boolean isValid() {
            synchronized (eventStreamRefLock) {
                return eventStreamRef != 0;
            }
        }

        @Override
        public void cancel() {
            if (!isValid()) return;

            ((MacOSXWatchService)watcher()).cancel(this);
        }

        void invalidate() {
            synchronized (eventStreamRefLock) {
                if (isValid()) {
                    FSEventStreamInvalidate(eventStreamRef);
                    eventStreamRef = 0;
                }
            }
        }

        long getEventStreamRef() {
            synchronized (eventStreamRefLock) {
                assert(isValid());
                return eventStreamRef;
            }
        }
    }

    /* native methods */

    private static native long createNewEventStreamFor(String dir, double latencyInSeconds, int flags, CFRunLoopThread handlerThreadObjectRef);
    private static native void scheduleEventLoop(long eventStreamRef);
    private static native long CFRunLoopGetCurrent();
    private static native void CFRunLoopRun();
    private static native void runLoopStop(long runLoopRef, long handlerThreadObjectRef);

    private static native void FSEventStreamInvalidate(long eventStreamRef);

    private static native void initIDs();

    static {
        tracingEnabled = Boolean.parseBoolean(System.getProperty("java.nio.watchservice.macosx.trace", "false"));
        initIDs();
        System.loadLibrary("nio");
    }
}
