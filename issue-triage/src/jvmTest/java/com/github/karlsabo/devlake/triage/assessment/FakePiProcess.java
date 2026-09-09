package com.github.karlsabo.devlake.triage.assessment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class FakePiProcess {
    private FakePiProcess() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "assessment" -> assess(args);
            case "sleep" -> sleep(args);
            default -> throw new IllegalArgumentException("Unknown fake pi mode: " + args[0]);
        }
    }

    private static void assess(String[] args) throws Exception {
        Files.write(Path.of(args[1]), Arrays.asList(args).subList(4, args.length));
        Files.copy(System.in, Path.of(args[2]));
        System.out.print(Files.readString(Path.of(args[3])));
    }

    private static void sleep(String[] args) throws Exception {
        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        System.in.transferTo(java.io.OutputStream.nullOutputStream());
        Thread.sleep(30_000);
    }
}
