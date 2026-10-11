package dev.reconcilekit;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuntimeSmokeTest extends PostgreSqlTestBase {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @Test void migrationReadWriteHealthAndOpenApi() throws Exception {
        jdbc.update("INSERT INTO runtime_probe(id,value) VALUES(1,'verified') ON CONFLICT(id) DO UPDATE SET value=EXCLUDED.value");
        assertEquals("verified", jdbc.queryForObject("SELECT value FROM runtime_probe WHERE id=1", String.class));
        var client = HttpClient.newHttpClient();
        for (String path : new String[]{"/actuator/health", "/v3/api-docs", "/swagger-ui/index.html"}) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path);
            assertFalse(response.body().isBlank());
            if (path.endsWith("health")) assertTrue(response.body().contains("UP"));
            if (path.endsWith("api-docs")) assertTrue(response.body().contains("openapi"));
        }
        jdbc.update("DELETE FROM runtime_probe WHERE id=1");
    }
}
