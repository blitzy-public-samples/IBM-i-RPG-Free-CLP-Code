package com.democorp.customermaster;

import com.democorp.customermaster.config.AppProperties;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point of the Customer Master jar ({@code app.jar}): the REST API by default, and the
 * test-data generator CLI under profile {@code generator}, which runs with no web server.
 *
 * <p>{@code AppProperties} is registered here and nowhere else. The application declares no
 * configuration-properties scan, so every other properties class is bound only in a context that
 * holds the class registering it.
 *
 * <p>The OpenAPI annotations give the springdoc document its title, version and same-origin server
 * URL, and declare the HTTP Basic security scheme {@code basicAuth}.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@OpenAPIDefinition(info = @Info(title = "Customer Master API", version = "1"), servers = @Server(url = "/"))
@SecurityScheme(name = "basicAuth", type = SecuritySchemeType.HTTP, scheme = "basic")
public class CustomerMasterApplication {

    /**
     * Starts the application. A context without a web server (the generator) has finished its work
     * when {@code run} returns, so it is closed and the JVM exits with the code its
     * {@code ExitCodeGenerator} beans report (0 when none reports another).
     *
     * @param args command-line arguments, passed to Spring Boot unchanged
     */
    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(CustomerMasterApplication.class, args);
        if (!(ctx instanceof WebServerApplicationContext)) {
            System.exit(SpringApplication.exit(ctx));
        }
    }
}
