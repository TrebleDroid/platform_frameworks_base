/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.biometrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.os.FileObserver
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import com.android.systemui.R
import com.android.systemui.doze.DozeReceiver
import java.io.File

import vendor.goodix.hardware.biometrics.fingerprint.V2_1.IGoodixFingerprintDaemon

private const val TAG = "UdfpsView"


/**
 * The main view group containing all UDFPS animations.
 */
class UdfpsView(
    context: Context,
    attrs: AttributeSet?
) : FrameLayout(context, attrs), DozeReceiver {
    private var currentOnIlluminatedRunnable: Runnable? = null
    private val mySurfaceView = SurfaceView(context)
    init {
        mySurfaceView.setVisibility(INVISIBLE)
        mySurfaceView.setZOrderOnTop(true)
        addView(mySurfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        mySurfaceView.holder.addCallback(object: SurfaceHolder.Callback{
            override fun surfaceCreated(p0: SurfaceHolder) {
                Log.d("PHH", "Surface created!")
                val paint = Paint(0 /* flags */);
                paint.setAntiAlias(true);
                paint.setStyle(Paint.Style.FILL);
                val colorStr = android.os.SystemProperties.get("persist.sys.phh.fod_color", "00ff00");
                try {
                    val parsedColor = Color.parseColor("#" + colorStr);
                    val r = (parsedColor shr 16) and 0xff;
                    val g = (parsedColor shr  8) and 0xff;
                    val b = (parsedColor shr  0) and 0xff;
                    paint.setARGB(255, r, g, b);
                } catch(t: Throwable) {
                    Log.d("PHH", "Failed parsing color #" + colorStr, t);
                }
                var canvas: Canvas? = null
                try {
                    canvas = p0.lockCanvas();
Log.d("PHH", "Surface dimensions ${canvas.getWidth()*1.0f} ${canvas.getHeight()*1.0f}")
                    canvas.drawOval(RectF(0.0f, 0.0f, canvas.getWidth()*1.0f, canvas.getHeight()*1.0f), paint);
                } finally {
                    // Make sure the surface is never left in a bad state.
                    if (canvas != null) {
                        p0.unlockCanvasAndPost(canvas);
                    }
                }

                currentOnIlluminatedRunnable?.run()
            }

            override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
Log.d("PHH", "Got surface size $p1 $p2 $p3")
            }

            override fun surfaceDestroyed(p0: SurfaceHolder) {
Log.d("PHH", "Surface destroyed!")
            }
        })
        mySurfaceView.holder.setFormat(PixelFormat.RGBA_8888)

    }

    // sensorRect may be bigger than the sensor. True sensor dimensions are defined in
    // overlayParams.sensorBounds
    private val sensorRect = RectF()
    private var mUdfpsDisplayMode: UdfpsDisplayModeProvider? = null
    private val debugTextPaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLUE
        textSize = 32f
    }

    private val sensorTouchAreaCoefficient: Float =
        context.theme.obtainStyledAttributes(attrs, R.styleable.UdfpsView, 0, 0).use { a ->
            require(a.hasValue(R.styleable.UdfpsView_sensorTouchAreaCoefficient)) {
                "UdfpsView must contain sensorTouchAreaCoefficient"
            }
            a.getFloat(R.styleable.UdfpsView_sensorTouchAreaCoefficient, 0f)
        }

    /** View controller (can be different for enrollment, BiometricPrompt, Keyguard, etc.). */
    var animationViewController: UdfpsAnimationViewController<*>? = null

    /** Parameters that affect the position and size of the overlay. */
    var overlayParams = UdfpsOverlayParams()

    var dimUpdate: (Float) -> Unit = {}

    /** Debug message. */
    var debugMessage: String? = null
        set(value) {
            field = value
            postInvalidate()
        }

    /** True after the call to [configureDisplay] and before the call to [unconfigureDisplay]. */
    var isDisplayConfigured: Boolean = false
        private set

    fun setUdfpsDisplayModeProvider(udfpsDisplayModeProvider: UdfpsDisplayModeProvider?) {
        mUdfpsDisplayMode = udfpsDisplayModeProvider
    }

    // Don't propagate any touch events to the child views.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return (animationViewController == null || !animationViewController!!.shouldPauseAuth())
    }

    override fun dozeTimeTick() {
        animationViewController?.dozeTimeTick()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        val paddingX = animationViewController?.paddingX ?: 0
        val paddingY = animationViewController?.paddingY ?: 0

        sensorRect.set(
            paddingX.toFloat(),
            paddingY.toFloat(),
            (overlayParams.sensorBounds.width() + paddingX).toFloat(),
            (overlayParams.sensorBounds.height() + paddingY).toFloat()
        )
        animationViewController?.onSensorRectUpdated(RectF(sensorRect))
    }

    fun onTouchOutsideView() {
        animationViewController?.onTouchOutsideView()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.v(TAG, "onAttachedToWindow")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.v(TAG, "onDetachedFromWindow")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isDisplayConfigured) {
            if (!debugMessage.isNullOrEmpty()) {
                canvas.drawText(debugMessage!!, 0f, 160f, debugTextPaint)
            }
        }
    }

    fun isWithinSensorArea(x: Float, y: Float): Boolean {
        // The X and Y coordinates of the sensor's center.
        val translation = animationViewController?.touchTranslation ?: PointF(0f, 0f)
        val cx = sensorRect.centerX() + translation.x
        val cy = sensorRect.centerY() + translation.y
        // Radii along the X and Y axes.
        val rx = (sensorRect.right - sensorRect.left) / 2.0f
        val ry = (sensorRect.bottom - sensorRect.top) / 2.0f

        return x > cx - rx * sensorTouchAreaCoefficient &&
            x < cx + rx * sensorTouchAreaCoefficient &&
            y > cy - ry * sensorTouchAreaCoefficient &&
            y < cy + ry * sensorTouchAreaCoefficient &&
            !(animationViewController?.shouldPauseAuth() ?: false)
    }

    fun goodixCmd(id: Int) {
        val goodixSvc = IGoodixFingerprintDaemon.getService()
        if(goodixSvc != null) {
            goodixSvc.sendCommand(id, ArrayList(), { returnCode, resultData -> {
                Log.e("PHH-Enroll", "Goodix send command returned code "+ returnCode);
            }});
        }
    }

    val asusGhbmOnAchieved = "/sys/class/drm/ghbm_on_achieved"
    var hasAsusGhbm = File(asusGhbmOnAchieved).exists()
    var samsungActualMaskBrightness = "/sys/class/lcd/panel/actual_mask_brightness"
    val hasSamsungMask = File(samsungActualMaskBrightness).exists()
    var fodFileObserver: FileObserver? = null

    fun configureDisplay(onDisplayConfigured: Runnable) {
        isDisplayConfigured = true
        animationViewController?.onDisplayConfiguring()
        mUdfpsDisplayMode?.enable(onDisplayConfigured)

        mySurfaceView.setVisibility(VISIBLE)
        Log.d("PHH", "setting surface visible!")

        val brightness = File("/sys/class/backlight/panel0-backlight/brightness").readText().toDouble()
        val maxBrightness = File("/sys/class/backlight/panel0-backlight/max_brightness").readText().toDouble()

        // Assume HBM is max brightness
        val dim = 1.0 - Math.pow( (brightness / maxBrightness), 1/2.3);
        Log.d("PHH-Enroll", "Brightness is $brightness / $maxBrightness, setting dim to $dim")
        if (hasAsusGhbm) {
            dimUpdate(dim.toFloat())
        }
        if (hasSamsungMask) {
            dimUpdate(dim.toFloat())
        }

        if(android.os.SystemProperties.get("ro.vendor.build.fingerprint").contains("ASUS")) {
            goodixCmd(200001)
        }
    }

    fun unconfigureDisplay() {
        isDisplayConfigured = false
        animationViewController?.onDisplayUnconfigured()
        mUdfpsDisplayMode?.disable(null /* onDisabled */)

        if (hasAsusGhbm) {
            fodFileObserver = object: FileObserver(asusGhbmOnAchieved, FileObserver.MODIFY) {
                override fun onEvent(event: Int, path: String): Unit {
                    Log.d("PHH-Enroll", "Asus ghbm event")
                    try {
                        val spotOn = File(asusGhbmOnAchieved).readText().toInt()
                        if(spotOn == 0) {
                            dimUpdate(0.0f)
                            fodFileObserver?.stopWatching()
                            fodFileObserver = null
                        }
                    } catch(e: Exception) {
                        Log.d("PHH-Enroll", "Failed dimpdate off", e)
                    }
                }
            };
            fodFileObserver?.startWatching();
        } else if (hasSamsungMask) {
            fodFileObserver = object: FileObserver(asusGhbmOnAchieved, FileObserver.MODIFY) {
                override fun onEvent(event: Int, path: String): Unit {
                    Log.d("PHH-Enroll", "samsung mask brightness event")
                    try {
                        val spotOn = File(samsungActualMaskBrightness).readText().toInt()
                        if(spotOn == 0) {
                            dimUpdate(0.0f)
                            fodFileObserver?.stopWatching()
                            fodFileObserver = null
                        }
                    } catch(e: Exception) {
                        Log.d("PHH-Enroll", "Failed dimpdate off", e)
                    }
                }
            };
            fodFileObserver?.startWatching();
        } else {
            dimUpdate(0.0f)
        }

        mySurfaceView.setVisibility(INVISIBLE)
        Log.d("PHH", "setting surface invisible!")
        if(android.os.SystemProperties.get("ro.vendor.build.fingerprint").contains("ASUS")) {
            goodixCmd(200003)
        }
    }
}
