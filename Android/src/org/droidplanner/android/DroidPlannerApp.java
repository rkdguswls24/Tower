package org.droidplanner.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.multidex.MultiDexApplication;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.o3dr.android.client.ControlTower;
import com.o3dr.android.client.Drone;
import com.o3dr.android.client.apis.VehicleApi;
import com.o3dr.android.client.interfaces.DroneListener;
import com.o3dr.android.client.interfaces.LinkListener;
import com.o3dr.android.client.interfaces.TowerListener;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.connection.ConnectionParameter;
import com.o3dr.services.android.lib.drone.connection.ConnectionType;
import com.o3dr.services.android.lib.gcs.link.LinkConnectionStatus;
import com.o3dr.services.android.lib.model.AbstractCommandListener;
import com.squareup.leakcanary.LeakCanary;

import org.droidplanner.android.activities.helpers.BluetoothDevicesActivity;
import org.droidplanner.android.proxy.mission.MissionProxy;
import org.droidplanner.android.utils.LogToFileTree;
import org.droidplanner.android.utils.Utils;
import org.droidplanner.android.utils.file.IO.ExceptionWriter;
import org.droidplanner.android.utils.prefs.DroidPlannerPrefs;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.fabric.sdk.android.Fabric;
import timber.log.Timber;

public class DroidPlannerApp extends MultiDexApplication implements DroneListener, TowerListener, LinkListener {

    private static final long DELAY_TO_DISCONNECTION = 1000L; // ms

    private static final String TAG = DroidPlannerApp.class.getSimpleName();

    public static final String ACTION_TOGGLE_DRONE_CONNECTION = Utils.PACKAGE_NAME
            + ".ACTION_TOGGLE_DRONE_CONNECTION";
    public static final String EXTRA_ESTABLISH_CONNECTION = "extra_establish_connection";

    private static final long EVENTS_DISPATCHING_PERIOD = 200L; //MS

