package com.proportionalsharetesttomcat;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.ProtocolHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.boot.tomcat.TomcatWebServerFactory;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@Configuration
@Profile("split")
public class TomcatPoolsConfig {

    @Value("${app.cgroup.base:/sys/fs/cgroup/system.slice/app.service}")
    private String cgroupBase;

    @Value("${app.user.port:8080}")
    private int userPort;

    @Value("${app.admin.port:8081}")
    private int adminPort;

    @Value("${app.user.poolSize:50}")
    private int userPoolSize;

    @Value("${app.admin.poolSize:5}")
    private int adminPoolSize;

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatPools() {
        ThreadFactory userFactory  = new CgroupThreadFactory(cgroupBase + "/user-pool",  "user-worker");
        ThreadFactory adminFactory = new CgroupThreadFactory(cgroupBase + "/admin-pool", "admin-worker");

        TomcatProtocolHandlerCustomizer<ProtocolHandler> userExec =
                ph -> ph.setExecutor(Executors.newFixedThreadPool(userPoolSize, userFactory));

        return factory -> {
            factory.setPort(userPort);
            factory.addProtocolHandlerCustomizers(userExec);

            Connector admin = new Connector(TomcatWebServerFactory.DEFAULT_PROTOCOL);
            admin.setPort(adminPort);
            admin.getProtocolHandler()
                 .setExecutor(Executors.newFixedThreadPool(adminPoolSize, adminFactory));
            factory.addAdditionalConnectors(admin);
        };
    }
}
