/*
 * Copyright 2018 Urban Airship and Contributors
 */

package com.gimbal.airship;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

import com.gimbal.android.Attributes;
import com.gimbal.android.DeviceAttributesManager;
import com.gimbal.android.Gimbal;
import com.gimbal.android.PlaceEventListener;
import com.gimbal.android.PlaceManager;
import com.gimbal.android.Visit;
import com.urbanairship.UAirship;
import com.urbanairship.analytics.CustomEvent;
import com.urbanairship.analytics.location.RegionEvent;
import com.urbanairship.channel.AirshipChannelListener;
import com.urbanairship.json.JsonValue;
import com.urbanairship.util.DateUtils;
import com.urbanairship.util.HelperActivity;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * GimbalAdapter interfaces Gimbal SDK functionality with Urban Airship services.
 */
@SuppressWarnings({"unused"})
public class AirshipAdapter {
    private static final String PREFERENCE_NAME = "com.urbanairship.gimbal.preferences";
    private static final String API_KEY_PREFERENCE = "com.urbanairship.gimbal.api_key";
    private static final String TRACK_CUSTOM_ENTRY_PREFERENCE_KEY = "com.gimbal.track_custom_entry";
    private static final String TRACK_CUSTOM_EXIT_PREFERENCE_KEY = "com.gimbal.track_custom_exit";
    private static final String TRACK_REGION_EVENT_PREFERENCE_KEY = "com.gimbal.track_region_event";
    private static final String STARTED_PREFERENCE = "com.urbanairship.gimbal.is_started";

    private static final String TAG = "GimbalAdapter";
    private static final String SOURCE = "Gimbal";

    // UA to Gimbal Device Attributes
    private static final String GIMBAL_UA_NAMED_USER_ID = "ua.nameduser.id";
    private static final String GIMBAL_UA_CHANNEL_ID = "ua.channel.id";

    // Gimbal to UA Device Attributes
    private static final String UA_GIMBAL_APPLICATION_INSTANCE_ID = "com.urbanairship.gimbal.aii";

    // CustomEvent names
    private static final String CUSTOM_ENTRY_EVENT_NAME = "gimbal_custom_entry_event";
    private static final String CUSTOM_EXIT_EVENT_NAME = "gimbal_custom_exit_event";

    private final SharedPreferences preferences;
    private static AirshipAdapter instance;
    private final Context context;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean isAdapterStarted = false;
    private RequestPermissionsTask requestPermissionsTask;
    private LinkedList<CachedVisit> cachedVisits = new LinkedList<>();


    /**
     * Permission result callback.
     */
    public interface PermissionResultCallback {

        /**
         * Called with the permission result.
         *
         * @param enabled {@link true} if the permissions have been granted, otherwise {@code false}.
         */
        void onResult(boolean enabled);
    }

    /**
     * Adapter listener.
     */
    public interface Listener {

        /**
         * Called when a Urban Airship Region enter event is created from a Gimbal Visit.
         *
         * @param event The Urban Airship event.
         * @param visit The Gimbal visit.
         */
        void onRegionEntered(@NonNull RegionEvent event, @NonNull Visit visit);

        /**
         * Called when a Urban Airship Region exit event is created from a Gimbal Visit.
         *
         * @param event The Urban Airship event.
         * @param visit The Gimbal visit.
         */
        void onRegionExited(@NonNull RegionEvent event, @NonNull Visit visit);

        /**
         * Called when a Urban Airship CustomEvent entry is created from a Gimbal Visit.
         *
         * @param event The Urban Airship event.
         * @param visit The Gimbal visit.
         */
        void onCustomRegionEntry(@NonNull CustomEvent event, @NonNull Visit visit);

        /**
         * Called when a Urban Airship CustomEvent exit is created from a Gimbal Visit.
         *
         * @param event The Urban Airship event.
         * @param visit The Gimbal visit.
         */
        void onCustomRegionExit(@NonNull CustomEvent event, @NonNull Visit visit);
    }

