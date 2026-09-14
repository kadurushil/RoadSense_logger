package com.bajajauto.roadsense.camera

import android.hardware.camera2.CameraCharacteristics

/**
 * Metadata describing an available physical or logical camera device on the phone.
 */
data class CameraDeviceInfo(
    val id: String,
    val displayName: String,
    val facing: Int, // CameraCharacteristics.LENS_FACING_BACK or LENS_FACING_FRONT
    val focalLengthMm: Float,
    val isUltraWide: Boolean = false,
    val supportsOis: Boolean = false
) {
    val isBackFacing: Boolean
        get() = facing == CameraCharacteristics.LENS_FACING_BACK

    val isFrontFacing: Boolean
        get() = facing == CameraCharacteristics.LENS_FACING_FRONT
}
