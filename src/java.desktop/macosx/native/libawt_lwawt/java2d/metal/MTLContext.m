/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

#ifndef HEADLESS

#include <stdlib.h>

#include "sun_java2d_SunGraphics2D.h"

#include "jlong.h"
#import "MTLContext.h"
#include "MTLRenderQueue.h"


extern jboolean MTLSD_InitMTLWindow(JNIEnv *env, MTLSDOps *mtlsdo);

static struct TxtVertex verts[PGRAM_VERTEX_COUNT] = {
        {{-1.0, 1.0}, {0.0, 0.0}},
        {{1.0, 1.0}, {1.0, 0.0}},
        {{1.0, -1.0}, {1.0, 1.0}},
        {{1.0, -1.0}, {1.0, 1.0}},
        {{-1.0, -1.0}, {0.0, 1.0}},
        {{-1.0, 1.0}, {0.0, 0.0}}
};

@implementation MTLCommandBufferWrapper {
    id<MTLCommandBuffer> _commandBuffer;
    NSMutableArray * _pooledTextures;
}

- (id) initWithCommandBuffer:(id<MTLCommandBuffer>)cmdBuf {
    self = [super init];
    if (self) {
        _commandBuffer = [cmdBuf retain];
        _pooledTextures = [[NSMutableArray alloc] init];
    }
    return self;
}

- (id<MTLCommandBuffer>) getCommandBuffer {
    return _commandBuffer;
}

- (void) onComplete { // invoked from completion handler in some pooled thread
    for (int c = 0; c < [_pooledTextures count]; ++c)
        [[_pooledTextures objectAtIndex:c] releaseTexture];
    [_pooledTextures removeAllObjects];
}

- (void) registerPooledTexture:(MTLPooledTextureHandle *)handle {
    [_pooledTextures addObject:handle];
}

- (void) dealloc {
    [self onComplete];

    [self->_pooledTextures release];
    [self->_commandBuffer release];
    [super dealloc];
}

@end

@implementation MTLContext {
    MTLCommandBufferWrapper * _commandBufferWrapper;

    MTLComposite *     _composite;
    MTLPaint *         _paint;
    MTLTransform *     _transform;
    MTLClip *           _clip;

    EncoderManager * _encoderManager;
}

@synthesize textureFunction,
            vertexCacheEnabled, aaEnabled, device, library, pipelineStateStorage,
            commandQueue, vertexBuffer,
            texturePool;

extern void initSamplers(id<MTLDevice> device);

- (id)initWithDevice:(id<MTLDevice>)d shadersLib:(NSString*)shadersLib {
    self = [super init];
    if (self) {
        // Initialization code here.
        device = d;

        texturePool = [[MTLTexturePool alloc] initWithDevice:device];
        pipelineStateStorage = [[MTLPipelineStatesStorage alloc] initWithDevice:device shaderLibPath:shadersLib];

        vertexBuffer = [device newBufferWithBytes:verts
                                           length:sizeof(verts)
                                          options:MTLResourceCPUCacheModeDefaultCache];

        NSError *error = nil;

        library = [device newLibraryWithFile:shadersLib error:&error];
        if (!library) {
            NSLog(@"Failed to load library. error %@", error);
            exit(0);
        }

        _encoderManager = [[EncoderManager alloc] init];
        [_encoderManager setContext:self];
        _composite = [[MTLComposite alloc] init];
        _paint = [[MTLPaint alloc] init];
        _transform = [[MTLTransform alloc] init];
        _clip = [[MTLClip alloc] init];

        _commandBufferWrapper = nil;

        // Create command queue
        commandQueue = [device newCommandQueue];

        initSamplers(device);
    }
    return self;
}

- (void)dealloc {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.dealloc");

    self.texturePool = nil;
    self.library = nil;
    self.vertexBuffer = nil;
    self.commandQueue = nil;
    self.pipelineStateStorage = nil;
    [_encoderManager release];
    [_composite release];
    [_paint release];
    [_transform release];
    [_clip release];
    [super dealloc];
}

 - (MTLCommandBufferWrapper *) getCommandBufferWrapper {
    if (_commandBufferWrapper == nil) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLContext : commandBuffer is NULL");
        // NOTE: Command queues are thread-safe and allow multiple outstanding command buffers to be encoded simultaneously.
        _commandBufferWrapper = [[MTLCommandBufferWrapper alloc] initWithCommandBuffer:[self.commandQueue commandBuffer]];// released in [layer blitTexture]
    }
    return _commandBufferWrapper;
}

