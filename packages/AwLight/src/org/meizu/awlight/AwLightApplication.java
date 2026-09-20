/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight;

import android.app.Application;
import android.content.Context;

import org.meizu.awlight.manager.AwLightController;

public final class AwLightApplication extends Application {
    private AwLightController mController;

    @Override
    public void onCreate() {
        super.onCreate();
        mController = new AwLightController(this);
    }

    public static AwLightController controller(Context context) {
        return ((AwLightApplication) context.getApplicationContext()).mController;
    }
}
