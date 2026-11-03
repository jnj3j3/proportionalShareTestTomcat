package com.proportionalsharetesttomcat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class CgroupThreadFactory implements ThreadFactory {

    private static final Logger log = LoggerFactory.getLogger(CgroupThreadFactory.class);

    private final String cgroupDir;
    private final String namePrefix;
    private final AtomicInteger counter = new AtomicInteger(1);

    public CgroupThreadFactory(String cgroupDir, String namePrefix) {
        this.cgroupDir = cgroupDir;
        this.namePrefix = namePrefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        return new Thread(() -> {
            try {
                CgroupPool.joinCurrentThread(cgroupDir);
            } catch (Exception e) {
                log.warn("failed to join cgroup {}: {}", cgroupDir, e.toString());
            }
            r.run();
        }, namePrefix + "-" + counter.getAndIncrement());
    }
}
