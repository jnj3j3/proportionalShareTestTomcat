package com.proportionalsharetesttomcat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
public class BurnController {

    private static final Logger log = LoggerFactory.getLogger(BurnController.class);

    @Value("${app.cpuHog.cgroup:/sys/fs/cgroup/system.slice/app.service}")
    private String outsideCgroupDir;

    private final List<Thread> burners = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();

    @PostMapping("/burn/start")
    public String start(@RequestParam(defaultValue = "1") int threads,
                        @RequestParam(defaultValue = "true") boolean outside) {
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> burn(outside), "cpu-hog-" + counter.getAndIncrement());
            t.setDaemon(true);
            t.start();
            burners.add(t);
        }
        return "started=" + threads + " outside=" + outside + " total=" + burners.size() + "\n";
    }

    @PostMapping("/burn/stop")
    public String stop() {
        int n = burners.size();
        burners.forEach(Thread::interrupt);
        burners.clear();
        return "stopped=" + n + "\n";
    }

    private void burn(boolean outside) {
        if (outside) {
            try {
                CgroupPool.joinCurrentThread(outsideCgroupDir);
                log.info("burner joined {}", outsideCgroupDir);
            } catch (Exception e) {
                log.warn("burner failed to move cgroup {}: {}", outsideCgroupDir, e.toString());
            }
        }
        long x = 1;
        while (!Thread.currentThread().isInterrupted()) {
            for (int i = 0; i < 10_000; i++) x += x * 31 + 7;
        }
        log.info("burner exit, final x={}", x);
    }
}
