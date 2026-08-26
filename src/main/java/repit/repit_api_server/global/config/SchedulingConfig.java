package repit.repit_api_server.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** SSE 하트비트처럼 요청 밖에서 도는 작업을 위한 스케줄러. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
