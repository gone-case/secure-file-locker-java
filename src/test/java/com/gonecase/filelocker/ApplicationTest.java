package com.gonecase.filelocker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

/** Boots the full app (no AWS calls are made at startup) and checks auth wiring end to end. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "locker.api-key=test-key")
class ApplicationTest {

    @Autowired TestRestTemplate http;

    @Test
    void healthIsPublic() {
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void filesRequireApiKey() {
        assertThat(http.getForEntity("/files", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
