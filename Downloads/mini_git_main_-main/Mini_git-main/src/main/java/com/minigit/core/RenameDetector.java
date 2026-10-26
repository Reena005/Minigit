package com.minigit.core;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Detects only unambiguous, byte-identical file moves. */
public final class RenameDetector {

    private RenameDetector() {
    }

    /**
     * Returns destination path to source path for hashes appearing exactly
     * once in both maps. Ambiguous hash matches are deliberately omitted.
     */
    public static Map<String, String> detect(
            Map<String, String> deletedFiles,
            Map<String, String> addedFiles
    ) {
        Map<String, Integer> deletedCounts = countHashes(deletedFiles);
        Map<String, Integer> addedCounts = countHashes(addedFiles);
        Map<String, String> deletedByHash = new HashMap<>();
        Map<String, String> addedByHash = new HashMap<>();

        deletedFiles.forEach((path, hash) -> {
            if (deletedCounts.get(hash) == 1) {
                deletedByHash.put(hash, path);
            }
        });
        addedFiles.forEach((path, hash) -> {
            if (addedCounts.get(hash) == 1) {
                addedByHash.put(hash, path);
            }
        });

        Map<String, String> renames = new TreeMap<>();
        addedByHash.forEach((hash, newPath) -> {
            String oldPath = deletedByHash.get(hash);
            if (oldPath != null && !oldPath.equals(newPath)) {
                renames.put(newPath, oldPath);
            }
        });

        return Map.copyOf(renames);
    }

    private static Map<String, Integer> countHashes(
            Map<String, String> files
    ) {
        Map<String, Integer> counts = new HashMap<>();
        files.values().forEach(hash ->
                counts.merge(hash, 1, Integer::sum));
        return counts;
    }
}