    /**
     * Listener for Gimbal place events. Creates an analytics event
     * corresponding to boundary event type, and Event type preference.
     */
    private final PlaceEventListener placeEventListener = new PlaceEventListener() {
        @Override
        public void onVisitStart(@NonNull final Visit visit) {
            Log.i(TAG, "Entered place: " + visit.getPlace().getName() + "Entrance date: " +
                    DateUtils.createIso8601TimeStamp(visit.getArrivalTimeInMillis()));

            // If Airship is not ready yet, store visit for later
            if (!UAirship.isFlying() && !UAirship.isTakingOff()) {
                cachedVisits.add(new CachedVisit(visit, RegionEvent.BOUNDARY_EVENT_ENTER));
                return;
            }
            UAirship.shared(airship -> {
                if (preferences.getBoolean(TRACK_REGION_EVENT_PREFERENCE_KEY, false)) {
                    RegionEvent event = createRegionEvent(visit, RegionEvent.BOUNDARY_EVENT_ENTER);

                    airship.getAnalytics().addEvent(event);

                    for (Listener listener : listeners) {
                        listener.onRegionEntered(event, visit);
                    }
                }

                if (preferences.getBoolean(TRACK_CUSTOM_ENTRY_PREFERENCE_KEY, false)) {
                    CustomEvent event = createCustomEvent(CUSTOM_ENTRY_EVENT_NAME, visit, RegionEvent.BOUNDARY_EVENT_ENTER);

                    airship.getAnalytics().addEvent(event);

                    for (Listener listener : listeners) {
                        listener.onCustomRegionEntry(event, visit);
                    }
                }
            });
        }

        @Override
        public void onVisitEnd(@NonNull final Visit visit) {
            Log.i(TAG, "Exited place: " + visit.getPlace().getName() + "Entrance date: " +
                    DateUtils.createIso8601TimeStamp(visit.getArrivalTimeInMillis()) + "Exit date:" +
                    DateUtils.createIso8601TimeStamp(visit.getDepartureTimeInMillis()));

            // If Airship is not ready yet, store visit for later
            if (!UAirship.isFlying() && !UAirship.isTakingOff()) {
                cachedVisits.add(new CachedVisit(visit, RegionEvent.BOUNDARY_EVENT_EXIT));
                return;
            }
            UAirship.shared(airship -> {

                if (preferences.getBoolean(TRACK_REGION_EVENT_PREFERENCE_KEY, false)) {
                    RegionEvent event = createRegionEvent(visit, RegionEvent.BOUNDARY_EVENT_EXIT);

                    airship.getAnalytics().addEvent(event);

                    for (Listener listener : listeners) {
                        listener.onRegionExited(event, visit);
                    }
                }

                if (preferences.getBoolean(TRACK_CUSTOM_EXIT_PREFERENCE_KEY, false)) {
                    CustomEvent event = createCustomEvent(CUSTOM_EXIT_EVENT_NAME, visit, RegionEvent.BOUNDARY_EVENT_EXIT);

                    airship.getAnalytics().addEvent(event);

                    for (Listener listener : listeners) {
                        listener.onCustomRegionExit(event, visit);
                    }
                }
            });
        }
    };

    /**
     * Hidden to support the singleton pattern.
     *
     * @param context The application context.
     */
    AirshipAdapter(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.preferences = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
    }

    /**
     * GimbalAdapter shared instance.
     */
    public synchronized static AirshipAdapter shared(@NonNull Context context) {
        if (instance == null) {
            instance = new AirshipAdapter(context.getApplicationContext());
        }

        return instance;
    }

    public void unloadQueuedEvents() {
        for (CachedVisit cachedVisit: cachedVisits) {
            createAirshipEvent(cachedVisit.visit, cachedVisit.regionEvent);
        }
        cachedVisits = new LinkedList<>();
    }

