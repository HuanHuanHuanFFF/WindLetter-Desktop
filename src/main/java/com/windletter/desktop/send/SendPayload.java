package com.windletter.desktop.send;

import java.util.Objects;

/** Caller-owned business payload copied into the send operation. */
public record SendPayload(String contentType, byte[] data) {

    public SendPayload {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(data, "data");
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    public long size() {
        return data.length;
    }
}
