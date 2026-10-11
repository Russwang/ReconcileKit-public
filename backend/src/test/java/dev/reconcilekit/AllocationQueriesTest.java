package dev.reconcilekit;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import dev.reconcilekit.allocation.*;
import dev.reconcilekit.api.ApiException;
import dev.reconcilekit.query.LedgerQueries;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AllocationQueriesTest extends PostgreSqlTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired AllocationService service;
    @Autowired LedgerQueries queries;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    LedgerFixture fixture;
    @BeforeEach void reset() { fixture=new LedgerFixture(jdbc); }
    static AllocationCommand command(long payment,long invoice,String amount) {
        return new AllocationCommand(payment,List.of(new AllocationCommand.Target(invoice,new BigDecimal(amount))));
    }
    @Test void singleLockedAllocationAndIndependentStatuses() {
        long invoice=fixture.invoice("I","100"), payment=fixture.payment("P","200");
        var applied=service.allocate(command(payment,invoice,"60"));
        assertEquals("40.00",applied.invoices().getFirst().remaining());
        assertEquals("PARTIALLY_PAID",applied.invoices().getFirst().status());
        assertEquals("140.00",applied.payment().remaining());
        assertEquals("PARTIALLY_ALLOCATED",applied.payment().status());
        assertEquals(1,queries.payments(0,50,true).items().size());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM allocations",Integer.class));
        service.allocate(command(payment,invoice,"40"));
        assertEquals("PAID",queries.invoice(invoice).status());
        assertEquals("PARTIALLY_ALLOCATED",queries.payment(payment).status());
        assertEquals("100.00",queries.payment(payment).remaining());
        assertEquals(1,queries.payments(0,50,true).items().size());
        fixture.invariants();
    }
    @Test void eitherParentBalanceCanRejectAndMissingParentIs404() {
        long invoice=fixture.invoice("I","50"), payment=fixture.payment("P","40");
        assertEquals(409,assertThrows(ApiException.class,()->service.allocate(command(payment,invoice,"45"))).status());
        long rich=fixture.payment("R","100");
        assertEquals(409,assertThrows(ApiException.class,()->service.allocate(command(rich,invoice,"60"))).status());
        assertEquals(404,assertThrows(ApiException.class,()->service.allocate(command(payment,999,"1"))).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM allocations",Integer.class));
        fixture.invariants();
    }
    @Test void queriesAreReadOnlyPaginatedAndErrorsCarryCorrelation() throws Exception {
        fixture.invoice("I1","100");fixture.invoice("I2","100");fixture.payment("P","100");
        HttpClient client=HttpClient.newHttpClient();
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/invoices?page=1&size=1")).header("X-Request-ID","m03-check").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
        var body=mapper.readTree(response.body());
        assertEquals("I2",body.get("items").get(0).get("externalInvoiceId").asString());
        assertEquals("100.00",body.get("items").get(0).get("remaining").asString());
        var error=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/payments?size=101")).header("X-Request-ID","m03-error").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(400,error.statusCode());
        assertEquals("m03-error",mapper.readTree(error.body()).get("request_id").asString());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM allocations",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_events",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records",Integer.class));
    }
    @Test void amountJsonTypesAndStrictDatesAreValidatedBeforeWrites() throws Exception {
        var node=mapper.readTree("{\"payment_id\":1,\"allocations\":[{\"invoice_id\":1,\"amount\":60}]}");
        assertEquals(400,assertThrows(ApiException.class,()->AllocationCommand.parse(node,1)).status());
        var invalid=mapper.readTree("{\"date\":\"2025-02-29\"}");
        assertEquals(422,assertThrows(ApiException.class,()->dev.reconcilekit.api.JsonInput.date(invalid,"date")).status());
    }
}
