package org.gptdictation.assistant;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Deterministic set of semantic labels grouped by clickable toolbar container. */
final class HeaderContext {
    private final TreeMap<String, TreeSet<String>> controls = new TreeMap<>();
    void add(String container, String semanticHash) {
        if (container == null || container.isEmpty() || semanticHash == null || semanticHash.isEmpty()) return;
        TreeSet<String> labels = controls.get(container);
        if (labels == null) { labels = new TreeSet<>(); controls.put(container, labels); }
        labels.add(semanticHash);
    }
    String canonical() {
        if (controls.size() < 2) return null;
        StringBuilder value = new StringBuilder();
        for (Map.Entry<String, TreeSet<String>> item : controls.entrySet()) {
            value.append(item.getKey()).append('=');
            for (String label : item.getValue()) value.append(label).append(',');
            value.append('|');
        }
        return value.toString();
    }
}
