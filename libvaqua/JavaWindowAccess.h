/*
 * Copyright (c) 2018-2020 Alan Snyder.
 * All rights reserved.
 *
 * You may not use, copy or modify this file, except in compliance with the license agreement. For details see
 * accompanying license terms.
 */

// Support for mapping a native window to a Java window or a Java platform window (CPlatformWindow).

#import <Cocoa/Cocoa.h>
#import <jni.h>

jobject getJavaWindow(JNIEnv *env, NSWindow *w);
jobject getJavaPlatformWindow(JNIEnv *env, NSWindow *w);
