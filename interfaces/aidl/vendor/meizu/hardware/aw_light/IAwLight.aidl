// SPDX-License-Identifier: Apache-2.0

package vendor.meizu.hardware.aw_light;

import vendor.meizu.hardware.aw_light.IAwLightClient;
import vendor.meizu.hardware.aw_light.LightRequest;
import vendor.meizu.hardware.aw_light.LightState;

@VintfStability
interface IAwLight {
    void request(in IAwLightClient client, int requestId, in LightRequest request);
    void cancel(in IAwLightClient client, int requestId);
    void release(in IAwLightClient client);
    LightState getState();
}
