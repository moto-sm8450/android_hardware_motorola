/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.motorola.internal.enterprise

/**
 * Placeholder for Motorola's enterprise manager. MotoFaceUnlockArcSoft casts the result of
 * getSystemService() to this type and only calls hasUserRestriction(); stock framework.jar does not
 * define the class, so without this the cast throws NoClassDefFoundError (the APK only catches
 * SecurityException). On Lineage getSystemService() returns null and the APK logs
 * "MotoDevicePolicyManager not found", so this never runs.
 *
 * Kept `open` (Java classes are non-final by default) and the parameter nullable so Kotlin does not
 * emit a kotlin.jvm.internal.Intrinsics null-check, which would need kotlin-stdlib at runtime.
 */
open class MotoDevicePolicyManager {
    @Suppress("UNUSED_PARAMETER") open fun hasUserRestriction(restriction: String?): Boolean = false
}
