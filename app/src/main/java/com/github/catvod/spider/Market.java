package com.github.catvod.spider;

import android.app.ProgressDialog;
import android.content.Context;
import android.net.Uri;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.market.Data;
import com.github.catvod.bean.market.Item;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.FileUtil;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.github.catvod.utils.Notify;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import okhttp3.Response;

public class Market extends Spider {

    private ProgressDialog dialog;
    private List<Data> datas;
    private boolean busy;

    public boolean isBusy() {
        return busy;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        // 预处理 extend：如果包含 $，则拆分并合并内容
        if (extend.contains("$")) {
            String[] urls = extend.split("\\$"); // 按 $ 拆分 URL
            Map<String, Data> uniqueDataMap = new LinkedHashMap<>(); // 用于去重，保留首次出现顺序

            for (String url : urls) {
                url = url.trim();
                if (!url.isEmpty()) {
                    try {
                        String content = OkHttp.string(url); // 获取 URL 内容
                        List<Data> dataList = Data.arrayFrom(content); // 解析为 List<Data>

                        // 去重处理：仅保留第一次出现的 name
                        for (Data data : dataList) {
                            if (!uniqueDataMap.containsKey(data.getName())) {
                                uniqueDataMap.put(data.getName(), data);
                            }
                        }
                    } catch (Exception e) {
                        Notify.show(e.getMessage());
                    }
                }
            }

            // 将去重后的数据转换为 JSON 字符串
            List<Data> mergedDataList = new ArrayList<>(uniqueDataMap.values());
            datas = mergedDataList;
        } else if (extend.startsWith("http")) {
            extend = OkHttp.string(extend);
            datas = Data.arrayFrom(extend);
        }
        Init.checkPermission();
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        if (datas.size() > 1) for (int i = 1; i < datas.size(); i++) classes.add(datas.get(i).type());
        return Result.string(classes, datas.get(0).getVod());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        for (Data data : datas) if (data.getName().equals(tid)) return Result.get().page().vod(data.getVod()).string();
        return super.categoryContent(tid, pg, filter, extend);
    }

    @Override
    public String action(String action) {
        try {
            if (isBusy()) return "";
            setBusy(true);
            Init.run(this::setDialog, 500);
            String fileName = Uri.parse(action).getLastPathSegment();
            Response response = OkHttp.newCall(action);
            
            // 尝试从 Content-Disposition 获取
            String contentDisposition = response.header("Content-Disposition");
            if (contentDisposition != null) {
                fileName = parseFileNameFromContentDisposition(contentDisposition);
            }
            
            if (fileName == null || fileName.isEmpty()) {
                fileName = "download_file";
            }
            
            // 解码 URL 编码的中文
            try {
                fileName = java.net.URLDecoder.decode(fileName, "UTF-8");
            } catch (Exception ignored) {}
            
            // 清理非法文件名字符
            fileName = sanitizeFileName(fileName); // 移除 \ / : * ? " < > | 等

            File file = Path.create(new File(Path.download(), fileName));
            download(file, response.body().byteStream(), Double.parseDouble(response.header("Content-Length", "1")));
            if (file.getName().startsWith("__") &&file.getName().endsWith(".png")) {
                fileName = file.getName ();
                String folderName = fileName.substring (2, fileName.length () - 4);
                File folder = new File(Path.root() + File.separator + folderName);
                if (!folder.exists ()) {
                folder.mkdirs (); 
                }
                FileUtil.unzip(file, Path.root());
            } else if (file.getName().endsWith(".zip")) { 
                FileUtil.unzip(file, Path.download());
            } else if (file.getName().endsWith(".apk")) { 
                FileUtil.openFile(file);
            } else Result.notify("下載完成");
            checkCopy(action);
            response.close();
            dismiss();
            return "";
        } catch (Exception e) {
            dismiss();
            return Result.notify(e.getMessage());
        }
    }

    private static String parseFileNameFromContentDisposition(String disposition) {
        if (disposition == null) return null;
        String regex = "filename\\s*=\\s*\"?([^\";]+)\"?";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(disposition);
        if (matcher.find()) {
            String name = matcher.group(1).trim();
            try {
                // 尝试按 RFC 5987 解码（如 filename*=UTF-8''%E6%B5%8B%E8%AF%95.txt）
                if (name.startsWith("UTF-8''")) {
                    return java.net.URLDecoder.decode(name.substring(7), "UTF-8");
                }
            } catch (Exception ignored) {}
            return name;
        }
        return null;
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void download(File file, InputStream is, double length) throws Exception {
        FileOutputStream os = new FileOutputStream(file);
        try (BufferedInputStream input = new BufferedInputStream(is)) {
            byte[] buffer = new byte[4096];
            int readBytes;
            long totalBytes = 0;
            while ((readBytes = input.read(buffer)) != -1) {
                totalBytes += readBytes;
                os.write(buffer, 0, readBytes);
                setProgress((int) (totalBytes / length * 100.0));
            }
        }
    }

    private void checkCopy(String url) {
        for (Data data : datas) {
            int index = data.getList().indexOf(new Item(url));
            if (index == -1) continue;
            String text = data.getList().get(index).getCopy();
            if (!text.isEmpty()) {
                if (text.startsWith("__")) {
                    Notify.show(text.substring(2, text.length()));
                } else {
                    Util.copy(text);
                }
            }
            break;
        }
    }

    private void setDialog() {
        Init.run(() -> {
            try {
                dialog = new ProgressDialog(Init.getActivity());
                dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                dialog.setCancelable(false);
                if (isBusy()) dialog.show();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void dismiss() {
        Init.run(() -> {
            try {
                setBusy(false);
                if (dialog != null) dialog.dismiss();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void setProgress(int value) {
        Init.run(() -> {
            try {
                if (dialog != null) dialog.setProgress(value);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
