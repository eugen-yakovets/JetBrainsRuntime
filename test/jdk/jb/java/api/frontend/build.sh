#!/bin/sh
#
# Copyright 2021 JetBrains s.r.o.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#

SRC="$TESTSRC/../../../../../../src"
PWD="`pwd`"
# Generate sources
"$COMPILEJAVA/bin/java" "$SRC/jetbrains.api/templates/Gensrc.java" "$SRC" "$PWD/jbr-api" || exit 1
# Validate version
"$COMPILEJAVA/bin/java" "$SRC/jetbrains.api/templates/CheckVersion.java" "$SRC/jetbrains.api" "$PWD/jbr-api" || exit 1
# Compile API
find "$SRC/jetbrains.api/src" -name *.java > compile.list
find jbr-api/gensrc -name *.java >> compile.list
"$COMPILEJAVA/bin/javac" $TESTJAVACOPTS -d "$TESTCLASSES" @compile.list || exit 1
rm "$TESTCLASSES/module-info.class"
exit 0
