package com.minigit.model;

import com.minigit.util.HashUtil;

public class Blob {

    private final byte[] content;
    private final String hash;

    public Blob(byte[] content) {
        this.content = content.clone();
        this.hash = HashUtil.sha256(this.content);
    }

    public byte[] getContent() {
        return content.clone();
    }

    public String getHash() {
        return hash;
    }
}