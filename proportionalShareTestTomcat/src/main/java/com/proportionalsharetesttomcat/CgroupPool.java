package com.proportionalsharetesttomcat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CgroupPool {

    private CgroupPool() {}

    public static void joinCurrentThread(String cgroupDir) throws IOException {
        Path self = Path.of("/proc/thread-self").toRealPath();
        String tid = self.getFileName().toString();
        Files.writeString(Path.of(cgroupDir, "cgroup.threads"), tid);
    }
}
