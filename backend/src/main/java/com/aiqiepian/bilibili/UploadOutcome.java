package com.aiqiepian.bilibili;

/**
 * 分片上传完成后的结果（M3 §2，upos/bup 协议）。
 * uri        —— B 站返回的合并后视频资源地址（upos location，如 bucket/key.mp4，提交稿件时引用）。
 * containBvid—— 部分协议在合并完成时直接回传的稿件 bvid（upos 协议恒为 null）。
 * objectName —— 稿件提交 videos[].filename 使用的对象名（upos_uri 文件名的去扩展名部分）。
 */
public record UploadOutcome(String uri, String containBvid, String objectName) {

    /** 兼容旧两参构造（mock/旧协议占位） */
    public UploadOutcome(String uri, String containBvid) {
        this(uri, containBvid, null);
    }
}
