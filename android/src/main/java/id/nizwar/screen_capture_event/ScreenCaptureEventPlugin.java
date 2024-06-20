package id.nizwar.screen_capture_event;

import android.Manifest;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import android.util.Log;

/**
 * ScreenCaptureEventPlugin
 */
public class ScreenCaptureEventPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
    static int SCREEN_CAPTURE_PERMISSION = 101;
    private MethodChannel channel;
    private ContentObserver contentObserver;
    private Timer timeout = new Timer();
    private final Map<String, ContentObserver> watchModifier = new HashMap<>();
    private ActivityPluginBinding activityPluginBinding;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean screenRecording = false;
    private long tempSize = 0;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "screencapture_method");
        channel.setMethodCallHandler(this);
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "isRecording":
                result.success(screenRecording);
                break;
            case "request_permission":
                if (ContextCompat.checkSelfPermission(activityPluginBinding.getActivity(), Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(activityPluginBinding.getActivity(), new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, SCREEN_CAPTURE_PERMISSION);
                }
                break;
            case "watch":
                updateScreenRecordStatus();
                ContentResolver contentResolver = activityPluginBinding.getActivity().getContentResolver();
                Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                contentObserver = new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange, @Nullable Uri uri) {
                        handleNewFileCreated(uri);
                    }
                };
                contentResolver.registerContentObserver(uri, true, contentObserver);
                break;
            case "dispose":
                if (contentObserver != null) {
                    activityPluginBinding.getActivity().getContentResolver().unregisterContentObserver(contentObserver);
                }
                for (Map.Entry<String, ContentObserver> stringObjectEntry : watchModifier.entrySet()) {
                    activityPluginBinding.getActivity().getContentResolver().unregisterContentObserver(stringObjectEntry.getValue());
                }
                watchModifier.clear();
                break;
            default:
        }
    }

    private void stopAllRecordWatcher() {
        for (Map.Entry<String, ContentObserver> stringObjectEntry : watchModifier.entrySet()) {
            activityPluginBinding.getActivity().getContentResolver().unregisterContentObserver(stringObjectEntry.getValue());
        }
        watchModifier.clear();
        setScreenRecordStatus(false);
    }

    private void updateScreenRecordStatus() {
        List<String> paths = new ArrayList<>();
        for (Path path : Path.values()) {
            paths.add(path.getPath());
        }
        for (int i = 0; i < paths.size(); i++) {
            String fullPath = paths.get(i);
            File newFile = getLastModified(fullPath);
            if (newFile != null) {
                String mime = getMimeType(newFile.getPath());
                if (mime != null) {
                    if (mime.contains("video") && !watchModifier.containsKey(newFile.getPath())) {
                        ContentObserver contentObserver = new ContentObserver(handler) {
                            @Override
                            public void onChange(boolean selfChange, @Nullable Uri uri) {
                                handleUpdateScreenRecordEvent(newFile);
                            }
                        };
                        watchModifier.put(newFile.getPath(), contentObserver);
                        activityPluginBinding.getActivity().getContentResolver().registerContentObserver(Uri.fromFile(newFile), true, contentObserver);
                    }
                }
            }
        }
    }
    private void handleUpdateScreenRecordEvent(File newFile) {
        long curSize = newFile.length();
        if (curSize > tempSize) {
            if (timeout != null) {
                try {
                    timeout.cancel();
                    timeout = null;
                } catch (Exception ignored) {
                }
            }
            setScreenRecordStatus(true);
            tempSize = newFile.length();
        }
        if (timeout == null) {
            timeout = new Timer();
            timeout.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (watchModifier.containsKey(newFile.getPath())) {
                        setScreenRecordStatus(curSize != tempSize);
                    }
                }
            }, 1600);
        }
    }
    private void handleNewFileCreated(@Nullable Uri uri) {
        if (uri != null) {
            String path = getPathFromUri(uri);
            if (path != null) {
                File file = new File(path);
                if (file.exists()) {
                    String mime = getMimeType(file.getPath());
                    if (mime != null) {
                        if (mime.contains("video")) {
                            stopAllRecordWatcher();
                            setScreenRecordStatus(true);
                            updateScreenRecordStatus();
                        } else if (mime.contains("image")) {
                            handler.post(() -> {
                                channel.invokeMethod("screenshot", file.getPath());
                            });
                        }
                    }
                }
            }
        }
    }

    void setScreenRecordStatus(boolean value) {
        if (screenRecording != value) {
            handler.post(() -> {
                screenRecording = value;
                channel.invokeMethod("screenrecord", value);
            });
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {}

    public static String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return type;
    }

    public static File getLastModified(String directoryFilePath) {
        File directory = new File(directoryFilePath);
        if (directory.listFiles() == null) return null;
        File[] files = directory.listFiles(File::isFile);
        long lastModifiedTime = Long.MIN_VALUE;
        File chosenFile = null;
        if (files != null) {
            for (File file : files) {
                if (file.lastModified() > lastModifiedTime) {
                    chosenFile = file;
                    lastModifiedTime = file.lastModified();
                }
            }
        }
        return chosenFile;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activityPluginBinding = binding;
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {}

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activityPluginBinding = binding;
    }

    @Override
    public void onDetachedFromActivity() {}

    public enum Path {
        DCIM(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + File.separator + "Screenshots" + File.separator),
        DCIMSAMSUNG(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + File.separator + "Screen recordings" + File.separator),
        PICTURES(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) + File.separator + "Screenshots" + File.separator);

        final private String path;
        
        public String getPath() {
            return path;
        }

        Path(String path) {
            this.path = path;
        }
    }

    private String getPathFromUri(Uri uri) {
        String[] projection = {MediaStore.Images.Media.DATA};
        try (Cursor cursor = activityPluginBinding.getActivity().getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                return cursor.getString(columnIndex);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}