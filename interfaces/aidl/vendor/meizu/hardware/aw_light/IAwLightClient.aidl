// SPDX-License-Identifier: Apache-2.0

package vendor.meizu.hardware.aw_light;

import vendor.meizu.hardware.aw_light.LightState;

@VintfStability
oneway interface IAwLightClient {
    void onStateChanged(in LightState state);
}
