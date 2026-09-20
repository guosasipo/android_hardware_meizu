// SPDX-License-Identifier: Apache-2.0

package vendor.meizu.hardware.aw_light;

@VintfStability
parcelable LightState {
    long owner = 0;
    int requestId = -1;
    int effect = 0;
    boolean active = false;
    boolean animated = false;
    int error = 0;
}