    private void createAirshipEvent(Visit visit, int regionEvent) {
        UAirship airship = UAirship.shared();
        if (regionEvent == RegionEvent.BOUNDARY_EVENT_ENTER) {
            if (preferences.getBoolean(TRACK_REGION_EVENT_PREFERENCE_KEY, false)) {
                RegionEvent event = createRegionEvent(visit, RegionEvent.BOUNDARY_EVENT_ENTER);

                airship.getAnalytics().addEvent(event);

                for (Listener listener : listeners) {
                    listener.onRegionEntered(event, visit);
                }
            }

            if (preferences.getBoolean(TRACK_CUSTOM_ENTRY_PREFERENCE_KEY, false)) {
                CustomEvent event = createCustomEvent(CUSTOM_ENTRY_EVENT_NAME, visit, RegionEvent.BOUNDARY_EVENT_ENTER);

                airship.getAnalytics().addEvent(event);

                for (Listener listener : listeners) {
                    listener.onCustomRegionEntry(event, visit);
                }
            }
        }

        if (regionEvent == RegionEvent.BOUNDARY_EVENT_EXIT) {
            if (preferences.getBoolean(TRACK_REGION_EVENT_PREFERENCE_KEY, false)) {
                RegionEvent event = createRegionEvent(visit, RegionEvent.BOUNDARY_EVENT_EXIT);

                airship.getAnalytics().addEvent(event);

                for (Listener listener : listeners) {
                    listener.onRegionExited(event, visit);
                }
            }

            if (preferences.getBoolean(TRACK_CUSTOM_EXIT_PREFERENCE_KEY, false)) {
                CustomEvent event = createCustomEvent(CUSTOM_EXIT_EVENT_NAME, visit, RegionEvent.BOUNDARY_EVENT_EXIT);

                airship.getAnalytics().addEvent(event);

                for (Listener listener : listeners) {
                    listener.onCustomRegionExit(event, visit);
                }
            }
        }
    }

    private RegionEvent createRegionEvent(Visit visit, int boundaryEvent) {
        return RegionEvent.newBuilder()
                .setBoundaryEvent(boundaryEvent)
                .setSource(SOURCE)
                .setRegionId(visit.getPlace().getIdentifier())
                .build();
    }

    private CustomEvent createCustomEvent(final String eventName, final Visit visit, final int boundaryEvent) {
        if (boundaryEvent == RegionEvent.BOUNDARY_EVENT_ENTER) {
            return createCustomEventBuilder(eventName, visit, boundaryEvent).build();
        } else {
            return createCustomEventBuilder(eventName, visit, boundaryEvent)
                    .addProperty("dwellTimeInSeconds", visit.getDwellTimeInMillis() / 1000)
                    .build();
        }
    }

    private CustomEvent.Builder createCustomEventBuilder(String eventName, Visit visit, final int boundaryEvent) {
        HashMap<String, String> placeAttributesCopy = new HashMap<>();
        Attributes placeAttributes = visit.getPlace().getAttributes();

        CustomEvent.Builder builder = CustomEvent.newBuilder(eventName);
        if (placeAttributes != null) {
            for (String key : placeAttributes.getAllKeys()) {
                placeAttributesCopy.put(key, placeAttributes.getValue(key));
                builder.addProperty("GMBL_PA_" + key, placeAttributes.getValue(key));
            }
        }

        return builder
                .addProperty("placeAttributes", JsonValue.wrapOpt(placeAttributesCopy))
                .addProperty("visitID", visit.getVisitID())
                .addProperty("placeIdentifier", visit.getPlace().getIdentifier())
                .addProperty("placeName", visit.getPlace().getName())
                .addProperty("source", SOURCE)
                .addProperty("boundaryEvent", boundaryEvent);
    }

