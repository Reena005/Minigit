package com.minigit.storage;

import java.io.IOException;

public interface ObjectStore {

    String store(byte[] content) throws IOException;

    byte[] read(String hash) throws IOException;

    boolean exists(String hash);
}
