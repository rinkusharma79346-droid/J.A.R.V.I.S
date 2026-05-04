package com.vayu.agent

import android.animation.*
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.*
import kotlin.random.Random

/**
 * V.A.Y.U Bot Face — Interactive 3D-style spherical avatar
 *
 * Features:
 * - Spherical gradient with specular highlight
 * - Animated eyes that follow touch position
 * - Breathing/pulse animation
 * - Expression changes (happy, thinking, idle)
 * - Floating particles around the sphere
 * - Touch interaction (eyes follow finger)
 * - Smooth glow effect
 */
class VayuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class BotState { IDLE, THINKING, HAPPY, WORKING }

    var currentState: BotState = BotState.IDLE
        set(value) { field = value; invalidate() }

    // Touch tracking
    private var touchX = 0f
    private var touchY = 0f
    private var isTouched = false

    // Animation values
    private var breathScale = 1f
    private var glowAlpha = 0.4f
    private var eyeOffsetX = 0f
    private var eyeOffsetY = 0f
    private var thinkPhase = 0f
    private var particlePhase = 0f

    // Paints
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Particles
    private data class Particle(val angle: Float, val radius: Float, val speed: Float, val size: Float, val alpha: Float)
    private val particles = mutableListOf<Particle>()

    // Animators
    private val breathAnimator: ValueAnimator
    private val glowAnimator: ValueAnimator
    private val thinkAnimator: ValueAnimator
    private val particleAnimator: ValueAnimator

    init {
        // Initialize particles
        repeat(20) {
            particles.add(Particle(
                angle = Random.nextFloat() * 360f,
                radius = 0.55f + Random.nextFloat() * 0.25f,
                speed = 0.2f + Random.nextFloat() * 0.8f,
                size = 1.5f + Random.nextFloat() * 3f,
                alpha = 0.2f + Random.nextFloat() * 0.5f
            ))
        }

        // Breathing animation
        breathAnimator = ValueAnimator.ofFloat(0.96f, 1.04f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                breathScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // Glow pulse
        glowAnimator = ValueAnimator.ofFloat(0.2f, 0.6f).apply {
            duration = 2500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                glowAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // Thinking eye movement
        thinkAnimator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                thinkPhase = it.animatedValue as Float
                if (currentState == BotState.THINKING && !isTouched) {
                    eyeOffsetX = sin(thinkPhase.toDouble()).toFloat() * 8f
                    eyeOffsetY = cos(thinkPhase * 2).toFloat() * 3f
                    invalidate()
                }
            }
            start()
        }

        // Particle rotation
        particleAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 15000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                particlePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // Configure paints
        eyeGlowPaint.style = Paint.Style.FILL
        eyeGlowPaint.color = Color.argb(80, 255, 200, 150)
        mouthPaint.style = Paint.Style.STROKE
        mouthPaint.strokeCap = Paint.Cap.ROUND
        mouthPaint.strokeJoin = Paint.Join.ROUND
        particlePaint.style = Paint.Style.FILL
        ringPaint.style = Paint.Style.STROKE
        ringPaint.color = Color.argb(30, 224, 122, 79)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isTouched = true
                touchX = event.x
                touchY = event.y
                val cx = width / 2f
                val cy = height / 2f
                val dx = touchX - cx
                val dy = touchY - cy
                val maxOffset = width * 0.06f
                val dist = sqrt(dx * dx + dy * dy)
                val norm = (dist / (width * 0.4f)).coerceIn(0f, 1f)
                eyeOffsetX = (dx / dist.coerceAtLeast(1f)) * maxOffset * norm
                eyeOffsetY = (dy / dist.coerceAtLeast(1f)) * maxOffset * norm
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouched = false
                eyeOffsetX = 0f
                eyeOffsetY = 0f
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (min(width, height) * 0.35f).coerceAtLeast(20f)
        val radius = baseRadius * breathScale

        canvas.save()
        canvas.translate(cx, cy)

        // ─── Outer Glow ───
        drawOuterGlow(canvas, radius)

        // ─── Floating Particles ───
        drawParticles(canvas, radius)

        // ─── Main Sphere ───
        drawSphere(canvas, radius)

        // ─── Orbital Ring ───
        drawOrbitalRing(canvas, radius)

        // ─── Eyes ───
        drawEyes(canvas, radius)

        // ─── Mouth ───
        drawMouth(canvas, radius)

        canvas.restore()
    }

    private fun drawOuterGlow(canvas: Canvas, radius: Float) {
        val glowRadius = radius * 1.8f
        glowPaint.shader = RadialGradient(0f, 0f, radius * 0.5f, 0f, 0f, glowRadius, longArrayOf(
            Color.argb((glowAlpha * 100).toInt(), 224, 122, 79).toLong(),
            Color.argb((glowAlpha * 60).toInt(), 224, 122, 79).toLong(),
            Color.argb(0, 224, 122, 79).toLong()
        ), floatArrayOf(0.2f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, glowRadius, glowPaint)
    }

    private fun drawParticles(canvas: Canvas, radius: Float) {
        particles.forEach { p ->
            val angleRad = Math.toRadians((p.angle + particlePhase * p.speed).toDouble())
            val pr = radius * p.radius
            val px = cos(angleRad).toFloat() * pr
            val py = sin(angleRad).toFloat() * pr * 0.6f // Slightly elliptical orbit
            val pulseAlpha = (p.alpha * (0.5f + 0.5f * sin((particlePhase * p.speed * 0.05f).toDouble()).toFloat()))
            particlePaint.color = Color.argb((pulseAlpha * 255).toInt(), 255, 176, 136)
            canvas.drawCircle(px, py, p.size * breathScale, particlePaint)
        }
    }

    private fun drawSphere(canvas: Canvas, radius: Float) {
        // Main sphere gradient
        val gradColors = longArrayOf(
            Color.parseColor("#F4A261").toLong(),  // Light orange top-left
            Color.parseColor("#E07A4F").toLong(),  // Primary orange
            Color.parseColor("#C4603A").toLong(),  // Darker orange
            Color.parseColor("#8B3A1F").toLong()   // Dark bottom-right
        )
        val gradPositions = floatArrayOf(0f, 0.35f, 0.7f, 1f)
        spherePaint.shader = RadialGradient(
            -radius * 0.3f, -radius * 0.3f, radius * 0.1f,
            0f, 0f, radius,
            gradColors, gradPositions, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(0f, 0f, radius, spherePaint)

        // Specular highlight
        specularPaint.shader = RadialGradient(
            -radius * 0.25f, -radius * 0.3f, 0f,
            -radius * 0.25f, -radius * 0.3f, radius * 0.5f,
            longArrayOf(
                Color.argb(180, 255, 255, 255).toLong(),
                Color.argb(60, 255, 240, 220).toLong(),
                Color.argb(0, 255, 255, 255).toLong()
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(-radius * 0.25f, -radius * 0.3f, radius * 0.5f, specularPaint)

        // Subtle rim light
        ringPaint.shader = null
        ringPaint.color = Color.argb(40, 244, 162, 97)
        ringPaint.strokeWidth = 2f
        canvas.drawCircle(0f, 0f, radius - 1f, ringPaint)
    }

    private fun drawOrbitalRing(canvas: Canvas, radius: Float) {
        ringPaint.color = Color.argb(25, 224, 122, 79)
        ringPaint.strokeWidth = 1.5f
        canvas.drawOval(RectF(-radius * 1.15f, -radius * 0.35f, radius * 1.15f, radius * 0.35f), ringPaint)

        // Small dot on ring
        val ringAngle = Math.toRadians((particlePhase * 1.5).toDouble())
        val dotX = cos(ringAngle).toFloat() * radius * 1.15f
        val dotY = sin(ringAngle).toFloat() * radius * 0.35f
        particlePaint.color = Color.argb(200, 244, 162, 97)
        canvas.drawCircle(dotX, dotY, 3f, particlePaint)
    }

    private fun drawEyes(canvas: Canvas, radius: Float) {
        val eyeSpacing = radius * 0.28f
        val eyeY = -radius * 0.1f
        val eyeRadius = radius * 0.12f
        val pupilRadius = radius * 0.06f

        val maxPupilOffset = eyeRadius * 0.35f
        val pox = (eyeOffsetX / (width * 0.06f).coerceAtLeast(1f)) * maxPupilOffset
        val poy = (eyeOffsetY / (width * 0.06f).coerceAtLeast(1f)) * maxPupilOffset

        // Determine eye appearance based on state
        when (currentState) {
            BotState.THINKING -> {
                // Squinting eyes (narrower vertically)
                val squint = radius * 0.06f
                drawSingleEye(canvas, -eyeSpacing, eyeY, eyeRadius, eyeRadius * squint / eyeRadius, pox, poy, pupilRadius)
                drawSingleEye(canvas, eyeSpacing, eyeY, eyeRadius, eyeRadius * squint / eyeRadius, pox, poy, pupilRadius)
            }
            BotState.HAPPY -> {
                // Happy curved eyes (^_^)
                drawHappyEye(canvas, -eyeSpacing, eyeY, eyeRadius)
                drawHappyEye(canvas, eyeSpacing, eyeY, eyeRadius)
            }
            BotState.WORKING -> {
                // Wide focused eyes
                drawSingleEye(canvas, -eyeSpacing, eyeY, eyeRadius * 1.15f, eyeRadius * 1.1f, pox, poy, pupilRadius * 0.8f)
                drawSingleEye(canvas, eyeSpacing, eyeY, eyeRadius * 1.15f, eyeRadius * 1.1f, pox, poy, pupilRadius * 0.8f)
            }
            else -> {
                // Normal idle eyes
                drawSingleEye(canvas, -eyeSpacing, eyeY, eyeRadius, eyeRadius, pox, poy, pupilRadius)
                drawSingleEye(canvas, eyeSpacing, eyeY, eyeRadius, eyeRadius, pox, poy, pupilRadius)
            }
        }
    }

    private fun drawSingleEye(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float, pupilOx: Float, pupilOy: Float, pupilRadius: Float) {
        // Eye glow
        eyeGlowPaint.color = Color.argb(50, 255, 200, 150)
        canvas.drawOval(RectF(x - rx - 4, y - ry - 4, x + rx + 4, y + ry + 4), eyeGlowPaint)

        // Eye white
        eyePaint.color = Color.argb(240, 255, 248, 240)
        canvas.drawOval(RectF(x - rx, y - ry, x + rx, y + ry), eyePaint)

        // Pupil (iris)
        pupilPaint.shader = RadialGradient(x + pupilOx, y + pupilOy, 0f, x + pupilOx, y + pupilOy, pupilRadius, longArrayOf(
                Color.argb(255, 30, 20, 15).toLong(),
                Color.argb(200, 100, 60, 40).toLong()
            ), floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x + pupilOx, y + pupilOy, pupilRadius, pupilPaint)

        // Pupil highlight
        val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        hlPaint.color = Color.argb(200, 255, 255, 255)
        canvas.drawCircle(x + pupilOx - pupilRadius * 0.25f, y + pupilOy - pupilRadius * 0.25f, pupilRadius * 0.3f, hlPaint)
    }

    private fun drawHappyEye(canvas: Canvas, x: Float, y: Float, eyeRadius: Float) {
        mouthPaint.color = Color.argb(230, 255, 248, 240)
        mouthPaint.strokeWidth = eyeRadius * 0.25f
        val arcRect = RectF(x - eyeRadius, y - eyeRadius * 0.5f, x + eyeRadius, y + eyeRadius * 0.8f)
        canvas.drawArc(arcRect, 200f, 140f, false, mouthPaint)
    }

    private fun drawMouth(canvas: Canvas, radius: Float) {
        val mouthY = radius * 0.25f
        val mouthWidth = radius * 0.3f
        mouthPaint.color = Color.argb(180, 80, 40, 20)

        when (currentState) {
            BotState.HAPPY -> {
                mouthPaint.strokeWidth = radius * 0.06f
                val arcRect = RectF(-mouthWidth, mouthY - radius * 0.1f, mouthWidth, mouthY + radius * 0.25f)
                canvas.drawArc(arcRect, 210f, 120f, false, mouthPaint)
            }
            BotState.THINKING -> {
                // Small 'o' mouth
                mouthPaint.style = Paint.Style.STROKE
                mouthPaint.strokeWidth = radius * 0.04f
                canvas.drawOval(RectF(-radius * 0.06f, mouthY, radius * 0.06f, mouthY + radius * 0.12f), mouthPaint)
                mouthPaint.style = Paint.Style.STROKE
            }
            BotState.WORKING -> {
                // Determined straight line
                mouthPaint.strokeWidth = radius * 0.05f
                canvas.drawLine(-mouthWidth * 0.5f, mouthY + radius * 0.05f, mouthWidth * 0.5f, mouthY + radius * 0.05f, mouthPaint)
            }
            else -> {
                // Gentle smile
                mouthPaint.strokeWidth = radius * 0.05f
                val arcRect = RectF(-mouthWidth, mouthY - radius * 0.05f, mouthWidth, mouthY + radius * 0.18f)
                canvas.drawArc(arcRect, 215f, 110f, false, mouthPaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        breathAnimator.cancel()
        glowAnimator.cancel()
        thinkAnimator.cancel()
        particleAnimator.cancel()
    }
}
