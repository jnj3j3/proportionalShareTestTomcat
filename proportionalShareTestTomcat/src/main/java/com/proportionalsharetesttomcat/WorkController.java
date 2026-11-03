package com.proportionalsharetesttomcat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkController {

    @GetMapping("/work")
    public String work() {
        long end = System.nanoTime() + 20_000_000L;
        long x = 0;
        while (System.nanoTime() < end) {
            x += x * 31 + 7;
        }
        return "hello " + x + " tid=" + currentNativeTid();
    }

    private static String currentNativeTid() {
        try {
            return java.nio.file.Path.of("/proc/thread-self")
                    .toRealPath()
                    .getFileName()
                    .toString();
        } catch (Exception e) {
            return "?";
        }
    }
}