    /**
     * Adds an adapter listener.
     *
     * @param listener The listener.
     */
    public void addListener(@NonNull Listener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    /**
     * Removes an adapter listener.
     *
     * @param listener The listener.
     * @deprecated Will be removed in 5.0.0, use {@link #removeListener(Listener)} instead.
     */
    @Deprecated
    public void removeListner(@NonNull Listener listener) {
        removeListener(listener);
    }

    /**
     * Removes an adapter listener.
     *
     * @param listener The listener.
     */
    public void removeListener(@NonNull Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    /**
     * Restores the last run state. If previously started it will start listening, otherwise
     * it will stop listening. Should be called when the application starts up.
     */
    public void restore() {
        String gimbalApiKey = preferences.getString(API_KEY_PREFERENCE, null);
        boolean previouslyStarted = preferences.getBoolean(STARTED_PREFERENCE, false);
        if (gimbalApiKey != null && previouslyStarted) {
            Log.i(TAG, "Restoring Gimbal Adapter");
            startAdapter(gimbalApiKey);
            if (isStarted()) {
                Log.i(TAG, "Gimbal adapter restored");
            } else {
                Log.e(TAG, "Failed to restore Gimbal adapter. Make sure the API key is being set Application#onCreate");
            }
        }
    }

    /**
     * Starts the adapter.
     * <p>
     * b>Note:</b> The adapter will fail to listen for places if the application does not have proper
     * permissions. Use {@link #isPermissionGranted()} to check for permissions and {@link #startWithPermissionPrompt(String, PermissionResultCallback)}.
     * to prompt the user for permissions while starting the adapter.
     *
     * @param gimbalApiKey The Gimbal API key.
     * @return {@code true} if the adapter started, otherwise {@code false}.
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean start(@NonNull String gimbalApiKey) {
        startAdapter(gimbalApiKey);
        return isStarted();
    }

    /**
     * Prompts for permission for ACCESS_FINE_LOCATION before starting the adapter.
     * <p>
     * b>Note:</b> You should only call this from a foregrounded activity. This will prompt the user
     * for permissions even if the application is currently in the background.
     *
     * @param gimbalApiKey The Gimbal API key.
     */
    public void startWithPermissionPrompt(@NonNull final String gimbalApiKey) {
        startWithPermissionPrompt(gimbalApiKey, null);
    }

    /**
     * Prompts for permission for ACCESS_FINE_LOCATION before starting the adapter.
     * <p>
     * b>Note:</b> You should only call this from a foregrounded activity. This will prompt the user
     * for permissions even if the application is currently in the background.
     *
     * @param gimbalApiKey The Gimbal API key.
     * @param callback     Optional callback to get the result of the permission prompt.
     */
    public void startWithPermissionPrompt(@NonNull final String gimbalApiKey, @Nullable final PermissionResultCallback callback) {
        requestPermissionsTask = new RequestPermissionsTask(context.getApplicationContext(), enabled -> {
            if (enabled) {
                //noinspection MissingPermission
                startAdapter(gimbalApiKey);
            }

            if (callback != null) {
                callback.onResult(enabled);
            }
        });

        requestPermissionsTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, ACCESS_FINE_LOCATION);
    }


    private void startAdapter(@NonNull String gimbalApiKey) {
        if (isAdapterStarted) {
            return;
        }

        preferences.edit()
                .putString(API_KEY_PREFERENCE, gimbalApiKey)
                .putBoolean(STARTED_PREFERENCE, true)
                .apply();

        try {
            Gimbal.setApiKey((Application) context.getApplicationContext(), gimbalApiKey);
            Gimbal.start();
            PlaceManager.getInstance().addListener(placeEventListener);
            updateDeviceAttributes();
            Log.i(TAG, String.format("Gimbal Adapter started. Gimbal.isStarted: %b, Gimbal application instance identifier: %s", Gimbal.isStarted(), Gimbal.getApplicationInstanceIdentifier()));
            isAdapterStarted = true;
            UAirship.shared(airship -> {
                updateDeviceAttributes();
                airship.getChannel().addChannelListener(new AirshipChannelListener() {
                    @Override
                    public void onChannelCreated(@NonNull String channelId) {
                        updateDeviceAttributes();
                    }

                    @Override
                    public void onChannelUpdated(@NonNull String channelId) {
                    }
                });
            });
        } catch (Exception e) {
            Log.e(TAG,"Failed to start Gimbal.", e);
        }
    }

    /**
     * Stops the adapter.
     */
    public void stop() {
        if (!isStarted()) {
            Log.w(TAG, "stop() called when adapter was not started");
            return;
        }

        if (requestPermissionsTask != null) {
            requestPermissionsTask.cancel(true);
        }

        preferences.edit()
                .putBoolean(STARTED_PREFERENCE, false)
                .apply();

        try {
            Gimbal.stop();
            PlaceManager.getInstance().removeListener(placeEventListener);
        } catch (Exception e) {
            Log.w(TAG,"Caught exception stopping Gimbal. ", e);
            return;
        }

        isAdapterStarted = false;

        Log.i(TAG, "Adapter Stopped");
    }

    /**
     * Check if the adapter is started or not.
     */
    public boolean isStarted() {
        try {
            return isAdapterStarted && Gimbal.isStarted();
        } catch (Exception e) {
            Log.w(TAG,"Unable to check Gimbal.isStarted().", e);
            return false;
        }
    }

    /**
     * Checks if the application has been granted ACCESS_FINE_LOCATION for Gimbal.
     *
     * @return {@code true} if permissions have been granted, otherwise {@code false}.
     */
    public boolean isPermissionGranted() {
        return ContextCompat.checkSelfPermission(context, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Called when the airship channel is created.
     */
    public void onAirshipChannelCreated() {
        if (isStarted()) {
            updateDeviceAttributes();
        }
    }

    /**
     * Set whether the adapter should create a CustomEvent upon Gimbal Place entry.
     * */
    public void setShouldTrackCustomEntryEvent(Boolean shouldTrackCustomEntryEvent) {
        preferences.edit().putBoolean(TRACK_CUSTOM_ENTRY_PREFERENCE_KEY, shouldTrackCustomEntryEvent).apply();
    }

    /**
     * Set whether the adapter should create a CustomEvent upon Gimbal Place exit.
     * */
    public void setShouldTrackCustomExitEvent(Boolean shouldTrackCustomExitEvent) {
        preferences.edit().putBoolean(TRACK_CUSTOM_EXIT_PREFERENCE_KEY, shouldTrackCustomExitEvent).apply();
    }

    /**
     * Set whether the adapter should create a CustomEvent upon Gimbal Place exit.
     * */
    public void setShouldTrackRegionEvent(Boolean shouldTrackRegionEvent) {
        preferences.edit().putBoolean(TRACK_REGION_EVENT_PREFERENCE_KEY, shouldTrackRegionEvent).apply();
    }

    /**
     * Updates Gimbal and Urban Airship device attributes.
     */
    private void updateDeviceAttributes() {
        DeviceAttributesManager deviceAttributesManager = DeviceAttributesManager.getInstance();

        if (deviceAttributesManager == null) {
            return;
        }

        String namedUserId = UAirship.shared().getContact().getNamedUserId();
        deviceAttributesManager.setDeviceAttribute(GIMBAL_UA_NAMED_USER_ID, namedUserId);

        String channelId = UAirship.shared().getChannel().getId();
        deviceAttributesManager.setDeviceAttribute(GIMBAL_UA_CHANNEL_ID, channelId);

        String gimbalInstanceId = Gimbal.getApplicationInstanceIdentifier();
        if (gimbalInstanceId != null) {
            UAirship.shared().getAnalytics().editAssociatedIdentifiers().addIdentifier(UA_GIMBAL_APPLICATION_INSTANCE_ID, gimbalInstanceId).apply();
        }
    }

    private static class RequestPermissionsTask extends AsyncTask<String, Void, Boolean> {

        @SuppressLint("StaticFieldLeak")
        private final Context context;
        private final PermissionResultCallback callback;


        RequestPermissionsTask(Context context, PermissionResultCallback callback) {
            this.context = context;
            this.callback = callback;
        }

        @Override
        protected Boolean doInBackground(String... permissions) {
            int[] result = HelperActivity.requestPermissions(context, permissions);
            for (int element : result) {
                if (element == PackageManager.PERMISSION_GRANTED) {
                    return true;
                }
            }

            return false;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (callback != null) {
                callback.onResult(result);
            }
        }
    }
}

class CachedVisit {
    Visit visit;
    int regionEvent;

    CachedVisit(Visit visit, int event) {
        this.visit = visit;
        this.regionEvent = event;
    }
}