- (MTLCommandBufferWrapper *) pullCommandBufferWrapper {
    MTLCommandBufferWrapper * result = _commandBufferWrapper;
    _commandBufferWrapper = nil;
    return result;
}

+ (MTLContext*) setSurfacesEnv:(JNIEnv*)env src:(jlong)pSrc dst:(jlong)pDst {
    BMTLSDOps *srcOps = (BMTLSDOps *)jlong_to_ptr(pSrc);
    BMTLSDOps *dstOps = (BMTLSDOps *)jlong_to_ptr(pDst);
    MTLContext *mtlc = NULL;

    if (srcOps == NULL || dstOps == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLContext_SetSurfaces: ops are null");
        return NULL;
    }

    J2dTraceLn6(J2D_TRACE_VERBOSE, "MTLContext_SetSurfaces: bsrc=%p (tex=%p type=%d), bdst=%p (tex=%p type=%d)", srcOps, srcOps->pTexture, srcOps->drawableType, dstOps, dstOps->pTexture, dstOps->drawableType);

    if (dstOps->drawableType == MTLSD_TEXTURE) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "MTLContext_SetSurfaces: texture cannot be used as destination");
        return NULL;
    }

    if (dstOps->drawableType == MTLSD_UNDEFINED) {
        // initialize the surface as an OGLSD_WINDOW
        if (!MTLSD_InitMTLWindow(env, dstOps)) {
            J2dRlsTraceLn(J2D_TRACE_ERROR,
                          "MTLContext_SetSurfaces: could not init OGL window");
            return NULL;
        }
    }

    // make the context current
    MTLSDOps *dstCGLOps = (MTLSDOps *)dstOps->privOps;
    mtlc = dstCGLOps->configInfo->context;

    if (mtlc == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "MTLContext_SetSurfaces: could not make context current");
        return NULL;
    }

    // perform additional one-time initialization, if necessary
    if (dstOps->needsInit) {
        if (dstOps->isOpaque) {
            // in this case we are treating the destination as opaque, but
            // to do so, first we need to ensure that the alpha channel
            // is filled with fully opaque values (see 6319663)
            //MTLContext_InitAlphaChannel();
        }
        dstOps->needsInit = JNI_FALSE;
    }

    return mtlc;
}

- (void)resetClip {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.resetClip");
    [_clip reset];
}

- (void)setClipRectX1:(jint)x1 Y1:(jint)y1 X2:(jint)x2 Y2:(jint)y2 {
    J2dTraceLn4(J2D_TRACE_INFO, "MTLContext.setClipRect: %d,%d - %d,%d", x1, y1, x2, y2);
    [_clip setClipRectX1:x1 Y1:y1 X2:x2 Y2:y2];
}

- (void)beginShapeClip:(BMTLSDOps *)dstOps {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.beginShapeClip");
    [_clip beginShapeClip:dstOps context:self];
}

- (void)endShapeClip:(BMTLSDOps *)dstOps {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.endShapeClip");
    [_clip endShapeClip:dstOps context:self];
}

- (void)resetComposite {
    J2dTraceLn(J2D_TRACE_VERBOSE, "MTLContext_ResetComposite");
    [_composite reset];
}

- (void)setAlphaCompositeRule:(jint)rule extraAlpha:(jfloat)extraAlpha
                        flags:(jint)flags {
    J2dTraceLn3(J2D_TRACE_INFO, "MTLContext_SetAlphaComposite: rule=%d, extraAlpha=%1.2f, flags=%d", rule, extraAlpha, flags);

    [_composite setRule:rule extraAlpha:extraAlpha];
}

- (NSString*)getCompositeDescription {
    return [_composite getDescription];
}

- (NSString*)getPaintDescription {
    return [_paint getDescription];
}

- (void)setXorComposite:(jint)xp {
    J2dTraceLn1(J2D_TRACE_INFO, "MTLContext.setXorComposite: xorPixel=%08x", xp);

    [_composite setXORComposite:xp];
}

- (jboolean)isBlendingDisabled:(jboolean) isSrcOpaque {
    return [_composite isBlendingDisabled:isSrcOpaque];
}


- (void)resetTransform {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_ResetTransform");
    [_transform resetTransform];
}

- (void)setTransformM00:(jdouble) m00 M10:(jdouble) m10
                    M01:(jdouble) m01 M11:(jdouble) m11
                    M02:(jdouble) m02 M12:(jdouble) m12 {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_SetTransform");
    [_transform setTransformM00:m00 M10:m10 M01:m01 M11:m11 M02:m02 M12:m12];
}

