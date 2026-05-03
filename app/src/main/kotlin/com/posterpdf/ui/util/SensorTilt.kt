package com.posterpdf.ui.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Smoothed phone tilt as (roll, pitch) radians, derived from
 * [Sensor.TYPE_ROTATION_VECTOR] (sensor-fused gyro + accel + magnetometer).
 *
 * Why rotation vector? It's the preferred fused source: gravity tilts
 * (accel-only) get noisy under motion, gyro alone drifts. The fused vector
 * handles both. Output is a unit quaternion which we convert to a 3x3
 * rotation matrix and read pitch/roll off the gravity axis.
 *
 * Smoothing: a one-pole IIR low-pass with `alpha = 0.15` per sensor frame.
 * That's fast enough to track real phone tilt (~80% step-response in ~10
 * frames at the typical 50–100 Hz sensor rate) but slow enough to suppress
 * the high-frequency hand-tremor jitter that would otherwise make the
 * glint shimmer flicker.
 *
 * Returns a [State]<Pair<Float, Float>> = (roll, pitch) in radians; both
 * components are 0f if the device has no rotation sensor (the caller must
 * gracefully fall back).
 *
 * Listener registration is bound to the composable's lifetime via
 * [DisposableEffect] — the listener is unregistered on dispose so we don't
 * burn battery while the modal is closed.
 */
@Composable
fun rememberDeviceTilt(): State<Pair<Float, Float>> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(0f to 0f) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (sensorManager == null || rotationSensor == null) {
            // Device has no rotation sensor; leave (0f, 0f) and skip listener.
            return@DisposableEffect onDispose { /* no-op */ }
        }

        // Reusable scratch arrays — allocating inside onSensorChanged would
        // create per-frame GC pressure on low-end phones.
        val rotMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        // Quaternion buffer: getRotationMatrixFromVector accepts up to 5 components.
        var smoothRoll = 0f
        var smoothPitch = 0f
        var initialised = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                // SensorManager handles >4-component rotation-vector arrays
                // (some OEMs include heading-accuracy as event.values[4]).
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                SensorManager.getOrientation(rotMatrix, orientation)
                // orientation[0] = azimuth (yaw, ignored — tilt only cares
                //   about how the device is held, not which way it points)
                // orientation[1] = pitch (front/back tilt, +ve = leaning back)
                // orientation[2] = roll  (left/right tilt, +ve = right side down)
                val rawRoll = orientation[2]
                val rawPitch = orientation[1]

                // One-pole IIR low-pass (alpha = 0.15 chosen empirically:
                // smooth enough to suppress hand-tremor at ~10–15 Hz,
                // responsive enough that a deliberate tilt feels live).
                val alpha = 0.15f
                if (!initialised) {
                    smoothRoll = rawRoll
                    smoothPitch = rawPitch
                    initialised = true
                } else {
                    smoothRoll += alpha * (rawRoll - smoothRoll)
                    smoothPitch += alpha * (rawPitch - smoothPitch)
                }
                tilt.value = smoothRoll to smoothPitch
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
        }

        sensorManager.registerListener(
            listener,
            rotationSensor,
            SensorManager.SENSOR_DELAY_GAME, // ~50 Hz, plenty for a glint UV-shift
        )

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    return tilt
}
