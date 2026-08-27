package com.ajun.filetransfer;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;

public class FileServer extends NanoHTTPD {

    private static final String TAG = "FileServer";
    public static final String ACTION_PROGRESS = "com.ajun.filetransfer.PROGRESS";

    private final File sharedDirectory;
    private final Context context;
    private final File tempDirectory;
    private final Map<String, PendingInfo> pendingMap = new HashMap<>();
    private final Map<String, UserSession> userSessions = new ConcurrentHashMap<>();
    private final Map<String, List<PendingPush>> pendingPushes = new ConcurrentHashMap<>();
    private final Map<String, List<PendingPush>> outgoingPushes = new ConcurrentHashMap<>();
    private final Map<String, List<String>> userReceivedFiles = new ConcurrentHashMap<>();

    static class UserSession {
        String userName;
        String ip;
        long lastActive;
    }

    static class PendingPush {
        String fileName;
        long fileSize;
        String downloadUrl;
        String sender;
        String targetUser;
        File tempFile;
    }

    static class PendingInfo {
        String id;
        String originalName;
        long fileSize;
        File tempFile;
    }

    public FileServer(int port, File sharedDir, Context context) {
        super(port);
        this.sharedDirectory = sharedDir;
        this.context = context;
        tempDirectory = new File(context.getCacheDir(), "pending");
        if (!tempDirectory.exists()) tempDirectory.mkdirs();
        if (!sharedDirectory.exists()) sharedDirectory.mkdirs();

        UserSession appUser = new UserSession();
        appUser.userName = "手机";
        appUser.ip = "127.0.0.1";
        appUser.lastActive = System.currentTimeMillis();
        userSessions.put("手机", appUser);
        Log.d(TAG, "已注册App端用户: 手机");
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        Log.d(TAG, "Request: " + method + " " + uri);

        try {
            if ("/".equals(uri) && Method.GET.equals(method)) return serveWebPage();
            if ("/ip".equals(uri) && Method.GET.equals(method)) {
                String ip = getLocalIpAddress();
                return newFixedLengthResponse(Response.Status.OK, "text/plain", ip != null ? ip : "未知");
            }
            if ("/list".equals(uri) && Method.GET.equals(method)) {
                Map<String, String> params = session.getParms();
                String user = params.get("user");
                if (user != null && !user.isEmpty() && userSessions.containsKey(user)) {
                    userSessions.get(user).lastActive = System.currentTimeMillis();
                }
                return serveFileList(user);
            }
            if (uri.startsWith("/download/") && Method.GET.equals(method)) {
                String fileName = uri.substring("/download/".length());
                try {
                    fileName = URLDecoder.decode(fileName, "UTF-8");
                } catch (Exception e) {
                    Log.w(TAG, "解码文件名失败", e);
                }
                return serveFileDownload(fileName);
            }
            if ("/upload".equals(uri) && Method.POST.equals(method)) {
                return handleFileUpload(session);
            }
            if ("/pending".equals(uri) && Method.GET.equals(method)) {
                return servePendingList();
            }
            if ("/confirm".equals(uri) && Method.POST.equals(method)) {
                return handleConfirm(session);
            }
            if ("/reject".equals(uri) && Method.POST.equals(method)) {
                return handleReject(session);
            }
            if ("/connect".equals(uri) && Method.GET.equals(method)) {
                return handleConnect(session);
            }
            if ("/notify".equals(uri) && Method.GET.equals(method)) {
                Map<String, String> params = session.getParms();
                String userName = params.get("user");
                if (userName != null && !userName.isEmpty() && userSessions.containsKey(userName)) {
                    userSessions.get(userName).lastActive = System.currentTimeMillis();
                }
                return handleNotify(session);
            }
            if ("/users".equals(uri) && Method.GET.equals(method)) {
                return handleUsers();
            }
            if ("/push".equals(uri) && Method.POST.equals(method)) {
                return handlePush(session);
            }
            if ("/thumbnail".equals(uri) && Method.GET.equals(method)) {
                return serveThumbnail(session);
            }
            if ("/pending_pushes".equals(uri) && Method.GET.equals(method)) {
                Map<String, String> params = session.getParms();
                String user = params.get("user");
                if (user != null && !user.isEmpty() && userSessions.containsKey(user)) {
                    userSessions.get(user).lastActive = System.currentTimeMillis();
                }
                return handlePendingPushes(session);
            }
            if ("/cancel_push".equals(uri) && Method.POST.equals(method)) {
                return handleCancelPush(session);
            }
            if ("/outgoing_pushes".equals(uri) && Method.GET.equals(method)) {
                return handleOutgoingPushes(session);
            }
            if ("/confirm_receive".equals(uri) && Method.POST.equals(method)) {
                return handleConfirmReceive(session);
            }
            if ("/reject_receive".equals(uri) && Method.POST.equals(method)) {
                return handleRejectReceive(session);
            }
            if ("/heartbeat".equals(uri) && Method.GET.equals(method)) {
                return handleHeartbeat(session);
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
        } catch (Exception e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server error: " + e.getMessage());
        }
    }

    // ==================== 心跳 ====================
    private Response handleHeartbeat(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String user = params.get("user");
        if (user != null && userSessions.containsKey(user)) {
            userSessions.get(user).lastActive = System.currentTimeMillis();
            Log.d(TAG, "心跳更新: " + user);
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok");
    }

    // ==================== 私有文件列表 ====================
    private Response serveFileList(String user) {
        if (user != null && !user.isEmpty()) {
            List<String> files = userReceivedFiles.get(user);
            if (files == null) files = new ArrayList<>();
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < files.size(); i++) {
                String fname = files.get(i);
                File f = new File(sharedDirectory, fname);
                if (f.exists() && f.isFile()) {
                    json.append("{\"name\":\"").append(escapeJson(fname)).append("\",\"size\":").append(f.length()).append("}");
                    if (i < files.size() - 1) json.append(",");
                }
            }
            json.append("]");
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString());
        } else {
            File[] files = sharedDirectory.listFiles();
            List<Map<String, Object>> fileInfoList = new ArrayList<>();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", f.getName());
                        info.put("size", f.length());
                        fileInfoList.add(info);
                    }
                }
            }
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < fileInfoList.size(); i++) {
                Map<String, Object> map = fileInfoList.get(i);
                json.append("{\"name\":\"").append(escapeJson(map.get("name").toString()))
                        .append("\",\"size\":").append(map.get("size")).append("}");
                if (i < fileInfoList.size() - 1) json.append(",");
            }
            json.append("]");
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString());
        }
    }

    // ==================== 文件下载 ====================
    private Response serveFileDownload(String fileName) {
        File file = new File(sharedDirectory, fileName);
        if (file.exists() && file.isFile()) {
            try {
                FileInputStream fis = new FileInputStream(file);
                return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", fis, file.length());
            } catch (IOException e) {
                e.printStackTrace();
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "读取文件失败");
            }
        } else {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "文件不存在");
        }
    }

    // ==================== 缩略图 ====================
    private Response serveThumbnail(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String fileName = params.get("file");
        boolean full = "true".equals(params.get("full"));
        if (fileName == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "缺少文件名");
        }
        try {
            fileName = URLDecoder.decode(fileName, "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "解码缩略图文件名失败", e);
        }
        File file = new File(sharedDirectory, fileName);
        if (!file.exists() || !file.isFile()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "文件不存在");
        }
        String lower = fileName.toLowerCase();
        boolean isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".gif") ||
                lower.endsWith(".bmp") || lower.endsWith(".webp");
        if (!isImage) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "不是图片文件");
        }
        try {
            if (full) {
                FileInputStream fis = new FileInputStream(file);
                return newFixedLengthResponse(Response.Status.OK, "image/jpeg", fis, file.length());
            } else {
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap == null) {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "无法解码图片");
                }
                Bitmap thumb = Bitmap.createScaledBitmap(bitmap, 200, 200, true);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                thumb.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] imageData = baos.toByteArray();
                return newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                        new ByteArrayInputStream(imageData), imageData.length);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "处理图片失败");
        }
    }

    // ==================== 用户连接 ====================
    private Response handleConnect(IHTTPSession session) {
        String ip = session.getHeaders().get("x-forwarded-for");
        if (ip == null) ip = session.getHeaders().get("remote-addr");
        if (ip == null) ip = getLocalIpAddress();
        String userName = "游客_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UserSession us = new UserSession();
        us.userName = userName;
        us.ip = ip;
        us.lastActive = System.currentTimeMillis();
        userSessions.put(userName, us);
        JSONObject json = new JSONObject();
        try { json.put("userName", userName); } catch (JSONException e) {}
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
    }

    // ==================== 在线用户列表 ====================
    private Response handleUsers() {
        long now = System.currentTimeMillis();
        userSessions.entrySet().removeIf(e ->
                !"手机".equals(e.getKey()) && now - e.getValue().lastActive > 30000
        );
        List<String> names = new ArrayList<>(userSessions.keySet());
        StringBuilder sb = new StringBuilder("[");
        for (String name : names) {
            sb.append("\"").append(escapeJson(name)).append("\",");
        }
        if (sb.length() > 1) sb.deleteCharAt(sb.length() - 1);
        sb.append("]");
        return newFixedLengthResponse(Response.Status.OK, "application/json", sb.toString());
    }

    // ==================== 解码工具 ====================
    private String decodeParam(String value) {
        if (value == null) return null;
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "解码失败，使用原值", e);
            return value;
        }
    }

    // ==================== 网页端 -> App 上传（待确认） ====================
    private Response handleFileUpload(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            Map<String, List<String>> params = session.getParameters();

            String originalName = params.get("originalName") != null ? params.get("originalName").get(0) : null;
            originalName = decodeParam(originalName);
            if (originalName == null || originalName.isEmpty()) {
                originalName = getFileNameFromHeaders(session);
            }
            if (originalName == null || originalName.isEmpty()) {
                originalName = "file_" + System.currentTimeMillis() + ".bin";
            }

            String tempFilePath = null;
            for (String value : files.values()) {
                if (value != null && new File(value).exists()) {
                    tempFilePath = value;
                    break;
                }
            }
            if (tempFilePath == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "没有文件数据");
            }
            File tempFile = new File(tempFilePath);

            String id = UUID.randomUUID().toString();
            File destTemp = new File(tempDirectory, id + ".tmp");
            if (!tempFile.renameTo(destTemp)) {
                copyFile(tempFile, destTemp);
                tempFile.delete();
            }

            PendingInfo info = new PendingInfo();
            info.id = id;
            info.originalName = originalName;
            info.fileSize = tempFile.length();
            info.tempFile = destTemp;
            pendingMap.put(id, info);

            return newFixedLengthResponse(Response.Status.OK, "text/plain", "文件已接收，等待确认");
        } catch (Exception e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "上传失败: " + e.getMessage());
        }
    }

    // ==================== App 待确认列表 ====================
    private Response servePendingList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PendingInfo info : pendingMap.values()) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", info.id);
            item.put("fileName", info.originalName);
            item.put("fileSize", info.fileSize);
            item.put("tempPath", info.tempFile.getAbsolutePath());
            list.add(item);
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> map = list.get(i);
            json.append("{\"id\":\"").append(map.get("id"))
                    .append("\",\"fileName\":\"").append(escapeJson(map.get("fileName").toString()))
                    .append("\",\"fileSize\":").append(map.get("fileSize"))
                    .append(",\"tempPath\":\"").append(map.get("tempPath")).append("\"}");
            if (i < list.size() - 1) json.append(",");
        }
        json.append("]");
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString());
    }

    // ==================== App 确认接收（upload） ====================
    private Response handleConfirm(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String id = params.get("id");
        if (id == null || !pendingMap.containsKey(id)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "无效的ID");
        }
        PendingInfo info = pendingMap.remove(id);
        File destFile = new File(sharedDirectory, info.originalName);
        int counter = 1;
        String baseName = info.originalName;
        String extension = "";
        int dotIndex = info.originalName.lastIndexOf('.');
        if (dotIndex != -1) {
            baseName = info.originalName.substring(0, dotIndex);
            extension = info.originalName.substring(dotIndex);
        }
        while (destFile.exists()) {
            String newName = baseName + " (" + counter + ")" + extension;
            destFile = new File(sharedDirectory, newName);
            counter++;
        }
        try {
            copyFile(info.tempFile, destFile);
            info.tempFile.delete();

            String record = "接收: " + info.originalName + " (" + (info.fileSize / 1024) + " KB) " +
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            MainActivity.addHistory(context, record);

            sendProgressBroadcast(info.originalName, 100, destFile.getAbsolutePath());
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "确认成功");
        } catch (IOException e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "移动文件失败");
        }
    }

    // ==================== App 拒绝接收（upload） ====================
    private Response handleReject(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String id = params.get("id");
        if (id == null || !pendingMap.containsKey(id)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "无效的ID");
        }
        PendingInfo info = pendingMap.remove(id);
        if (info.tempFile.exists()) {
            info.tempFile.delete();
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "已拒绝");
    }

    // ==================== App -> 网页端 推送 ====================
    private Response handlePush(IHTTPSession session) {
        Log.d(TAG, "handlePush 开始");
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            Map<String, List<String>> params = session.getParameters();
    
            // 获取并解码参数
            String targetUser = params.get("target") != null ? params.get("target").get(0) : null;
            targetUser = decodeParam(targetUser);
            if (targetUser == null || targetUser.isEmpty()) {
                Log.e(TAG, "目标用户为空");
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "目标用户缺失");
            }
            String sender = params.get("sender") != null ? params.get("sender").get(0) : null;
            sender = decodeParam(sender);
            if (sender == null || sender.isEmpty()) {
                sender = "手机";
            }
            String originalName = params.get("originalName") != null ? params.get("originalName").get(0) : null;
            originalName = decodeParam(originalName);
            if (originalName == null || originalName.isEmpty()) {
                originalName = getFileNameFromHeaders(session);
            }
            if (originalName == null || originalName.isEmpty()) {
                originalName = "file_" + System.currentTimeMillis() + ".bin";
            }
    
            // 获取 NanoHTTPD 临时文件
            String tempFilePath = null;
            for (String value : files.values()) {
                if (value != null && new File(value).exists()) {
                    tempFilePath = value;
                    break;
                }
            }
            if (tempFilePath == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "没有文件数据");
            }
            File tempFile = new File(tempFilePath);
            long fileSize = tempFile.length();
    
            // 确保 tempDirectory 存在
            if (!tempDirectory.exists()) {
                boolean created = tempDirectory.mkdirs();
                if (!created) {
                    Log.e(TAG, "无法创建临时目录: " + tempDirectory.getAbsolutePath());
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "服务器内部错误");
                }
            }
    
            // 将临时文件复制到我们自己的 tempDirectory
            String id = UUID.randomUUID().toString();
            File destTemp = new File(tempDirectory, id + ".tmp");
            try {
                copyFile(tempFile, destTemp);
            } catch (IOException e) {
                Log.e(TAG, "复制临时文件失败", e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "处理文件失败");
            }
            // 删除 NanoHTTPD 原始临时文件
            tempFile.delete();
    
            // 创建推送记录
            PendingPush push = new PendingPush();
            push.fileName = originalName;
            push.fileSize = fileSize;
            push.downloadUrl = "/download/" + originalName;
            push.sender = sender;
            push.targetUser = targetUser;
            push.tempFile = destTemp;
    
            pendingPushes.computeIfAbsent(targetUser, k -> new ArrayList<>()).add(push);
            outgoingPushes.computeIfAbsent(sender, k -> new ArrayList<>()).add(push);
    
            String record = "发送: " + originalName + " (" + (fileSize / 1024) + " KB) 给 " + targetUser +
                    " " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            MainActivity.addHistory(context, record);
    
            Log.d(TAG, "handlePush 成功，发送方=" + sender + "，接收方=" + targetUser + "，文件=" + originalName);
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "推送成功");
        } catch (Exception e) {
            Log.e(TAG, "handlePush 异常", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "推送失败: " + e.getMessage());
        }
    }

    // ==================== 网页端轮询待推送 ====================
    private Response handleNotify(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String userName = params.get("user");
        if (userName == null) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "[]");
        }
        if (!pendingPushes.containsKey(userName)) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "[]");
        }
        List<PendingPush> list = pendingPushes.get(userName);
        JSONArray arr = new JSONArray();
        for (PendingPush p : list) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("fileName", p.fileName);
                obj.put("fileSize", p.fileSize);
                obj.put("downloadUrl", p.downloadUrl);
            } catch (JSONException e) {}
            arr.put(obj);
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString());
    }

    // ==================== 网页端确认接收推送 ====================
    private Response handleConfirmReceive(IHTTPSession session) {
    Map<String, String> params = session.getParms();
    String userNameParam = params.get("user");
    String fileNameParam = params.get("fileName");
    if (userNameParam == null || fileNameParam == null) {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "参数缺失");
    }
    // 解码
    String userName = userNameParam;
    String fileName = fileNameParam;
    try {
        userName = URLDecoder.decode(userNameParam, "UTF-8");
        fileName = URLDecoder.decode(fileNameParam, "UTF-8");
    } catch (Exception e) {
        Log.w(TAG, "解码失败，使用原值", e);
    }

    // 创建 final 副本用于 lambda
    final String finalFileName = fileName;
    final String finalUserName = userName;

    Log.d(TAG, "handleConfirmReceive: userName=" + userName + ", fileName=" + fileName);

    if (!pendingPushes.containsKey(userName)) {
        Log.w(TAG, "该用户无待推送文件");
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "该用户无待推送文件");
    }
    List<PendingPush> list = pendingPushes.get(userName);
    PendingPush removedPush = null;
    for (PendingPush p : list) {
        if (p.fileName.equals(fileName)) {
            removedPush = p;
            break;
        }
    }
    if (removedPush == null) {
        Log.w(TAG, "文件不存在: " + fileName);
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "文件不存在");
    }
    list.remove(removedPush);
    if (list.isEmpty()) {
        pendingPushes.remove(userName);
    }

    // 移动临时文件到共享目录
    File tempFile = removedPush.tempFile;
    if (tempFile == null || !tempFile.exists()) {
        Log.e(TAG, "临时文件丢失: " + (tempFile == null ? "null" : tempFile.getAbsolutePath()));
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "临时文件丢失");
    }
    File destFile = new File(sharedDirectory, fileName);
    int counter = 1;
    while (destFile.exists()) {
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot != -1) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        destFile = new File(sharedDirectory, base + " (" + counter + ")" + ext);
        counter++;
    }
    try {
        copyFile(tempFile, destFile);
        tempFile.delete();
        Log.d(TAG, "文件已保存到: " + destFile.getAbsolutePath());
    } catch (IOException e) {
        Log.e(TAG, "移动文件失败", e);
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "移动文件失败");
    }

    // 从发送方的 outgoingPushes 中移除
    String sender = removedPush.sender;
    if (sender != null && outgoingPushes.containsKey(sender)) {
        List<PendingPush> senderList = outgoingPushes.get(sender);
        // 使用 final 变量
        boolean removed = senderList.removeIf(p -> p.fileName.equals(finalFileName) && p.targetUser != null && p.targetUser.equals(finalUserName));
        if (removed) {
            Log.d(TAG, "从 outgoingPushes 中移除文件: " + fileName + " 发送者=" + sender + " 接收者=" + userName);
        } else {
            Log.w(TAG, "未能从 outgoingPushes 中移除文件: " + fileName + " 发送者=" + sender);
        }
        if (senderList.isEmpty()) {
            outgoingPushes.remove(sender);
        }
    } else {
        Log.w(TAG, "发送者 " + sender + " 不在 outgoingPushes 中，或 sender 为空");
    }

    // 移入已接收列表
    List<String> received = userReceivedFiles.computeIfAbsent(userName, k -> new ArrayList<>());
    if (!received.contains(fileName)) {
        received.add(fileName);
        Log.d(TAG, "文件 " + fileName + " 已加入 " + userName + " 的已接收列表");
    }

    // 记录历史（接收方）
    String record = "接收(推送): " + destFile.getName() + " (" + (destFile.length() / 1024) + " KB) " +
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    MainActivity.addHistory(context, record);

    return newFixedLengthResponse(Response.Status.OK, "text/plain", "确认接收成功");
}

    // ==================== 网页端拒绝接收推送 ====================
    private Response handleRejectReceive(IHTTPSession session) {
    Map<String, String> params = session.getParms();
    String userNameParam = params.get("user");
    String fileNameParam = params.get("fileName");
    if (userNameParam == null || fileNameParam == null) {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "参数缺失");
    }
    String userName = userNameParam;
    String fileName = fileNameParam;
    try {
        userName = URLDecoder.decode(userNameParam, "UTF-8");
        fileName = URLDecoder.decode(fileNameParam, "UTF-8");
    } catch (Exception e) {
        Log.w(TAG, "解码失败，使用原值", e);
    }

    // 创建 final 副本
    final String finalFileName = fileName;
    final String finalUserName = userName;

    Log.d(TAG, "handleRejectReceive: userName=" + userName + ", fileName=" + fileName);

    if (!pendingPushes.containsKey(userName)) {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "该用户无待推送文件");
    }
    List<PendingPush> list = pendingPushes.get(userName);
    PendingPush removedPush = null;
    for (PendingPush p : list) {
        if (p.fileName.equals(fileName)) {
            removedPush = p;
            break;
        }
    }
    if (removedPush == null) {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "文件不存在");
    }
    list.remove(removedPush);
    if (list.isEmpty()) {
        pendingPushes.remove(userName);
    }
    // 删除临时文件
    if (removedPush.tempFile != null && removedPush.tempFile.exists()) {
        removedPush.tempFile.delete();
        Log.d(TAG, "已删除临时文件: " + removedPush.tempFile.getAbsolutePath());
    }
    // 从发送方的 outgoingPushes 中移除
    String sender = removedPush.sender;
    if (sender != null && outgoingPushes.containsKey(sender)) {
        boolean removed = outgoingPushes.get(sender).removeIf(p -> p.fileName.equals(finalFileName) && p.targetUser != null && p.targetUser.equals(finalUserName));
        if (removed) {
            Log.d(TAG, "从 outgoingPushes 中移除文件 (拒绝): " + fileName);
        }
        if (outgoingPushes.get(sender).isEmpty()) {
            outgoingPushes.remove(sender);
        }
    }
    return newFixedLengthResponse(Response.Status.OK, "text/plain", "已拒绝接收");
}

    // ==================== App 管理待推送 ====================
    private Response handlePendingPushes(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String user = params.get("user");
        if (user == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "缺少user参数");
        }
        List<PendingPush> list = pendingPushes.get(user);
        if (list == null) list = new ArrayList<>();
        JSONArray arr = new JSONArray();
        for (PendingPush p : list) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("fileName", p.fileName);
                obj.put("fileSize", p.fileSize);
                obj.put("downloadUrl", p.downloadUrl);
            } catch (JSONException e) {}
            arr.put(obj);
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString());
    }

    // ==================== App 取消推送 ====================
    private Response handleCancelPush(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String targetUser = params.get("target");
        String fileName = params.get("fileName");
        if (targetUser == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "缺少target参数");
        }
        if (!pendingPushes.containsKey(targetUser)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "该用户无待推送文件");
        }
        List<PendingPush> list = pendingPushes.get(targetUser);
        if (fileName == null) {
            // 取消全部，删除所有临时文件
            for (PendingPush p : list) {
                if (p.tempFile != null && p.tempFile.exists()) {
                    p.tempFile.delete();
                }
            }
            pendingPushes.remove(targetUser);
            // 也从 outgoingPushes 中移除所有发给 targetUser 的条目
            for (Map.Entry<String, List<PendingPush>> entry : outgoingPushes.entrySet()) {
                entry.getValue().removeIf(p -> p.targetUser != null && p.targetUser.equals(targetUser));
                if (entry.getValue().isEmpty()) {
                    outgoingPushes.remove(entry.getKey());
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "已取消全部");
        } else {
            PendingPush removed = null;
            for (PendingPush p : list) {
                if (p.fileName.equals(fileName)) {
                    removed = p;
                    break;
                }
            }
            if (removed == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "未找到该文件");
            }
            list.remove(removed);
            if (removed.tempFile != null && removed.tempFile.exists()) {
                removed.tempFile.delete();
            }
            if (list.isEmpty()) {
                pendingPushes.remove(targetUser);
            }
            // 从 outgoingPushes 中移除匹配文件名和目标用户的条目
            for (Map.Entry<String, List<PendingPush>> entry : outgoingPushes.entrySet()) {
                entry.getValue().removeIf(p -> p.fileName.equals(fileName) && p.targetUser != null && p.targetUser.equals(targetUser));
                if (entry.getValue().isEmpty()) {
                    outgoingPushes.remove(entry.getKey());
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "已取消文件 " + fileName);
        }
    }

    // ==================== 已发送待接收（网页端查看） ====================
    private Response handleOutgoingPushes(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String sender = params.get("user");
        if (sender == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "缺少user参数");
        }
        List<PendingPush> list = outgoingPushes.get(sender);
        if (list == null) list = new ArrayList<>();
        JSONArray arr = new JSONArray();
        for (PendingPush p : list) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("fileName", p.fileName);
                obj.put("fileSize", p.fileSize);
                obj.put("downloadUrl", p.downloadUrl);
                obj.put("targetUser", p.targetUser != null ? p.targetUser : "未知");
            } catch (JSONException e) {}
            arr.put(obj);
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString());
    }

    // ==================== 工具方法 ====================
    private String getFileNameFromHeaders(IHTTPSession session) {
        Map<String, String> headers = session.getHeaders();
        String contentDisposition = headers.get("content-disposition");
        if (contentDisposition != null) {
            String filename = extractFileName(contentDisposition);
            if (filename != null && !filename.isEmpty()) {
                try {
                    return URLDecoder.decode(filename, "UTF-8");
                } catch (Exception e) {
                    return filename;
                }
            }
        }
        return null;
    }

    private String extractFileName(String contentDisposition) {
        String[] parts = contentDisposition.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("filename=")) {
                String filename = part.substring("filename=".length());
                if (filename.startsWith("\"") && filename.endsWith("\"")) {
                    filename = filename.substring(1, filename.length() - 1);
                }
                int lastSlash = filename.lastIndexOf('/');
                if (lastSlash != -1) {
                    filename = filename.substring(lastSlash + 1);
                }
                lastSlash = filename.lastIndexOf('\\');
                if (lastSlash != -1) {
                    filename = filename.substring(lastSlash + 1);
                }
                return filename;
            }
        }
        return null;
    }

    private void copyFile(File src, File dst) throws IOException {
        // 确保目标文件的父目录存在
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (InputStream in = new FileInputStream(src);
            OutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
        }
    }

    private void sendProgressBroadcast(String fileName, int progress) {
        sendProgressBroadcast(fileName, progress, null);
    }

    private void sendProgressBroadcast(String fileName, int progress, String savePath) {
        Intent intent = new Intent(ACTION_PROGRESS);
        intent.putExtra("fileName", fileName);
        intent.putExtra("progress", progress);
        if (savePath != null) {
            intent.putExtra("savePath", savePath);
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private Response serveWebPage() {
        try {
            AssetManager assetManager = context.getAssets();
            InputStream inputStream = assetManager.open("index.html");
            byte[] data = new byte[inputStream.available()];
            inputStream.read(data);
            inputStream.close();
            String html = new String(data, "UTF-8");
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
        } catch (IOException e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html", "无法加载页面");
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

    private String escapeJson(String str) {
        return str.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}