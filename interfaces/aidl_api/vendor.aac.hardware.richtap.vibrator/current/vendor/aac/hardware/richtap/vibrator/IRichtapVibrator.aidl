/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
///////////////////////////////////////////////////////////////////////////////
// THIS FILE IS IMMUTABLE. DO NOT EDIT IN ANY CASE.                          //
///////////////////////////////////////////////////////////////////////////////

// This file is a snapshot of an AIDL file. Do not edit it manually. There are
// two cases:
// 1). this is a frozen version file - do not edit this in any case.
// 2). this is a 'current' file. If you make a backwards compatible change to
//     the interface (from the latest frozen version), the build system will
//     prompt you to update this file with `m <name>-update-api`.
//
// You must not make a backward incompatible change to any AIDL file built
// with the aidl_interface module type with versions property set. The module
// type is used to build AIDL files in a way that they can be used across
// independently updatable components of the system. If a device is shipped
// with such a backward incompatible change, it has a high risk of breaking
// later when a module using the interface is updated, e.g., Mainline modules.

package vendor.aac.hardware.richtap.vibrator;
@VintfStability
interface IRichtapVibrator {
  oneway void init(in vendor.aac.hardware.richtap.vibrator.IRichtapCallback callback);
  oneway void setDynamicScale(in int scale, in vendor.aac.hardware.richtap.vibrator.IRichtapCallback callback);
  oneway void setF0(in int f0, in vendor.aac.hardware.richtap.vibrator.IRichtapCallback callback);
  void stop(in vendor.aac.hardware.richtap.vibrator.IRichtapCallback callback);
  oneway void setAmplitude(in int amplitude, in vendor.aac.hardware.richtap.vibrator.IRichtapCallback callback);
  void performHeParam(in int interval, in int amplitude, in int freq, in vendor.aac.hardware.richtap.vibrator.IRichtapCallback callback);
  oneway void off(in vendor.aac.hardware.richtap.vibrator.IRichtapCallback callback);
  oneway void on(in int timeoutMs, in vendor.aac.hardware.richtap.vibrator.IRichtapCallback callback);
  int perform(in int effect_id, in byte strength, in vendor.aac.hardware.richtap.vibrator.IRichtapCallback callback);
  oneway void performEnvelope(in int[] envInfo, in boolean fastFlag, in vendor.aac.hardware.richtap.vibrator.IRichtapCallback callback);
  oneway void performRtp(in ParcelFileDescriptor hdl, in vendor.aac.hardware.richtap.vibrator.IRichtapCallback callback);
  oneway void performHe(in int looper, in int interval, in int amplitude, in int freq, in int[] he, in vendor.aac.hardware.richtap.vibrator.IRichtapCallback callback);
}