- (jboolean)initBlitTileTexture {
    //TODO
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_InitBlitTileTexture -- :TODO");

    return JNI_TRUE;
}

- (jint)createBlitTextureFormat:(jint)internalFormat pixelFormat:(jint)pixelFormat
                          width:(jint)width height:(jint)height {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_InitBlitTileTexture -- :TODO");

    //TODO
    return 0;
}

- (void)resetPaint {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.resetPaint");
    [_paint reset];
}

- (void)setColorPaint:(int)pixel {
    J2dTraceLn5(J2D_TRACE_INFO, "MTLContext.setColorPaint: pixel=%08x [r=%d g=%d b=%d a=%d]", pixel, (pixel >> 16) & (0xFF), (pixel >> 8) & 0xFF, (pixel) & 0xFF, (pixel >> 24) & 0xFF);
    [_paint setColor:pixel];
}

- (void)setGradientPaintUseMask:(jboolean)useMask
                         cyclic:(jboolean)cyclic
                             p0:(jdouble)p0
                             p1:(jdouble)p1
                             p3:(jdouble)p3
                         pixel1:(jint)pixel1
                         pixel2:(jint) pixel2
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.setGradientPaintUseMask");
    [_paint setGradientUseMask:useMask
                            cyclic:cyclic
                                p0:p0
                                p1:p1
                                p3:p3
                            pixel1:pixel1
                            pixel2:pixel2];
}

- (void)setLinearGradientPaint:(jboolean)useMask
                        linear:(jboolean)linear
                   cycleMethod:(jboolean)cycleMethod
                      numStops:(jint)numStops
                            p0:(jfloat)p0
                            p1:(jfloat)p1
                            p3:(jfloat)p3
                     fractions:(void *)fractions
                        pixels:(void *)pixels
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.setLinearGradientPaint");
    [_paint setLinearGradient:useMask
                       linear:linear
                  cycleMethod:cycleMethod
                     numStops:numStops
                           p0:p0
                           p1:p1
                           p3:p3
                    fractions:fractions
                       pixels:pixels];
}

- (void)setRadialGradientPaint:(jboolean)useMask
                        linear:(jboolean)linear
                   cycleMethod:(jboolean)cycleMethod
                      numStops:(jint)numStops
                           m00:(jfloat)m00
                           m01:(jfloat)m01
                           m02:(jfloat)m02
                           m10:(jfloat)m10
                           m11:(jfloat)m11
                           m12:(jfloat)m12
                        focusX:(jfloat)focusX
                     fractions:(void *)fractions
                        pixels:(void *)pixels
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.setRadialGradientPaint");
    [_paint setRadialGradient:useMask
                       linear:linear
                  cycleMethod:cycleMethod
                     numStops:numStops
                          m00:m00
                          m01:m01
                          m02:m02
                          m10:m10
                          m11:m11
                          m12:m12
                       focusX:focusX
                    fractions:fractions
                       pixels:pixels];
}

- (void)setTexturePaint:(jboolean)useMask
                pSrcOps:(jlong)pSrcOps
                 filter:(jboolean)filter
                    xp0:(jdouble)xp0
                    xp1:(jdouble)xp1
                    xp3:(jdouble)xp3
                    yp0:(jdouble)yp0
                    yp1:(jdouble)yp1
                    yp3:(jdouble)yp3
{
    BMTLSDOps *srcOps = (BMTLSDOps *)jlong_to_ptr(pSrcOps);
    
    if (srcOps == NULL || srcOps->pTexture == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLContext_setTexturePaint: texture paint - texture is null");
        return;
    }

    J2dTraceLn1(J2D_TRACE_INFO, "MTLContext.setTexturePaint [tex=%p]", srcOps->pTexture);


    [_paint setTexture:useMask
               textureID:srcOps->pTexture
                filter:filter
                   xp0:xp0
                   xp1:xp1
                   xp3:xp3
                   yp0:yp0
                   yp1:yp1
                   yp3:yp3];
}

- (id<MTLCommandBuffer>)createBlitCommandBuffer {
    return [self.commandQueue commandBuffer];
}

@end

/*
 * Class:     sun_java2d_metal_MTLContext
 * Method:    getMTLIdString
 * Signature: ()Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_sun_java2d_metal_MTLContext_getMTLIdString
  (JNIEnv *env, jclass mtlcc)
{
    char *vendor, *renderer, *version;
    char *pAdapterId;
    jobject ret = NULL;
    int len;

    return NULL;
}

#endif /* !HEADLESS */
