package org.gptdictation.assistant;

import java.util.Arrays;

public final class DiagnosticHasherTest {
    public static void main(String[] args) throws Exception {
        byte[] first = new byte[32], second = new byte[32]; Arrays.fill(second, (byte) 7);
        DiagnosticHasher a = new DiagnosticHasher(first), b = new DiagnosticHasher(second);
        String sample = "测试内容 Secret 123";
        String hash = a.hash(sample);
        if (!hash.matches("[0-9a-f]{64}")) throw new AssertionError("expected SHA-256 hex");
        if (!hash.equals(a.hash(sample))) throw new AssertionError("same-run hash differs");
        if (hash.equals(b.hash(sample))) throw new AssertionError("different-run key produced same hash");
        if (hash.contains(sample) || !a.hash(null).isEmpty() || !a.hash("").isEmpty()) throw new AssertionError("unexpected plaintext or empty value");
        System.out.println("DiagnosticHasher: deterministic per-run, keyed across runs, no plaintext passed");
    }
}