    private static final AtomicBoolean isCellularNetworkOn = new AtomicBoolean(false);

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            switch (action) {
                case ACTION_TOGGLE_DRONE_CONNECTION:
                    boolean connect = intent.getBooleanExtra(EXTRA_ESTABLISH_CONNECTION,
                            !drone.isConnected());

                    if (connect)
                        connectToDrone();
                    else
                        disconnectFromDrone();
                    break;
            }
        }
    };

    @Override
    public void onTowerConnected() {
        Timber.d("Connecting to the control tower.");

        drone.unregisterDroneListener(this);

        controlTower.registerDrone(drone, handler);
        drone.registerDroneListener(this);

        notifyApiConnected();
    }

    @Override
    public void onTowerDisconnected() {
        Timber.d("Disconnection from the control tower.");
        notifyApiDisconnected();
    }

    @Override
    public void onLinkStateUpdated(@NonNull LinkConnectionStatus connectionStatus) {
        switch(connectionStatus.getStatusCode()){
            case LinkConnectionStatus.FAILED:
                Bundle extras = connectionStatus.getExtras();
                String errorMsg = null;
                if (extras != null) {
                    errorMsg = extras.getString(LinkConnectionStatus.EXTRA_ERROR_MSG);
                }

                Toast.makeText(getApplicationContext(), "Connection failed: " + errorMsg,
                    Toast.LENGTH_LONG).show();
                break;
        }
    }

    public interface ApiListener {
        void onApiConnected();

        void onApiDisconnected();
    }

    private final Runnable disconnectionTask = new Runnable() {
        @Override
        public void run() {
            Timber.d("Starting control tower disconnect process...");
            controlTower.unregisterDrone(drone);
            controlTower.disconnect();

            handler.removeCallbacks(this);
        }
    };

    private final Runnable eventsDispatcher = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);

            //Go through the events buffer and empty it
            for(Map.Entry<String, Bundle> entry: eventsBuffer.entrySet()){
                String event = entry.getKey();
                Bundle extras = entry.getValue();

                final Intent droneIntent = new Intent(event);
                if (extras != null)
                    droneIntent.putExtras(extras);
                lbm.sendBroadcast(droneIntent);
            }

            eventsBuffer.clear();

            handler.postDelayed(this, EVENTS_DISPATCHING_PERIOD);
        }
    };

    private final Map<String, Bundle> eventsBuffer = new LinkedHashMap<>(200);

    private final Handler handler = new Handler();
    private final List<ApiListener> apiListeners = new ArrayList<ApiListener>();

    private Thread.UncaughtExceptionHandler exceptionHandler;

    private ControlTower controlTower;
    private Drone drone;

    private MissionProxy missionProxy;
    private DroidPlannerPrefs dpPrefs;
    private LocalBroadcastManager lbm;

    private LogToFileTree logToFileTree;

    @Override
    public void onCreate() {
        super.onCreate();

        //Init leak canary
        LeakCanary.install(this);

        final Context context = getApplicationContext();

        final Thread.UncaughtExceptionHandler dpExceptionHandler = new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                new ExceptionWriter(ex).saveStackTraceToSD(context);
                exceptionHandler.uncaughtException(thread, ex);
            }
        };

        exceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(dpExceptionHandler);

        if (BuildConfig.WRITE_LOG_FILE) {
            logToFileTree = new LogToFileTree();
            Timber.plant(logToFileTree);
        } else if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }

        if(BuildConfig.ENABLE_CRASHLYTICS) {
            Fabric.with(context, new Crashlytics());
        }

        dpPrefs = DroidPlannerPrefs.getInstance(context);
        lbm = LocalBroadcastManager.getInstance(context);

        controlTower = new ControlTower(context);
        drone = new Drone(context);
        missionProxy = new MissionProxy(context, this.drone);

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_TOGGLE_DRONE_CONNECTION);

        registerReceiver(broadcastReceiver, intentFilter);
    }

    public void addApiListener(ApiListener listener) {
        if (listener == null)
            return;

        handler.removeCallbacks(disconnectionTask);
        boolean isTowerConnected = controlTower.isTowerConnected();
        if (isTowerConnected)
            listener.onApiConnected();

        if (!isTowerConnected) {
            try {
                controlTower.connect(this);
            } catch (IllegalStateException e) {
                //Ignore
            }
        }

        apiListeners.add(listener);
    }

    public void removeApiListener(ApiListener listener) {
        if (listener != null) {
            apiListeners.remove(listener);
            if (controlTower.isTowerConnected())
                listener.onApiDisconnected();
        }

        shouldWeTerminate();
    }

    private void shouldWeTerminate() {
        if (apiListeners.isEmpty() && !drone.isConnected()) {
            // Wait 30s, then disconnect the service binding.
            handler.postDelayed(disconnectionTask, DELAY_TO_DISCONNECTION);
        }
    }

    private void notifyApiConnected() {
        if (apiListeners.isEmpty())
            return;

        for (ApiListener listener : apiListeners)
            listener.onApiConnected();
    }

    private void notifyApiDisconnected() {
        if (apiListeners.isEmpty())
            return;

        for (ApiListener listener : apiListeners)
            listener.onApiDisconnected();
    }

    public void connectToDrone() {
        final ConnectionParameter connParams = retrieveConnectionParameters();
        if (connParams == null)
            return;

        boolean isDroneConnected = drone.isConnected();
        if (!connParams.equals(drone.getConnectionParameter()) && isDroneConnected) {
            Timber.d("Drone disconnection before reconnect attempt with different parameters.");
            drone.disconnect();
            isDroneConnected = false;
        }

        if (!isDroneConnected) {
            Timber.d("Connecting to drone using parameter %s", connParams);
            drone.connect(connParams, this);
        }
    }

    public static void connectToDrone(Context context) {
        context.sendBroadcast(new Intent(DroidPlannerApp.ACTION_TOGGLE_DRONE_CONNECTION)
                .putExtra(DroidPlannerApp.EXTRA_ESTABLISH_CONNECTION, true));
    }

    public static void disconnectFromDrone(Context context) {
        context.sendBroadcast(new Intent(DroidPlannerApp.ACTION_TOGGLE_DRONE_CONNECTION)
                .putExtra(DroidPlannerApp.EXTRA_ESTABLISH_CONNECTION, false));
    }

    public void disconnectFromDrone() {
        if (drone.isConnected()) {
            Timber.d("Disconnecting from drone.");
            drone.disconnect();
        }
    }

    public File getTLogsDirectory(){
        if(controlTower == null)
            return null;

        return controlTower.getTLogsDirectory();
    }

    public Drone getDrone() {
        return this.drone;
    }

    public MissionProxy getMissionProxy() {
        return this.missionProxy;
    }

    private ConnectionParameter retrieveConnectionParameters() {
        final int connectionType = dpPrefs.getConnectionParameterType();

        ConnectionParameter connParams;
        switch (connectionType) {
            case ConnectionType.TYPE_USB:
                connParams = ConnectionParameter.newUsbConnection(dpPrefs.getUsbBaudRate());
                break;

            case ConnectionType.TYPE_UDP:
                if (dpPrefs.isUdpPingEnabled()) {
                    connParams = ConnectionParameter.newUdpConnection(dpPrefs.getUdpServerPort(),
                        dpPrefs.getUdpPingReceiverIp(), dpPrefs.getUdpPingReceiverPort(), "Hello".getBytes());
                }
                else{
                    connParams = ConnectionParameter.newUdpConnection(dpPrefs.getUdpServerPort());
                }
                break;

            case ConnectionType.TYPE_TCP:
                connParams = ConnectionParameter.newTcpConnection(dpPrefs.getTcpServerIp(), dpPrefs.getTcpServerPort());
                break;

            case ConnectionType.TYPE_BLUETOOTH:
                String btAddress = dpPrefs.getBluetoothDeviceAddress();
                if (TextUtils.isEmpty(btAddress)) {
                    connParams = null;
                    startActivity(new Intent(getApplicationContext(),
                            BluetoothDevicesActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

                } else {
                    connParams = ConnectionParameter.newBluetoothConnection(btAddress);
                }
                break;

            default:
                Log.e(TAG, "Unrecognized connection type: " + connectionType);
                connParams = null;
                break;
        }

        return connParams;
    }

    @Override
    public void onDroneEvent(String event, Bundle extras) {
        switch (event) {
            case AttributeEvent.STATE_CONNECTED: {
                handler.removeCallbacks(disconnectionTask);
                startService(new Intent(getApplicationContext(), AppService.class));

                final boolean isReturnToMeOn = dpPrefs.isReturnToMeEnabled();
                VehicleApi.getApi(drone).enableReturnToMe(isReturnToMeOn, new AbstractCommandListener() {
                    @Override
                    public void onSuccess() {
                        Timber.i("Return to me %s successfully.", isReturnToMeOn ? "started" : "stopped");
                    }

                    @Override
                    public void onError(int i) {
                        Timber.e("%s return to me failed: %d", isReturnToMeOn ? "Starting" : "Stopping", i);
                    }

                    @Override
                    public void onTimeout() {
                        Timber.w("%s return to me timed out.", isReturnToMeOn ? "Starting" : "Stopping");
                    }
                });

                final Intent droneIntent = new Intent(event);
                if (extras != null)
                    droneIntent.putExtras(extras);
                lbm.sendBroadcast(droneIntent);

                handler.postDelayed(eventsDispatcher, EVENTS_DISPATCHING_PERIOD);
                break;
            }

            case AttributeEvent.STATE_DISCONNECTED: {
                handler.removeCallbacks(eventsDispatcher);

                shouldWeTerminate();

                final Intent droneIntent = new Intent(event);
                if (extras != null)
                    droneIntent.putExtras(extras);
                lbm.sendBroadcast(droneIntent);
                break;
            }

            default: {
                //Buffer the remaining events, and only fire them at 30hz
                //TODO: remove this once the buffer is placed on the 3DR Services side
                eventsBuffer.put(event, extras);
                break;
            }
        }
    }

    @Override
    public void onDroneServiceInterrupted(String errorMsg) {
        Timber.d("Drone service interrupted: %s", errorMsg);
        controlTower.unregisterDrone(drone);

        if (!TextUtils.isEmpty(errorMsg))
            Log.e(TAG, errorMsg);
    }

    public static void setCellularNetworkAvailability(boolean isAvailable){
        isCellularNetworkOn.set(isAvailable);
    }

    public static boolean isCellularNetworkAvailable(){
        return isCellularNetworkOn.get();
    }

    public void createFileStartLogging() {
        if (logToFileTree != null) {
            logToFileTree.createFileStartLogging(getApplicationContext());
        }
    }

    public void closeLogFile() {
        if(logToFileTree != null) {
            logToFileTree.stopLoggingThread();
        }
    }
}
