/*
 * Copyright 2018 Urban Airship and Contributors
 */

package com.gimbal.airship;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Broadcast receiver for Airship Ready events.
 */
public class AirshipReadyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        AirshipAdapter.shared(context).unloadQueuedEvents();
    }
}