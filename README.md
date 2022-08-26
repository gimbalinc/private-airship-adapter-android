# Airship Gimbal Adapter

The Airship Gimbal Adapter is a drop-in class that allows users to integrate Gimbal Place events with
Urban Airship.

## Resources
- [Gimbal Developer Guide](https://gimbal.com/doc/android/v4/devguide.html)
- [Gimbal Manager Portal](https://manager.gimbal.com)
- [Airship Getting Started guide](https://docs.airship.com/platform/android/getting-started/)

## Installation

To install it add the following dependency to your application's build.gradle file:
```
   implementation 'com.gimbal.android.v4:airship-adapter:1.0.0'
```

## Start the adapter

To start the adapter call:
```
   AirshipAdapter.shared(context).start("## PLACE YOUR GIMBAL API KEY HERE ##");
```

Once the adapter is started, it will automatically resume its last state if
the application is restarted in the background. You only need to call start
once.

### Restoring the adapter

To make sure the adapter runs on subsequent app starts add this line to your Application class in 
the `onCreate()` method:
```
    AirshipAdapter.shared(context).restore();
```

### Android Marshmallow Permissions

Before the adapter is able to be started on Android M, it must request the location permission
``ACCESS_FINE_LOCATION``. The adapter has convenience methods that you can use to request permissions while
starting the adapter:
```
    AirshipAdapter.shared(context).startWithPermissionPrompt("## PLACE YOUR GIMBAL API KEY HERE ##");
```

Alternatively you can follow [requesting runtime permissions](https://developer.android.com/training/permissions/requesting.html)
to manually request the proper permissions. Then once the permissions are granted, call start on the adapter.

Note: You will need `ACCESS_BACKGROUND_LOCATION` permissions to use Gimbal's background features

## Stopping the adapter

Adapter can be stopped at anytime by calling:
```
   AirshipAdapter.shared(context).stop();
```
