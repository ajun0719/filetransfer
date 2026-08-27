package com.ajun.filetransfer;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.Glide;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.w3c.dom.Document;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSION = 100;
    private static final int REQUEST_CODE_SEND_FILE = 1001;
    private static final String TAG = "MainActivity";
    private FileServer server;
    private TextView tvStatus, tvUrl, tvProgressFile, tvProgressPercent, tvSavePath, tvOnlineCount;
    private ProgressBar pbUpload;
    private ImageView ivQr;
    private Button btnStart, btnStop, btnCopy, btnSetPath, btnHistory, btnAcceptAll, btnSend,btnRejectAll,btnOpenFolder;
    private ListView lvPending, lvUsers;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private OkHttpClient httpClient = new OkHttpClient();

    private File sharedDir;
    private List<PendingFileItem> pendingList = new ArrayList<>();
    private PendingAdapter pendingAdapter;
    private List<String> userList = new ArrayList<>();
    private ArrayAdapter<String> userAdapter;

    private Runnable pendingRunnable, userPollRunnable, notifyRunnable;
    private Handler pollHandler = new Handler(Looper.getMainLooper());
    private Handler userHandler = new Handler(Looper.getMainLooper());
    private String selectedTarget;
    private Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable;

    private ListView lvOutgoing;
    private OutgoingAdapter outgoingAdapter;
    private List<OutgoingFile> outgoingList = new ArrayList<>();
    private Runnable outgoingRunnable;
    private Handler outgoingHandler = new Handler(Looper.getMainLooper());

    // ==================== 数据模型 ====================
    static class PendingFileItem {
        String id; // upload 专用
        String fileName;
        long fileSize;
        String tempPath; // upload 专用
        String type; // "upload" 或 "push"
        String downloadUrl; // push 专用
    }

    // ==================== 适配器 ====================
    private class PendingAdapter extends ArrayAdapter<PendingFileItem> {
        public PendingAdapter(Context context, List<PendingFileItem> data) {
            super(context, 0, data);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView =
                        LayoutInflater.from(getContext())
                                .inflate(R.layout.item_pending, parent, false);
            }
            PendingFileItem item = getItem(position);
            ImageView ivThumb = convertView.findViewById(R.id.iv_thumb);
            TextView tvName = convertView.findViewById(R.id.tv_file_name);
            TextView tvSize = convertView.findViewById(R.id.tv_file_size);

            if (item != null) {
                tvName.setText(item.fileName);
                tvSize.setText((item.fileSize / 1024) + " KB");

                boolean isImage = item.fileName.matches("(?i).*\\.(jpg|jpeg|png|gif|bmp|webp)");
                if (isImage) {
                    try {
                        String encodedName = URLEncoder.encode(item.fileName, "UTF-8");
                        String thumbUrl =
                                "http://"
                                        + getLocalIpAddress()
                                        + ":8080/thumbnail?file="
                                        + encodedName;
                        Glide.with(getContext())
                                .load(thumbUrl)
                                .placeholder(android.R.drawable.ic_menu_gallery)
                                .error(android.R.drawable.ic_menu_gallery)
                                .into(ivThumb);
                    } catch (Exception e) {
                        ivThumb.setImageResource(android.R.drawable.ic_menu_gallery);
                    }
                } else {
                    ivThumb.setImageResource(android.R.drawable.ic_menu_gallery);
                }
            }
            return convertView;
        }
    }

    // ==================== 广播接收器 ====================
    private BroadcastReceiver progressReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (FileServer.ACTION_PROGRESS.equals(action)) {
                        String fileName = intent.getStringExtra("fileName");
                        int progress = intent.getIntExtra("progress", 0);
                        String savePath = intent.getStringExtra("savePath");

                        findViewById(R.id.progress_layout).setVisibility(View.VISIBLE);
                        tvProgressFile.setText("正在接收：" + fileName);
                        pbUpload.setProgress(progress);
                        tvProgressPercent.setText(progress + "%");

                        if (progress >= 100) {
                            mainHandler.postDelayed(
                                    () -> {
                                        findViewById(R.id.progress_layout).setVisibility(View.GONE);
                                    },
                                    3000);
                            if (savePath != null) {
                                Toast.makeText(
                                                MainActivity.this,
                                                "文件已保存到：" + savePath,
                                                Toast.LENGTH_LONG)
                                        .show();
                            } else {
                                Toast.makeText(MainActivity.this, "文件接收完成！", Toast.LENGTH_SHORT)
                                        .show();
                            }
                            refreshPendingList();
                        }
                    }
                }
            };

    // ==================== 生命周期 ====================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        updateSharedDir();
        checkAndRequestPermissions();
        initAdapters();
        initListeners();

        LocalBroadcastManager.getInstance(this)
                .registerReceiver(progressReceiver, new IntentFilter(FileServer.ACTION_PROGRESS));
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvUrl = findViewById(R.id.tv_url);
        tvProgressFile = findViewById(R.id.tv_progress_file);
        tvProgressPercent = findViewById(R.id.tv_progress_percent);
        pbUpload = findViewById(R.id.pb_upload);
        ivQr = findViewById(R.id.iv_qr);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnCopy = findViewById(R.id.btn_copy);
        btnSetPath = findViewById(R.id.btn_set_path);
        btnHistory = findViewById(R.id.btn_history);
        btnAcceptAll = findViewById(R.id.btn_accept_all);
        tvSavePath = findViewById(R.id.tv_save_path);
        tvOnlineCount = findViewById(R.id.tv_online_count);
        lvPending = findViewById(R.id.lv_pending);
        lvUsers = findViewById(R.id.lv_users);
        btnSend = findViewById(R.id.btn_send);
        lvOutgoing = findViewById(R.id.lv_outgoing);
        outgoingAdapter = new OutgoingAdapter(this, outgoingList);
        lvOutgoing.setAdapter(outgoingAdapter);
        btnRejectAll = findViewById(R.id.btn_reject_all);
        btnOpenFolder = findViewById(R.id.btn_open_folder);
    }

    private void initAdapters() {
        pendingAdapter = new PendingAdapter(this, pendingList);
        lvPending.setAdapter(pendingAdapter);
        userAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, userList);
        lvUsers.setAdapter(userAdapter);
    }

    private void initListeners() {
        btnOpenFolder.setOnClickListener(v -> {
            if (sharedDir == null || !sharedDir.exists()) {
                Toast.makeText(this, "保存目录不存在", Toast.LENGTH_SHORT).show();
                return;
            }
        
            // Toast 提示路径
            Toast.makeText(this, "保存目录：" + sharedDir.getAbsolutePath(), Toast.LENGTH_LONG).show();
        
            try {
                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider",
                        sharedDir);
                // 使用 */* 作为 MIME 类型，让更多应用（包括系统文件管理器）出现在选择器中
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "*/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                // 如果希望同时允许写入，可添加 WRITE 权限
                // intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, "选择应用打开目录"));
            } catch (Exception e) {
                // 如果 FileProvider 失败，降级为 SAF
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivity(intent);
                } catch (Exception ex) {
                    Toast.makeText(this, "无法打开，请手动前往：" + sharedDir.getAbsolutePath(), Toast.LENGTH_LONG).show();
                }
            }
        });
        btnSend.setOnClickListener(v -> startSendFilesFlow());
        lvPending.setOnItemClickListener(
                (parent, view, position, id) -> {
                    if (position < pendingList.size()) {
                        PendingFileItem pf = pendingList.get(position);
                        if ("upload".equals(pf.type)) {
                            showUploadConfirmDialog(pf);
                        } else if ("push".equals(pf.type)) {
                            showPushConfirmDialog(pf);
                        }
                    }
                });

        lvUsers.setOnItemClickListener(
                (parent, view, position, id) -> {
                    if (position < userList.size()) {
                        selectedTarget = userList.get(position);
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(
                                Intent.createChooser(intent, "选择要发送的文件"), REQUEST_CODE_SEND_FILE);
                    }
                });

        lvUsers.setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    if (position < userList.size()) {
                        final String targetUser = userList.get(position);
                        fetchPendingPushes(
                                targetUser,
                                pushes -> {
                                    if (pushes == null || pushes.isEmpty()) {
                                        mainHandler.post(
                                                () ->
                                                        Toast.makeText(
                                                                        MainActivity.this,
                                                                        "该用户无待推送文件",
                                                                        Toast.LENGTH_SHORT)
                                                                .show());
                                        return;
                                    }
                                    AlertDialog.Builder builder =
                                            new AlertDialog.Builder(MainActivity.this);
                                    builder.setTitle("待推送文件给 " + targetUser);
                                    String[] items = new String[pushes.size()];
                                    for (int i = 0; i < pushes.size(); i++) {
                                        items[i] =
                                                pushes.get(i).fileName
                                                        + " ("
                                                        + (pushes.get(i).fileSize / 1024)
                                                        + " KB)";
                                    }
                                    builder.setItems(
                                            items,
                                            (dialog, which) -> {
                                                String fileName = pushes.get(which).fileName;
                                                cancelPush(targetUser, fileName);
                                            });
                                    builder.setPositiveButton(
                                            "取消全部",
                                            (dialog, which) -> cancelPush(targetUser, null));
                                    builder.setNegativeButton("关闭", null);
                                    builder.show();
                                });
                        return true;
                    }
                    return false;
                });

        btnStart.setOnClickListener(v -> startServer());
        btnStop.setOnClickListener(v -> stopServer());
        btnCopy.setOnClickListener(v -> copyUrlToClipboard());
        btnSetPath.setOnClickListener(v -> showSetPathDialog());
        btnHistory.setOnClickListener(
                v -> {
                    Intent intent = new Intent(MainActivity.this, HistoryActivity.class);
                    startActivity(intent);
                });
        btnAcceptAll.setOnClickListener(
                v -> {
                    if (pendingList.isEmpty()) {
                        Toast.makeText(this, "没有待接收文件", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    for (PendingFileItem pf : pendingList) {
                        if ("upload".equals(pf.type) && pf.id != null) {
                            confirmUploadReceive(pf.id);
                        } else if ("push".equals(pf.type)) {
                            confirmPushReceive(pf);
                        }
                    }
                    Toast.makeText(this, "已全部接受", Toast.LENGTH_SHORT).show();
                });
                
         btnRejectAll.setOnClickListener(v -> {
            if (pendingList.isEmpty()) {
                Toast.makeText(this, "没有待接收文件", Toast.LENGTH_SHORT).show();
                return;
            }
            for (PendingFileItem pf : pendingList) {
                if ("upload".equals(pf.type) && pf.id != null) {
                    rejectUploadReceive(pf.id);
                } else if ("push".equals(pf.type)) {
                    rejectPushReceive(pf);
                }
            }
            Toast.makeText(this, "已全部拒收", Toast.LENGTH_SHORT).show();
        });   
    }

    static class OutgoingFile {
        String fileName;
        long fileSize;
        String targetUser;
    }

    private class OutgoingAdapter extends ArrayAdapter<OutgoingFile> {
        public OutgoingAdapter(Context context, List<OutgoingFile> data) {
            super(context, 0, data);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView =
                        LayoutInflater.from(getContext())
                                .inflate(R.layout.item_outgoing, parent, false);
            }
            OutgoingFile item = getItem(position);
            TextView tvName = convertView.findViewById(R.id.tv_outgoing_name);
            TextView tvTarget = convertView.findViewById(R.id.tv_outgoing_target);
            TextView tvSize = convertView.findViewById(R.id.tv_outgoing_size);
            Button btnCancel = convertView.findViewById(R.id.btn_outgoing_cancel);

            tvName.setText(item.fileName);
            tvTarget.setText("目标: " + item.targetUser);
            tvSize.setText((item.fileSize / 1024) + " KB");

            btnCancel.setOnClickListener(
                    v -> {
                        cancelPush(item.targetUser, item.fileName);
                        // 刷新列表
                        fetchOutgoingList();
                    });

            return convertView;
        }
    }

    private void fetchOutgoingList() {
        if (server == null) return;
        String url = "http://" + getLocalIpAddress() + ":8080/outgoing_pushes?user=手机";
        Request request = new Request.Builder().url(url).build();
        httpClient
                .newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(Call call, IOException e) {
                                Log.e(TAG, "获取已发送待接收列表失败", e);
                            }

                            @Override
                            public void onResponse(Call call, Response response)
                                    throws IOException {
                                if (response.isSuccessful()) {
                                    String json = response.body().string();
                                    try {
                                        JSONArray array = new JSONArray(json);
                                        List<OutgoingFile> newList = new ArrayList<>();
                                        for (int i = 0; i < array.length(); i++) {
                                            JSONObject obj = array.getJSONObject(i);
                                            OutgoingFile of = new OutgoingFile();
                                            of.fileName = obj.getString("fileName");
                                            of.fileSize = obj.getLong("fileSize");
                                            of.targetUser = obj.optString("targetUser", "未知");
                                            newList.add(of);
                                        }
                                        mainHandler.post(
                                                () -> {
                                                    outgoingList.clear();
                                                    outgoingList.addAll(newList);
                                                    outgoingAdapter.notifyDataSetChanged();
                                                    findViewById(R.id.outgoingCard)
                                                            .setVisibility(
                                                                    outgoingList.isEmpty()
                                                                            ? View.GONE
                                                                            : View.VISIBLE);
                                                });
                                    } catch (JSONException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        });
    }

    private void startOutgoingPolling() {
        if (outgoingRunnable != null) return;
        outgoingRunnable =
                new Runnable() {
                    @Override
                    public void run() {
                        fetchOutgoingList();
                        outgoingHandler.postDelayed(this, 5000);
                    }
                };
        outgoingHandler.post(outgoingRunnable);
    }

    private void stopOutgoingPolling() {
        if (outgoingRunnable != null) {
            outgoingHandler.removeCallbacks(outgoingRunnable);
            outgoingRunnable = null;
        }
    }

    // ==================== 对话框（upload类型） ====================
    private void showUploadConfirmDialog(PendingFileItem pf) {
        new AlertDialog.Builder(this)
                .setTitle("确认接收")
                .setMessage("是否接收文件：" + pf.fileName + " (" + (pf.fileSize / 1024) + " KB) ?")
                .setPositiveButton("接收", (dialog, which) -> confirmUploadReceive(pf.id))
                .setNegativeButton("拒绝", (dialog, which) -> rejectUploadReceive(pf.id))
                .show();
    }

    // ==================== 对话框（push类型） ====================
    private void showPushConfirmDialog(PendingFileItem pf) {
        new AlertDialog.Builder(this)
                .setTitle("接收推送文件")
                .setMessage("是否接收推送文件：" + pf.fileName + " (" + (pf.fileSize / 1024) + " KB) ?")
                .setPositiveButton("接收", (dialog, which) -> confirmPushReceive(pf))
                .setNegativeButton("拒绝", (dialog, which) -> rejectPushReceive(pf))
                .show();
    }

    // ==================== 操作：upload接收 ====================
    private void confirmUploadReceive(String id) {
        String url = "http://" + getLocalIpAddress() + ":8080/confirm?id=" + id;
        Request request =
                new Request.Builder().url(url).post(RequestBody.create(null, new byte[0])).build();
        httpClient
                .newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(Call call, IOException e) {
                                mainHandler.post(
                                        () ->
                                                Toast.makeText(
                                                                MainActivity.this,
                                                                "确认失败",
                                                                Toast.LENGTH_SHORT)
                                                        .show());
                            }

                            @Override
                            public void onResponse(Call call, Response response)
                                    throws IOException {
                                if (response.isSuccessful()) {
                                    mainHandler.post(
                                            () -> {
                                                Toast.makeText(
                                                                MainActivity.this,
                                                                "已接收文件",
                                                                Toast.LENGTH_SHORT)
                                                        .show();
                                                refreshPendingList();
                                            });
                                } else {
                                    mainHandler.post(
                                            () ->
                                                    Toast.makeText(
                                                                    MainActivity.this,
                                                                    "确认失败",
                                                                    Toast.LENGTH_SHORT)
                                                            .show());
                                }
                            }
                        });
    }

    private void rejectUploadReceive(String id) {
        String url = "http://" + getLocalIpAddress() + ":8080/reject?id=" + id;
        Request request =
                new Request.Builder().url(url).post(RequestBody.create(null, new byte[0])).build();
        httpClient
                .newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(Call call, IOException e) {
                                mainHandler.post(
                                        () ->
                                                Toast.makeText(
                                                                MainActivity.this,
                                                                "拒绝失败",
                                                                Toast.LENGTH_SHORT)
                                                        .show());
                            }

                            @Override
                            public void onResponse(Call call, Response response)
                                    throws IOException {
                                if (response.isSuccessful()) {
                                    mainHandler.post(
                                            () -> {
                                                Toast.makeText(
                                                                MainActivity.this,
                                                                "已拒绝",
                                                                Toast.LENGTH_SHORT)
                                                        .show();
                                                refreshPendingList();
                                            });
                                }
                            }
                        });
    }

    // ==================== 操作：push接收 ====================
    private void confirmPushReceive(PendingFileItem pf) {
        String url =
                "http://"
                        + getLocalIpAddress()
                        + ":8080/confirm_receive?user=手机&fileName="
                        + pf.fileName;
        Request request =
                new Request.Builder().url(url).post(RequestBody.create(null, new byte[0])).build();
        httpClient
                .newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(Call call, IOException e) {
                                mainHandler.post(
                                        () ->
                                                Toast.makeText(
                                                                MainActivity.this,
                                                                "确认失败",
                                                                Toast.LENGTH_SHORT)
                                                        .show());
                            }

                            @Override
                            public void onResponse(Call call, Response response)
                                    throws IOException {
                                if (response.isSuccessful()) {
                                    mainHandler.post(
                                            () -> {
                                                Toast.makeText(
                                                                MainActivity.this,
                                                                "已接收推送文件",
                                                                Toast.LENGTH_SHORT)
                                                        .show();
                                                refreshPendingList();
                                            });
                                } else {
                                    mainHandler.post(
                                            () ->
                                                    Toast.makeText(
                                                                    MainActivity.this,
                                                                    "确认失败",
                                                                    Toast.LENGTH_SHORT)
                                                            .show());
                                }
                            }
                        });
    }

    private void rejectPushReceive(PendingFileItem pf) {
        String url =
                "http://"
                        + getLocalIpAddress()
                        + ":8080/reject_receive?user=手机&fileName="
                        + pf.fileName;
        Request request =
                new Request.Builder().url(url).post(RequestBody.create(null, new byte[0])).build();
        httpClient
                .newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(Call call, IOException e) {
                                mainHandler.post(
                                        () ->
                                                Toast.makeText(
                                                                MainActivity.this,
                                                                "拒绝失败",
                                                                Toast.LENGTH_SHORT)
                                                        .show());
                            }

                            @Override
                            public void onResponse(Call call, Response response)
                                    throws IOException {
                                if (response.isSuccessful()) {
                                    mainHandler.post(
                                            () -> {
                                                Toast.makeText(
                                                                MainActivity.this,
                                                                "已拒绝推送文件",
                                                                Toast.LENGTH_SHORT)
                                                        .show();
                                                refreshPendingList();
                                            });
                                }
                            }
                        });
    }

    // ==================== 统一刷新 ====================
    private void refreshPendingList() {
        if (server == null) return;
        // 先获取 upload 列表
        fetchPendingUploads();
    }

    private void fetchPendingUploads() {
        String url = "http://" + getLocalIpAddress() + ":8080/pending";
        Request request = new Request.Builder().url(url).build();
        httpClient
                .newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(Call call, IOException e) {
                                Log.e(TAG, "获取待接收列表失败", e);
                            }

                            @Override
                            public void onResponse(Call call, Response response)
                                    throws IOException {
                                if (response.isSuccessful()) {
                                    String json = response.body().string();
                                    try {
                                        JSONArray array = new JSONArray(json);
                                        List<PendingFileItem> newList = new ArrayList<>();
                                        for (int i = 0; i < array.length(); i++) {
                                            JSONObject obj = array.getJSONObject(i);
                                            PendingFileItem pf = new PendingFileItem();
                                            pf.id = obj.getString("id");
                                            pf.fileName = obj.getString("fileName");
                                            pf.fileSize = obj.getLong("fileSize");
                                            pf.tempPath = obj.getString("tempPath");
                                            pf.type = "upload";
                                            newList.add(pf);
                                        }
                                        // 获取 push 列表，合并
                                        fetchPendingPushesForMerge(newList);
                                    } catch (JSONException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        });
    }

    private void fetchPendingPushesForMerge(List<PendingFileItem> uploadList) {
        String url = "http://" + getLocalIpAddress() + ":8080/notify?user=手机";
        Request request = new Request.Builder().url(url).build();
        httpClient
                .newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(Call call, IOException e) {
                                Log.e(TAG, "获取推送列表失败", e);
                                // 即使失败也显示 upload 列表
                                mainHandler.post(
                                        () -> {
                                            pendingList.clear();
                                            pendingList.addAll(uploadList);
                                            pendingAdapter.notifyDataSetChanged();
                                            // 控制待接收文件区域显示隐藏
                                            View pendingLayout = findViewById(R.id.pending_layout);
                                            if (pendingList.isEmpty()) {
                                                pendingLayout.setVisibility(View.GONE);
                                            } else {
                                                pendingLayout.setVisibility(View.VISIBLE);
                                            }
                                        });
                            }

                            @Override
                            public void onResponse(Call call, Response response)
                                    throws IOException {
                                if (response.isSuccessful()) {
                                    String json = response.body().string();
                                    try {
                                        JSONArray array = new JSONArray(json);
                                        for (int i = 0; i < array.length(); i++) {
                                            JSONObject obj = array.getJSONObject(i);
                                            PendingFileItem pf = new PendingFileItem();
                                            pf.fileName = obj.getString("fileName");
                                            pf.fileSize = obj.getLong("fileSize");
                                            pf.downloadUrl = obj.getString("downloadUrl");
                                            pf.type = "push";
                                            uploadList.add(pf);
                                        }
                                    } catch (JSONException e) {
                                        e.printStackTrace();
                                    }
                                }
                                final List<PendingFileItem> finalList = uploadList;
                                mainHandler.post(
                                        () -> {
                                            pendingList.clear();
                                            pendingList.addAll(finalList);
                                            pendingAdapter.notifyDataSetChanged();
                                            // ========== 新增：控制待接收文件区域显示隐藏 ==========
                                            View pendingLayout = findViewById(R.id.pending_layout);
                                            if (pendingLayout != null) {
                                                if (pendingList.isEmpty()) {
                                                    pendingLayout.setVisibility(View.GONE);
                                                } else {
                                                    pendingLayout.setVisibility(View.VISIBLE);
                                                }
                                            }
                                        });
                            }
                        });
    }

    private void startSendFilesFlow() {
        // 1. 弹出文件选择器（多选）
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择要发送的文件"), REQUEST_CODE_SEND_FILE);
    }

    // ==================== App -> 网页端 发送 ====================
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SEND_FILE && resultCode == RESULT_OK && data != null) {
            // 收集选中的文件 Uri 列表
            List<Uri> fileUris = new ArrayList<>();
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    fileUris.add(clipData.getItemAt(i).getUri());
                }
            } else {
                Uri uri = data.getData();
                if (uri != null) fileUris.add(uri);
            }
            if (fileUris.isEmpty()) {
                Toast.makeText(this, "未选择文件", Toast.LENGTH_SHORT).show();
                return;
            }
            // 2. 弹出用户选择对话框（多选）
            showUserSelectionDialog(fileUris);
        }
    }

    private void showUserSelectionDialog(List<Uri> fileUris) {
        // 获取在线用户列表（排除自己）
        List<String> availableUsers = new ArrayList<>(userList);
        if (availableUsers.isEmpty()) {
            Toast.makeText(this, "没有在线用户", Toast.LENGTH_SHORT).show();
            return;
        }
        // 构建复选框列表
        boolean[] checkedItems = new boolean[availableUsers.size()];
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择目标用户（可多选）");
        builder.setMultiChoiceItems(
                availableUsers.toArray(new String[0]),
                checkedItems,
                (dialog, which, isChecked) -> {
                    // 无需额外操作
                });
        builder.setPositiveButton(
                "发送",
                (dialog, which) -> {
                    List<String> selectedUsers = new ArrayList<>();
                    for (int i = 0; i < checkedItems.length; i++) {
                        if (checkedItems[i]) {
                            selectedUsers.add(availableUsers.get(i));
                        }
                    }
                    if (selectedUsers.isEmpty()) {
                        Toast.makeText(MainActivity.this, "请至少选择一个用户", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 发送文件给所有选中的用户
                    for (Uri uri : fileUris) {
                        String fileName = getFileName(uri);
                        if (fileName != null) {
                            for (String target : selectedUsers) {
                                uploadFileToUser(uri, fileName, target);
                            }
                        }
                    }
                    Toast.makeText(
                                    MainActivity.this,
                                    "开始发送给 " + selectedUsers.size() + " 个用户",
                                    Toast.LENGTH_SHORT)
                            .show();
                });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
        }
        return result;
    }

    private void uploadFileToUser(Uri fileUri, String fileName, String targetUser) {
        try {
            ContentResolver resolver = getContentResolver();
            InputStream inputStream = resolver.openInputStream(fileUri);
            if (inputStream == null) {
                Log.e(TAG, "无法打开文件流");
                return;
            }
            byte[] fileBytes = inputStream.readAllBytes();
            inputStream.close();
            Log.d(TAG, "文件大小=" + fileBytes.length);

            RequestBody requestBody =
                    new MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart(
                                    "file",
                                    fileName,
                                    RequestBody.create(
                                            MediaType.parse("application/octet-stream"), fileBytes))
                            .addFormDataPart("target", URLEncoder.encode(targetUser, "UTF-8"))
                            .addFormDataPart("originalName", URLEncoder.encode(fileName, "UTF-8"))
                            .addFormDataPart("sender", URLEncoder.encode("手机", "UTF-8"))
                            .build();

            String url = "http://" + getLocalIpAddress() + ":8080/push";
            Log.d(TAG, "发送请求到: " + url);
            Request request = new Request.Builder().url(url).post(requestBody).build();

            httpClient
                    .newCall(request)
                    .enqueue(
                            new Callback() {
                                @Override
                                public void onFailure(Call call, IOException e) {
                                    Log.e(TAG, "发送失败", e);
                                    mainHandler.post(
                                            () ->
                                                    Toast.makeText(
                                                                    MainActivity.this,
                                                                    "发送失败: " + e.getMessage(),
                                                                    Toast.LENGTH_LONG)
                                                            .show());
                                }

                                @Override
                                public void onResponse(Call call, Response response)
                                        throws IOException {
                                    String body =
                                            response.body() != null ? response.body().string() : "";
                                    Log.d(TAG, "响应码=" + response.code() + ", 响应内容=" + body);
                                    if (response.isSuccessful()) {
                                        mainHandler.post(
                                                () ->
                                                        Toast.makeText(
                                                                        MainActivity.this,
                                                                        "文件已发送给 " + targetUser,
                                                                        Toast.LENGTH_SHORT)
                                                                .show());
                                    } else {
                                        mainHandler.post(
                                                () ->
                                                        Toast.makeText(
                                                                        MainActivity.this,
                                                                        "发送失败: " + body,
                                                                        Toast.LENGTH_LONG)
                                                                .show());
                                    }
                                }
                            });

        } catch (Exception e) {
            Log.e(TAG, "读取文件异常", e);
            Toast.makeText(this, "读取文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== App 管理待推送 ====================
    private void fetchPendingPushes(String targetUser, PendingPushesCallback callback) {
        String url = "http://" + getLocalIpAddress() + ":8080/pending_pushes?user=" + targetUser;
        Request request = new Request.Builder().url(url).build();
        httpClient
                .newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(Call call, IOException e) {
                                mainHandler.post(
                                        () -> {
                                            Toast.makeText(
                                                            MainActivity.this,
                                                            "获取失败",
                                                            Toast.LENGTH_SHORT)
                                                    .show();
                                            callback.onResult(null);
                                        });
                            }

                            @Override
                            public void onResponse(Call call, Response response)
                                    throws IOException {
                                if (response.isSuccessful()) {
                                    String json = response.body().string();
                                    List<PendingPush> list = new ArrayList<>();
                                    try {
                                        JSONArray arr = new JSONArray(json);
                                        for (int i = 0; i < arr.length(); i++) {
                                            JSONObject obj = arr.getJSONObject(i);
                                            PendingPush p = new PendingPush();
                                            p.fileName = obj.getString("fileName");
                                            p.fileSize = obj.getLong("fileSize");
                                            list.add(p);
                                        }
                                    } catch (JSONException e) {
                                        e.printStackTrace();
                                    }
                                    final List<PendingPush> finalList = list;
                                    mainHandler.post(() -> callback.onResult(finalList));
                                } else {
                                    mainHandler.post(() -> callback.onResult(null));
                                }
                            }
                        });
    }

    private void cancelPush(String targetUser, String fileName) {
        String url = "http://" + getLocalIpAddress() + ":8080/cancel_push?target=" + targetUser;
        if (fileName != null) {
            url += "&fileName=" + fileName;
        }
        Request request =
                new Request.Builder().url(url).post(RequestBody.create(null, new byte[0])).build();
        httpClient
                .newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(Call call, IOException e) {
                                mainHandler.post(
                                        () ->
                                                Toast.makeText(
                                                                MainActivity.this,
                                                                "取消失败",
                                                                Toast.LENGTH_SHORT)
                                                        .show());
                            }

                            @Override
                            public void onResponse(Call call, Response response)
                                    throws IOException {
                                if (response.isSuccessful()) {
                                    mainHandler.post(
                                            () -> {
                                                Toast.makeText(
                                                                MainActivity.this,
                                                                "已取消",
                                                                Toast.LENGTH_SHORT)
                                                        .show();
                                                refreshUserList();
                                            });
                                } else {
                                    mainHandler.post(
                                            () ->
                                                    Toast.makeText(
                                                                    MainActivity.this,
                                                                    "取消失败: " + response.message(),
                                                                    Toast.LENGTH_SHORT)
                                                            .show());
                                }
                            }
                        });
    }

    // ==================== 用户列表 ====================
    private void refreshUserList() {
        if (server == null) return;
        String url = "http://" + getLocalIpAddress() + ":8080/users";
        Request request = new Request.Builder().url(url).build();
        httpClient
                .newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(Call call, IOException e) {
                                Log.e(TAG, "获取用户列表失败", e);
                            }

                            @Override
                            public void onResponse(Call call, Response response)
                                    throws IOException {
                                if (response.isSuccessful()) {
                                    String json = response.body().string();
                                    try {
                                        JSONArray array = new JSONArray(json);
                                        List<String> newList = new ArrayList<>();
                                        for (int i = 0; i < array.length(); i++) {
                                            String user = array.getString(i);
                                            // 过滤掉 "手机" 用户（App自身）
                                            if (!"手机".equals(user)) {
                                                newList.add(user);
                                            }
                                        }
                                        mainHandler.post(
                                                () -> {
                                                    userList.clear();
                                                    userList.addAll(newList);
                                                    userAdapter.notifyDataSetChanged();
                                                    if (tvOnlineCount != null) {
                                                        tvOnlineCount.setText(
                                                                " (" + newList.size() + ")");
                                                    }
                                                });
                                    } catch (JSONException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        });
    }

    // ==================== 服务控制 ====================
    private void startServer() {
        if (server != null) {
            Toast.makeText(this, "服务已在运行", Toast.LENGTH_SHORT).show();
            return;
        }
        updateSharedDir();
        try {
            server = new FileServer(8080, sharedDir, this);
            server.start();

            String ip = getLocalIpAddress();
            if (ip == null) {
                tvStatus.setText("状态：已启动，但无法获取 IP");
                Toast.makeText(this, "无法获取 IP，请检查网络", Toast.LENGTH_LONG).show();
                return;
            }

            String url = "http://" + ip + ":8080";
            tvUrl.setText("地址：" + url);
            tvStatus.setText("状态：✅ 服务已启动");
            generateQrCode(url);

            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            btnCopy.setEnabled(true);

            Toast.makeText(this, "服务启动成功，访问地址: " + url, Toast.LENGTH_LONG).show();
            startPolling();
            startUserPolling();
            startHeartbeat();
            startOutgoingPolling();
        } catch (Exception e) {
            e.printStackTrace();
            tvStatus.setText("状态：启动失败");
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        tvStatus.setText("状态：已停止");
        tvUrl.setText("地址：");
        ivQr.setImageBitmap(null);
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        btnCopy.setEnabled(false);
        stopPolling();
        stopUserPolling();
        stopHeartbeat();
        stopOutgoingPolling();
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
    }

    private void startHeartbeat() {
        if (heartbeatRunnable != null) return;
        heartbeatRunnable =
                new Runnable() {
                    @Override
                    public void run() {
                        if (server != null) {
                            String url =
                                    "http://" + getLocalIpAddress() + ":8080/heartbeat?user=手机";
                            Request request = new Request.Builder().url(url).build();
                            httpClient
                                    .newCall(request)
                                    .enqueue(
                                            new Callback() {
                                                @Override
                                                public void onFailure(Call call, IOException e) {}

                                                @Override
                                                public void onResponse(Call call, Response response)
                                                        throws IOException {
                                                    response.close();
                                                }
                                            });
                        }
                        heartbeatHandler.postDelayed(this, 10000);
                    }
                };
        heartbeatHandler.post(heartbeatRunnable);
    }

    private void stopHeartbeat() {
        if (heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
    }

    // ==================== 轮询 ====================
    private void startPolling() {
        pendingRunnable =
                new Runnable() {
                    @Override
                    public void run() {
                        refreshPendingList();
                        pollHandler.postDelayed(this, 3000);
                    }
                };
        pollHandler.post(pendingRunnable);
    }

    private void stopPolling() {
        if (pendingRunnable != null) {
            pollHandler.removeCallbacks(pendingRunnable);
            pendingRunnable = null;
        }
    }

    private void startUserPolling() {
        userPollRunnable =
                new Runnable() {
                    @Override
                    public void run() {
                        refreshUserList();
                        userHandler.postDelayed(this, 3000);
                    }
                };
        userHandler.post(userPollRunnable);
    }

    private void stopUserPolling() {
        if (userPollRunnable != null) {
            userHandler.removeCallbacks(userPollRunnable);
            userPollRunnable = null;
        }
    }

    // ==================== 历史记录 ====================
    public static void addHistory(Context context, String record) {
        SharedPreferences prefs = context.getSharedPreferences("history", MODE_PRIVATE);
        String json = prefs.getString("history", "[]");
        try {
            JSONArray array = new JSONArray(json);
            array.put(record);
            prefs.edit().putString("history", array.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // ==================== 二维码和复制 ====================
    private void generateQrCode(String content) {
        new Thread(
                        () -> {
                            try {
                                MultiFormatWriter writer = new MultiFormatWriter();
                                BitMatrix matrix =
                                        writer.encode(content, BarcodeFormat.QR_CODE, 400, 400);
                                Bitmap bitmap = bitMatrixToBitmap(matrix);
                                mainHandler.post(() -> ivQr.setImageBitmap(bitmap));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        })
                .start();
    }

    private Bitmap bitMatrixToBitmap(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private void copyUrlToClipboard() {
        String url = tvUrl.getText().toString();
        if (url.startsWith("地址：")) {
            url = url.substring(3);
        }
        if (url.isEmpty()) {
            Toast.makeText(this, "没有地址可复制", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("URL", url);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "地址已复制", Toast.LENGTH_SHORT).show();
    }

    private void updateSharedDir() {
        String saveDirName = getSaveDirName();
        sharedDir =
                new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS),
                        saveDirName);
        if (!sharedDir.exists()) {
            sharedDir.mkdirs();
        }
        tvSavePath.setText("保存路径：" + sharedDir.getAbsolutePath());
    }

    private String getSaveDirName() {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        return prefs.getString("save_dir_name", "阿俊快传");
    }

    private void showSetPathDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("设置保存文件夹名称");
        final EditText input = new EditText(this);
        input.setText(getSaveDirName());
        builder.setView(input);
        builder.setPositiveButton(
                "确定",
                (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "阿俊快传";
                    SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
                    prefs.edit().putString("save_dir_name", name).apply();
                    updateSharedDir();
                    Toast.makeText(MainActivity.this, "路径已更新，下次接收文件将保存到该目录", Toast.LENGTH_SHORT)
                            .show();
                    if (server != null) {
                        Toast.makeText(MainActivity.this, "请重启服务使新路径生效", Toast.LENGTH_LONG).show();
                    }
                });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void checkAndRequestPermissions() {
        String[] permissions = {
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        boolean allGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "需要存储权限才能传输文件", Toast.LENGTH_LONG).show();
                    break;
                }
            }
        }
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface intf = interfaces.nextElement();
                Enumeration<InetAddress> addresses = intf.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException ex) {
            ex.printStackTrace();
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver);
        stopPolling();
        stopUserPolling();
    }

    // ==================== 内部类 ====================
    static class PendingPush {
        String fileName;
        long fileSize;
    }

    interface PendingPushesCallback {
        void onResult(List<PendingPush> pushes);
    }
}
