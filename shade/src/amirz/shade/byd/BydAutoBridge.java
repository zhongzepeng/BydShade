package amirz.shade.byd;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BydAutoBridge {
    private static final String TAG = "BydAutoBridge";

    private static final String CLASS_DEVICE_MANAGER = "android.hardware.bydauto.BYDAutoDeviceManager";
    private static final String CLASS_SPEED_DEVICE = "android.hardware.bydauto.speed.BYDAutoSpeedDevice";
    private static final String CLASS_ENERGY_DEVICE = "android.hardware.bydauto.energy.BYDAutoEnergyDevice";
    private static final String CLASS_MULTIMEDIA_DEVICE =
            "android.hardware.bydauto.multimedia.BYDAutoMultimediaDevice";

    private static BydAutoBridge sInstance;

    private final Context mContext;
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();

    private Object mDeviceManager;
    private Object mSpeedDevice;
    private Object mEnergyDevice;
    private Object mMultimediaDevice;
    private volatile Snapshot mSnapshot = Snapshot.empty();
    private boolean mStarted;

    public interface Listener {
        void onSnapshotChanged(@NonNull Snapshot snapshot);
    }

    public static synchronized BydAutoBridge get(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new BydAutoBridge(context.getApplicationContext());
        }
        return sInstance;
    }

    private BydAutoBridge(Context context) {
        mContext = context;
    }

    public synchronized void start() {
        if (mStarted) {
            return;
        }

        try {
            mDeviceManager = getInstance(CLASS_DEVICE_MANAGER);
            mSpeedDevice = getInstance(CLASS_SPEED_DEVICE);
            mEnergyDevice = getInstance(CLASS_ENERGY_DEVICE);
            mMultimediaDevice = getInstance(CLASS_MULTIMEDIA_DEVICE);

            enableManagedDevices();
            refreshSnapshot();
            mStarted = true;
        } catch (Throwable t) {
            Log.e(TAG, "Unable to initialize BYD OpenAPI bridge", t);
            clearDevices();
            publish(Snapshot.empty());
        }
    }

    public synchronized void stop() {
        if (!mStarted) {
            return;
        }

        disableManagedDevices();
        clearDevices();
        mStarted = false;
    }

    public synchronized void refreshSnapshot() {
        try {
            Snapshot updated = mSnapshot;
            boolean connected = false;

            if (mSpeedDevice != null) {
                connected = true;
                updated = updated
                        .withConnected(true)
                        .withSpeed(invokeDouble(mSpeedDevice, "getCurrentSpeed"))
                        .withAccelerateDeepness(invokeInt(mSpeedDevice, "getAccelerateDeepness"))
                        .withBrakeDeepness(invokeInt(mSpeedDevice, "getBrakeDeepness"));
            }
            if (mEnergyDevice != null) {
                connected = true;
                updated = updated
                        .withEnergyMode(invokeInt(mEnergyDevice, "getEnergyMode"))
                        .withOperationMode(invokeInt(mEnergyDevice, "getOperationMode"))
                        .withPowerGenerationState(invokeInt(mEnergyDevice, "getPowerGenerationState"))
                        .withPowerGenerationValue(invokeInt(mEnergyDevice, "getPowerGenerationValue"));
            }
            if (mMultimediaDevice != null) {
                connected = true;
                updated = updated
                        .withMediaType(invokeInt(mMultimediaDevice, "getMediaType"))
                        .withPlayMode(invokeInt(mMultimediaDevice, "getPlayMode"))
                        .withPlayState(invokeInt(mMultimediaDevice, "getPlayState"))
                        .withMediaInfo(invokeObject(mMultimediaDevice, "getPlayMediaInfo"));
            }

            publish(updated.withConnected(connected));
        } catch (Throwable t) {
            Log.e(TAG, "Unable to refresh BYD OpenAPI snapshot", t);
            publish(mSnapshot.withConnected(false));
        }
    }

    public void addListener(@NonNull Listener listener) {
        mListeners.add(listener);
        listener.onSnapshotChanged(mSnapshot);
    }

    public void removeListener(@NonNull Listener listener) {
        mListeners.remove(listener);
    }

    @NonNull
    public Snapshot getSnapshot() {
        return mSnapshot;
    }

    private void clearDevices() {
        mDeviceManager = null;
        mSpeedDevice = null;
        mEnergyDevice = null;
        mMultimediaDevice = null;
    }

    private void enableManagedDevices() {
        if (mDeviceManager == null) {
            return;
        }
        if (mSpeedDevice != null) {
            Log.d(TAG, "enable speed device result=" + invokeInt(mDeviceManager, "enableDevice", mSpeedDevice));
        }
        if (mEnergyDevice != null) {
            Log.d(TAG, "enable energy device result=" + invokeInt(mDeviceManager, "enableDevice", mEnergyDevice));
        }
    }

    private void disableManagedDevices() {
        if (mDeviceManager == null) {
            return;
        }
        if (mSpeedDevice != null) {
            invokeObject(mDeviceManager, "disableDevice", mSpeedDevice);
        }
        if (mEnergyDevice != null) {
            invokeObject(mDeviceManager, "disableDevice", mEnergyDevice);
        }
    }

    private void publish(@NonNull Snapshot snapshot) {
        mSnapshot = snapshot;
        for (Listener listener : mListeners) {
            listener.onSnapshotChanged(snapshot);
        }
    }

    private Object getInstance(String className) throws Exception {
        Class<?> clazz = Class.forName(className);
        Method method = clazz.getMethod("getInstance", Context.class);
        return method.invoke(null, mContext);
    }

    private static int invokeInt(@NonNull Object target, @NonNull String methodName, Object... args) {
        Object value = invokeObject(target, methodName, args);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static double invokeDouble(@NonNull Object target, @NonNull String methodName, Object... args) {
        Object value = invokeObject(target, methodName, args);
        return value instanceof Number ? ((Number) value).doubleValue() : 0d;
    }

    @Nullable
    private static Object invokeObject(@NonNull Object target, @NonNull String methodName, Object... args) {
        try {
            Method method = findMethod(target.getClass(), methodName, args);
            if (method == null) {
                throw new NoSuchMethodException(methodName);
            }
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to invoke " + target.getClass().getSimpleName() + "." + methodName, t);
            return null;
        }
    }

    @Nullable
    private static Method findMethod(@NonNull Class<?> clazz, @NonNull String methodName, Object... args) {
        Method[] methods = clazz.getMethods();
        for (Method method : methods) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != args.length) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                Object arg = args[i];
                if (arg == null) {
                    continue;
                }
                if (!parameterTypes[i].isAssignableFrom(arg.getClass())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method;
            }
        }
        return null;
    }

    public static final class Snapshot {
        public final boolean connected;
        public final double speedKph;
        public final int accelerateDeepness;
        public final int brakeDeepness;
        public final int energyMode;
        public final int operationMode;
        public final int powerGenerationState;
        public final int powerGenerationValue;
        public final int mediaType;
        public final int playMode;
        public final int playState;
        @Nullable
        public final String mediaFileName;
        @Nullable
        public final String mediaArtistName;
        @Nullable
        public final String mediaAlbumName;

        private Snapshot(
                boolean connected,
                double speedKph,
                int accelerateDeepness,
                int brakeDeepness,
                int energyMode,
                int operationMode,
                int powerGenerationState,
                int powerGenerationValue,
                int mediaType,
                int playMode,
                int playState,
                @Nullable String mediaFileName,
                @Nullable String mediaArtistName,
                @Nullable String mediaAlbumName) {
            this.connected = connected;
            this.speedKph = speedKph;
            this.accelerateDeepness = accelerateDeepness;
            this.brakeDeepness = brakeDeepness;
            this.energyMode = energyMode;
            this.operationMode = operationMode;
            this.powerGenerationState = powerGenerationState;
            this.powerGenerationValue = powerGenerationValue;
            this.mediaType = mediaType;
            this.playMode = playMode;
            this.playState = playState;
            this.mediaFileName = mediaFileName;
            this.mediaArtistName = mediaArtistName;
            this.mediaAlbumName = mediaAlbumName;
        }

        public static Snapshot empty() {
            return new Snapshot(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    null, null, null);
        }

        public Snapshot withConnected(boolean connected) {
            return new Snapshot(connected, speedKph, accelerateDeepness, brakeDeepness, energyMode,
                    operationMode, powerGenerationState, powerGenerationValue, mediaType, playMode,
                    playState, mediaFileName, mediaArtistName, mediaAlbumName);
        }

        public Snapshot withSpeed(double speedKph) {
            return new Snapshot(connected, speedKph, accelerateDeepness, brakeDeepness, energyMode,
                    operationMode, powerGenerationState, powerGenerationValue, mediaType, playMode,
                    playState, mediaFileName, mediaArtistName, mediaAlbumName);
        }

        public Snapshot withAccelerateDeepness(int deepness) {
            return new Snapshot(connected, speedKph, deepness, brakeDeepness, energyMode,
                    operationMode, powerGenerationState, powerGenerationValue, mediaType, playMode,
                    playState, mediaFileName, mediaArtistName, mediaAlbumName);
        }

        public Snapshot withBrakeDeepness(int deepness) {
            return new Snapshot(connected, speedKph, accelerateDeepness, deepness, energyMode,
                    operationMode, powerGenerationState, powerGenerationValue, mediaType, playMode,
                    playState, mediaFileName, mediaArtistName, mediaAlbumName);
        }

        public Snapshot withEnergyMode(int energyMode) {
            return new Snapshot(connected, speedKph, accelerateDeepness, brakeDeepness, energyMode,
                    operationMode, powerGenerationState, powerGenerationValue, mediaType, playMode,
                    playState, mediaFileName, mediaArtistName, mediaAlbumName);
        }

        public Snapshot withOperationMode(int operationMode) {
            return new Snapshot(connected, speedKph, accelerateDeepness, brakeDeepness, energyMode,
                    operationMode, powerGenerationState, powerGenerationValue, mediaType, playMode,
                    playState, mediaFileName, mediaArtistName, mediaAlbumName);
        }

        public Snapshot withPowerGenerationState(int state) {
            return new Snapshot(connected, speedKph, accelerateDeepness, brakeDeepness, energyMode,
                    operationMode, state, powerGenerationValue, mediaType, playMode,
                    playState, mediaFileName, mediaArtistName, mediaAlbumName);
        }

        public Snapshot withPowerGenerationValue(int value) {
            return new Snapshot(connected, speedKph, accelerateDeepness, brakeDeepness, energyMode,
                    operationMode, powerGenerationState, value, mediaType, playMode,
                    playState, mediaFileName, mediaArtistName, mediaAlbumName);
        }

        public Snapshot withMediaType(int mediaType) {
            return new Snapshot(connected, speedKph, accelerateDeepness, brakeDeepness, energyMode,
                    operationMode, powerGenerationState, powerGenerationValue, mediaType, playMode,
                    playState, mediaFileName, mediaArtistName, mediaAlbumName);
        }

        public Snapshot withPlayMode(int playMode) {
            return new Snapshot(connected, speedKph, accelerateDeepness, brakeDeepness, energyMode,
                    operationMode, powerGenerationState, powerGenerationValue, mediaType, playMode,
                    playState, mediaFileName, mediaArtistName, mediaAlbumName);
        }

        public Snapshot withPlayState(int playState) {
            return new Snapshot(connected, speedKph, accelerateDeepness, brakeDeepness, energyMode,
                    operationMode, powerGenerationState, powerGenerationValue, mediaType, playMode,
                    playState, mediaFileName, mediaArtistName, mediaAlbumName);
        }

        public Snapshot withMediaInfo(@Nullable Object info) {
            return new Snapshot(connected, speedKph, accelerateDeepness, brakeDeepness, energyMode,
                    operationMode, powerGenerationState, powerGenerationValue, mediaType, playMode,
                    playState,
                    readStringField(info, "fileName"),
                    readStringField(info, "artistName"),
                    readStringField(info, "albumName"));
        }

        @Nullable
        private static String readStringField(@Nullable Object target, @NonNull String fieldName) {
            if (target == null) {
                return null;
            }
            try {
                Field field = target.getClass().getField(fieldName);
                Object value = field.get(target);
                return emptyToNull(value instanceof String ? (String) value : null);
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Nullable
        private static String emptyToNull(@Nullable String value) {
            return TextUtils.isEmpty(value) ? null : value;
        }
    }
}
