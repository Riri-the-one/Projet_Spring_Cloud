package tp3;

import com.ticketflow.customerservice.controllers.ConfigDemoController;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Loopback-only test client: real Config Client and production RefreshScope controller.
 * No domain services, database, JWT configuration or Eureka registration. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(ConfigDemoController.class)
public class RefreshProbe {
    public static void main(String[] args) {
        SpringApplication.run(RefreshProbe.class, args);
    }
}
