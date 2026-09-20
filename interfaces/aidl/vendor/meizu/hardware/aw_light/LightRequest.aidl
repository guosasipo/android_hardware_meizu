// SPDX-License-Identifier: Apache-2.0

package vendor.meizu.hardware.aw_light;

@VintfStability
parcelable LightRequest {
    int effect;
    int color = 16777215;
    float strength = 1.0f;
    int progress = 0;
    int amplitude = 0;
    int letter = 0;
    int priority = 0;
    int timeoutMs = 0;
    boolean resume = false;
}
