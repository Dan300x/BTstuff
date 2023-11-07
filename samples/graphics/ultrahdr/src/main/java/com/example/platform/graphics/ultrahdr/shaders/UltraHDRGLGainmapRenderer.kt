/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.platform.graphics.ultrahdr.shaders

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Build
import androidx.annotation.RequiresApi
import java.lang.IllegalArgumentException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UltraHDRGLGainmapRenderer(private val bitmap: Bitmap) {
    private val FLOAT_SIZE_BYTES = 4
    private val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
    private val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
    private val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3
    private val mTriangleVerticesData = floatArrayOf(
        // X, Y, Z, U, V
        0f, 0f, .5f, 1f, 0f,
        0f, 1f, .5f, 1f, 1f,
        1f, 0f, .5f, 0f, 0f,
        1f, 1f, .5f, 0f, 1f,
        0f, 1f, .5f, 1f, 1f,
        1f, 0f, .5f, 0f, 0f,
    )

    private val mTriangleVertices: FloatBuffer = ByteBuffer.allocateDirect(
        mTriangleVerticesData.size
                * FLOAT_SIZE_BYTES,
    ).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val mVertexShader = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
          gl_Position = uMVPMatrix * aPosition;
          vTextureCoord = aTextureCoord;
        }
    """

    private val mFragmentShader = """
        precision mediump float;
        varying vec2 vTextureCoord;

        uniform highp float srcTF[7];
        uniform highp float destTF[7];
        uniform sampler2D base;
        uniform sampler2D gainmap;
        uniform mediump vec3 logRatioMin;
        uniform mediump vec3 logRatioMax;
        uniform mediump vec3 gainmapGamma;
        uniform mediump vec3 epsilonSdr;
        uniform mediump vec3 epsilonHdr;
        uniform mediump float W;
        uniform highp int gainmapIsAlpha;
        uniform highp int singleChannel;
        uniform highp int noGamma;

   	    highp float fromSrc(highp float x) {
   	    	highp float G = srcTF[0];
   	    	highp float A = srcTF[1];
   	    	highp float B = srcTF[2];
   	    	highp float C = srcTF[3];
   	    	highp float D = srcTF[4];
   	    	highp float E = srcTF[5];
   	    	highp float F = srcTF[6];
   	    	highp float s = sign(x);
   	    	x = abs(x);
   	    	x = x < D ? C * x + F : pow(A * x + B, G) + E;
   	    	return s * x;
   	    }

   	    highp float toDest(highp float x) {
   	    	highp float G = destTF[0];
   	    	highp float A = destTF[1];
   	    	highp float B = destTF[2];
   	    	highp float C = destTF[3];
   	    	highp float D = destTF[4];
   	    	highp float E = destTF[5];
   	    	highp float F = destTF[6];
   	    	highp float s = sign(x);
   	    	x = abs(x);
   	    	x = x < D ? C * x + F : pow(A * x + B, G) + E;
   	    	return s * x;
   	    }
   
        highp vec4 sampleBase(vec2 coord) {
            vec4 color = texture2D(base, vTextureCoord);
            color = vec4(color.xyz / max(color.w, 0.0001), color.w);
            color.x = fromSrc(color.x);
            color.y = fromSrc(color.y);
            color.z = fromSrc(color.z);
            color.xyz *= color.w;
            return color;
        }
  
        void main() {
          vec4 S = sampleBase(vTextureCoord);
          vec4 G = texture2D(gainmap, vTextureCoord);
          vec3 H;

          if (gainmapIsAlpha == 1) {
              G = vec4(G.w, G.w, G.w, 1.0);
              mediump float L;
              if (noGamma == 1) {
                  L = mix(logRatioMin.x, logRatioMax.x, G.x);
              } else {
                  L = mix(logRatioMin.x, logRatioMax.x, pow(G.x, gainmapGamma.x));
              }
              H = (S.xyz + epsilonSdr) * exp(L * W) - epsilonHdr;
          } else {
              mediump vec3 L;
              if (noGamma == 1) {
                  L = mix(logRatioMin, logRatioMax, G.xyz);
              } else {
                  L = mix(logRatioMin, logRatioMax, pow(G.xyz, gainmapGamma));
              }
              H = (S.xyz + epsilonSdr) * exp(L * W) - epsilonHdr;
          }

          vec4 result = vec4(H.xyz / max(S.w, 0.0001), S.w);
          result.x = toDest(result.x);
          result.y = toDest(result.y);
          result.z = toDest(result.z);
          result.xyz *= result.w;
          
          gl_FragColor = result;
        }
    """

    private val mMVPMatrix = FloatArray(16)
    private val mOrthoMatrix = FloatArray(16)
    private val mProjMatrix = FloatArray(16)
    private val mMMatrix = FloatArray(16)
    private val mVMatrix = FloatArray(16)

    private val mDestTF = FloatArray(7);

    private var mProgram = 0
    private var mTextureID = 0
    private var mGainmapTextureID = 0
    private var muMVPMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0
    private var mDestTFHandle = 0
    private var mWHandle = 0
    private var mDisplayRatioSdr = 0f
    private var mDisplayRatioHdr = 0f

    init {
        mTriangleVertices.put(mTriangleVerticesData).position(0)
    }

    val desiredHdrSdrRatio get() = bitmap.gainmap?.displayRatioForFullHdr ?: 1f

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = glCreateShader(shaderType)
        if (shader != 0) {
            glShaderSource(shader, source)
            glCompileShader(shader)
            val compiled = IntArray(1)
            glGetShaderiv(shader, GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                glDeleteShader(shader)
                shader = 0
            }
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            return 0
        }
        val pixelShader = loadShader(GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) {
            return 0
        }
        var program = glCreateProgram()
        if (program != 0) {
            glAttachShader(program, vertexShader)
            checkGlError("glAttachShader")
            glAttachShader(program, pixelShader)
            checkGlError("glAttachShader")
            glLinkProgram(program)
            val linkStatus = IntArray(1)
            glGetProgramiv(program, GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GL_TRUE) {
                glDeleteProgram(program)
                program = 0
            }
        }
        return program
    }

    private fun checkGlError(op: String) {
        var error: Int
        while (glGetError().also { error = it } != GL_NO_ERROR)
            throw RuntimeException("$op: glError $error")
    }

    private val kSRGB = floatArrayOf(
        2.4f,
        (1 / 1.055).toFloat(),
        (0.055 / 1.055).toFloat(),
        (1 / 12.92).toFloat(),
        0.04045f,
        0.0f,
        0.0f,
    )

    private fun trfn_apply_gain(trfn: FloatArray, gain: Float, dest: FloatArray) {
        val pow_gain_ginv = gain.toDouble().pow(1.0 / trfn[0]).toFloat()
        dest[0] = trfn[0]
        dest[1] = trfn[1] * pow_gain_ginv
        dest[2] = trfn[2] * pow_gain_ginv
        dest[3] = trfn[3] * gain
        dest[4] = trfn[4]
        dest[5] = trfn[5] * gain
        dest[6] = trfn[6] * gain
    }

    private fun skcms_TransferFunction_eval(tf: FloatArray, x: Float): Float {
        val sign = if (x < 0) -1.0f else 1.0f;
        val x = x * sign;
        return sign * if (x < tf[4]) {
            tf[3] * x * tf[6]
        } else {
            Math.pow((tf[1] * x + tf[2]).toDouble(), tf[0].toDouble()).toFloat() + tf[5]
        }
    }

    private fun invert_trfn(src: FloatArray, dest: FloatArray) {
        // We're inverting this function, solving for x in terms of y.
        //   y = (cx + f)         x < d
        //       (ax + b)^g + e   x ≥ d
        // The inverse of this function can be expressed in the same piecewise form.
        val inv = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)

        // We'll start by finding the new threshold inv[4].
        // In principle we should be able to find that by solving for y at x=d from either side.
        // (If those two d values aren't the same, it's a discontinuous transfer function.)
        val d_l = src[3] * src[4] + src[6]
        val d_r = (src[1] * src[4] + src[2]).toDouble().pow(src[0].toDouble()).toFloat() + src[5]
        if (abs(d_l - d_r) > 1 / 512.0f) {
            throw IllegalArgumentException()
        }
        inv[4] = d_l;

        // When d=0, the linear section collapses to a point.  We leave c,d,f all zero in that case.
        if (inv[4] > 0) {
            // Inverting the linear section is pretty straightfoward:
            //        y       = cx + f
            //        y - f   = cx
            //   (1/c)y - f/c = x
            inv[3] = 1.0f / src[3];
            inv[6] = -src[6] / src[3];
        }

        // The interesting part is inverting the nonlinear section:
        //         y                = (ax + b)^g + e.
        //         y - e            = (ax + b)^g
        //        (y - e)^1/g       =  ax + b
        //        (y - e)^1/g - b   =  ax
        //   (1/a)(y - e)^1/g - b/a =   x
        //
        // To make that fit our form, we need to move the (1/a) term inside the exponentiation:
        //   let k = (1/a)^g
        //   (1/a)( y -  e)^1/g - b/a = x
        //        (ky - ke)^1/g - b/a = x

        val k = src[1].toDouble().pow((-src[0]).toDouble()).toFloat();  // (1/a)^g == a^-g
        inv[0] = 1.0f / src[0];
        inv[1] = k;
        inv[2] = -k * src[5];
        inv[5] = -src[2] / src[1];

        // We need to enforce the same constraints here that we do when fitting a curve,
        // a >= 0 and ad+b >= 0.  These constraints are checked by classify(), so they're true
        // of the source function if we're here.

        // Just like when fitting the curve, there's really no way to rescue a < 0.
        if (inv[1] < 0) {
            throw IllegalArgumentException()
        }
        // On the other hand we can rescue an ad+b that's gone slightly negative here.
        if (inv[1] * inv[4] + inv[2] < 0) {
            inv[2] = -inv[1] * inv[4];
        }

        assert(inv[1] >= 0);
        assert(inv[1] * inv[4] + inv[2] >= 0);

        // Now in principle we're done.
        // But to preserve the valuable invariant inv(src(1.0f)) == 1.0f, we'll tweak
        // e or f of the inverse, depending on which segment contains src(1.0f).
        var s = skcms_TransferFunction_eval(src, 1.0f);

        val sign = if (s < 0) -1.0f else 1.0f
        s *= sign;
        if (s < inv[4]) {
            inv[6] = 1.0f - sign * inv[3] * s;
        } else {
            inv[5] =
                (1.0f - sign * Math.pow(
                    (inv[1] * s + inv[2]).toDouble(),
                    inv[0].toDouble(),
                )).toFloat();
        }

        System.arraycopy(inv, 0, dest, 0, inv.size)
    }

    fun onContextCreated() {
        mProgram = createProgram(mVertexShader, mFragmentShader)
        if (mProgram == 0) {
            return
        }
        maPositionHandle = glGetAttribLocation(mProgram, "aPosition")
        checkGlError("glGetAttribLocation aPosition")
        if (maPositionHandle == -1) {
            throw java.lang.RuntimeException("Could not get attrib location for aPosition")
        }
        maTextureHandle = glGetAttribLocation(mProgram, "aTextureCoord")
        checkGlError("glGetAttribLocation aTextureCoord")
        if (maTextureHandle == -1) {
            throw java.lang.RuntimeException("Could not get attrib location for aTextureCoord")
        }

        muMVPMatrixHandle = glGetUniformLocation(mProgram, "uMVPMatrix")
        checkGlError("glGetUniformLocation uMVPMatrix")
        if (muMVPMatrixHandle == -1) {
            throw java.lang.RuntimeException("Could not get attrib location for uMVPMatrix")
        }

        mDestTFHandle = glGetUniformLocation(mProgram, "destTF")
        mWHandle = uniform("W")

        val textures = IntArray(2)
        glGenTextures(2, textures, 0)

        if (bitmap.config == Bitmap.Config.HARDWARE
            || bitmap.gainmap!!.gainmapContents.config == Bitmap.Config.HARDWARE
        ) {
            throw IllegalArgumentException("Cannot handle HARDWARE bitmaps")
        }

        mTextureID = textures[0]
        mGainmapTextureID = textures[1]
        glBindTexture(GL_TEXTURE_2D, mTextureID)
        setupTexture(bitmap)
        glBindTexture(GL_TEXTURE_2D, mGainmapTextureID)
        setupTexture(bitmap.gainmap!!.gainmapContents)

        // Bind the base & gainmap textures
        glUseProgram(mProgram)
        val textureLoc = glGetUniformLocation(mProgram, "base")
        glUniform1i(textureLoc, 0)
        val gainmapLoc = glGetUniformLocation(mProgram, "gainmap")
        glUniform1i(gainmapLoc, 1)

        // Bind the base transfer function
        val srcTF = FloatArray(7)
        System.arraycopy(kSRGB, 0, srcTF, 0, srcTF.size)
        (bitmap.colorSpace as? ColorSpace.Rgb)?.transferParameters?.let { params ->
            srcTF[0] = params.g.toFloat()
            srcTF[1] = params.a.toFloat()
            srcTF[2] = params.b.toFloat()
            srcTF[3] = params.c.toFloat()
            srcTF[4] = params.d.toFloat()
            srcTF[5] = params.e.toFloat()
            srcTF[6] = params.f.toFloat()
        }
        val srcTfHandle = glGetUniformLocation(mProgram, "srcTF")
        glUniform1fv(srcTfHandle, 7, srcTF, 0)

        val gainmap = bitmap.gainmap!!
        val isAlpha = gainmap.gainmapContents.config == Bitmap.Config.ALPHA_8
        val gainmapGamma = gainmap.gamma
        val noGamma = gainmapGamma[0] == 1f &&
                gainmapGamma[1] == 1f &&
                gainmapGamma[2] == 1f

        glUniform1i(uniform("gainmapIsAlpha"), if (isAlpha) 1 else 0)
        glUniform1i(uniform("noGamma"), if (noGamma) 1 else 0)
        setVec3Uniform("gainmapGamma", gainmapGamma)
        setLogVec3Uniform("logRatioMin", gainmap.ratioMin)
        setLogVec3Uniform("logRatioMax", gainmap.ratioMax)
        setVec3Uniform("epsilonSdr", gainmap.epsilonSdr)
        setVec3Uniform("epsilonHdr", gainmap.epsilonHdr)

        mDisplayRatioSdr = gainmap.minDisplayRatioForHdrTransition
        mDisplayRatioHdr = gainmap.displayRatioForFullHdr

        Matrix.setLookAtM(mVMatrix, 0, 0f, 0f, -5f, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
    }

    fun onContextDestroyed() {

    }

    fun uniform(name: String) = glGetUniformLocation(mProgram, name)

    fun setVec3Uniform(name: String, vec3: FloatArray) {
        glUniform3f(uniform(name), vec3[0], vec3[1], vec3[2])
    }

    fun setLogVec3Uniform(name: String, vec3: FloatArray) {
        val log = floatArrayOf(
            ln(vec3[0].toDouble()).toFloat(),
            ln(vec3[1].toDouble()).toFloat(),
            ln(vec3[2].toDouble()).toFloat(),
        )
        setVec3Uniform(name, log)
    }

    private fun setupTexture(bitmap: Bitmap?) {
        glTexParameterf(
            GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
            GL_LINEAR.toFloat(),
        )
        glTexParameterf(
            GL_TEXTURE_2D,
            GL_TEXTURE_MAG_FILTER,
            GL_LINEAR.toFloat(),
        )

        glTexParameteri(
            GL_TEXTURE_2D, GL_TEXTURE_WRAP_S,
            GL_REPEAT,
        )
        glTexParameteri(
            GL_TEXTURE_2D, GL_TEXTURE_WRAP_T,
            GL_REPEAT,
        )

        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
    }

    fun onDrawFrame(
        width: Int, height: Int, hdrSdrRatio: Float,
        bufferWidth: Int, bufferHeight: Int, transform: FloatArray,
    ) {
        glViewport(0, 0, bufferWidth, bufferHeight)
        Matrix.orthoM(
            mOrthoMatrix, 0, 0f, bufferWidth.toFloat(), 0f,
            bufferHeight.toFloat(), -1f, 1f,
        )
        Matrix.multiplyMM(mProjMatrix, 0, mOrthoMatrix, 0, transform, 0)

        glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        glClear(GL_DEPTH_BUFFER_BIT or GL_COLOR_BUFFER_BIT)
        glUseProgram(mProgram)
        checkGlError("glUseProgram")

        // TODO: This demo assumes the input image & the destination are the same color gamut
        // This isn't a good assumption to make, and applying a color gamut matrix from the
        // source to the destination should be added
        trfn_apply_gain(kSRGB, hdrSdrRatio, mDestTF);
        invert_trfn(mDestTF, mDestTF)
        glUniform1fv(mDestTFHandle, 7, mDestTF, 0)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, mTextureID)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, mGainmapTextureID)

        val targetRatio = Math.log(hdrSdrRatio.toDouble()) - Math.log(mDisplayRatioSdr.toDouble())
        val maxRatio = Math.log(mDisplayRatioHdr.toDouble()) - Math.log(mDisplayRatioSdr.toDouble())
        val Wunclamped = targetRatio / maxRatio
        val W = max(min(Wunclamped, 1.0), 0.0).toFloat()
        glUniform1f(mWHandle, W)

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        glVertexAttribPointer(
            maPositionHandle, 3, GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices,
        )
        checkGlError("glVertexAttribPointer maPosition")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")
        glVertexAttribPointer(
            maTextureHandle, 2, GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices,
        )
        checkGlError("glVertexAttribPointer maTextureHandle")
        glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")

        Matrix.setRotateM(mMMatrix, 0, 0f, 0f, 0f, 1.0f)
        val scale = width.toFloat().coerceAtMost(height.toFloat())
        Matrix.scaleM(mMVPMatrix, 0, mMMatrix, 0, scale, scale, 1f)
        Matrix.multiplyMM(mMVPMatrix, 0, mProjMatrix, 0, mMVPMatrix, 0)

        glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        glDrawArrays(GL_TRIANGLES, 0, 6)
        checkGlError("glDrawArrays")
    }
}