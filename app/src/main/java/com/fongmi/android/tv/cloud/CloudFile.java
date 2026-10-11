package com.fongmi.android.tv.cloud;

/**
 * 网盘文件项
 * 115 字段对照：fid/cid, n=文件名, s=大小, sha, pc=pick_code, ico
 */
public class CloudFile {

    private String id;          // fid（文件）或 cid（目录）
    private String name;        // n
    private long size;          // s
    private boolean isDir;
    private String pickCode;    // pc
    private String sha1;        // sha
    private String parentCid;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public boolean isDir() { return isDir; }
    public void setDir(boolean dir) { isDir = dir; }

    public String getPickCode() { return pickCode; }
    public void setPickCode(String pickCode) { this.pickCode = pickCode; }

    public String getSha1() { return sha1; }
    public void setSha1(String sha1) { this.sha1 = sha1; }

    public String getParentCid() { return parentCid; }
    public void setParentCid(String parentCid) { this.parentCid = parentCid; }

    public boolean isVideo() {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi")
                || n.endsWith(".rmvb") || n.endsWith(".flv") || n.endsWith(".mov")
                || n.endsWith(".wmv") || n.endsWith(".ts") || n.endsWith(".m2ts")
                || n.endsWith(".webm") || n.endsWith(".m3u8");
    }

    public String getFormatSize() {
        if (size <= 0) return "";
        if (size < 1024) return size + "B";
        if (size < 1024 * 1024) return String.format("%.2fKB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format("%.2fMB", size / 1048576.0);
        return String.format("%.2fGB", size / 1073741824.0);
    }
}
