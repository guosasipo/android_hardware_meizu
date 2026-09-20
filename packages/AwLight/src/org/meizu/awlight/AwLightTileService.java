/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.meizu.awlight.manager.AwLightController;

public final class AwLightTileService extends TileService {
    private final AwLightController.Observer mObserver = this::updateTile;
    private AwLightController mController;
    private boolean mListening;

    public static void requestUpdate(Context context) {
        Context app = context.getApplicationContext();
        requestListeningState(app, new ComponentName(app, AwLightTileService.class));
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (mController == null) mController = AwLightApplication.controller(this);
        if (!mListening) {
            mListening = true;
            mController.addObserver(mObserver);
        } else {
            updateTile(mController.getSnapshot());
        }
    }

    @Override
    public void onStopListening() {
        stopListening();
        super.onStopListening();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopListening();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopListening();
        super.onDestroy();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (mController == null) mController = AwLightApplication.controller(this);
        if (mController.getSnapshot().canToggleManual) mController.toggleManual();
    }

    private void stopListening() {
        if (mListening) {
            mListening = false;
            mController.removeObserver(mObserver);
        }
    }

    private void updateTile(AwLightController.Snapshot state) {
        if (!mListening) return;
        Tile tile = getQsTile();
        if (tile == null) return;

        int status = state.manualActive ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        String subtitle;
        if (state.safetyBlocked) {
            subtitle = getString(R.string.safety_blocked);
        } else if (!state.preferencesValid || !state.connected) {
            subtitle = getString(R.string.service_unavailable);
        } else if (!state.canToggleManual) {
            subtitle = getString(R.string.unlock_required);
        } else if (state.manualRequested && !state.manualActive
                && state.manualBrightness != 0) {
            subtitle = getString(R.string.tile_waiting);
        } else {
            subtitle = selectedMode(state);
        }

        String label = getString(R.string.app_name);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_light_ring));
        tile.setLabel(label);
        tile.setSubtitle(subtitle);
        tile.setContentDescription(label + ", " + subtitle);
        tile.setState(status);
        tile.updateTile();
    }

    private String selectedMode(AwLightController.Snapshot state) {
        int mode = switch (state.preferences.manualEffect) {
            case 32 -> R.string.mode_cycle;
            case 67 -> R.string.mode_rotate;
            default -> R.string.mode_solid;
        };
        return getString(R.string.tile_choice, getString(mode),
                Math.round(state.manualBrightness * 100));
    }
}
