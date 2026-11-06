package br.com.fatura;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mail.javamail.JavaMailSender;
import org.flywaydb.core.Flyway;
import java.sql.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL",matches=".+")
class RecurrenceDatabaseTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",()->System.getenv("TEST_DATABASE_URL"));
        props.add("spring.datasource.username",()->System.getenv("TEST_DATABASE_USER"));
        props.add("spring.datasource.password",()->System.getenv("TEST_DATABASE_PASSWORD"));
        props.add("app.mail.enabled",()->false);
    }
    @Autowired Users users; @Autowired Bills bills; @Autowired Recurrences recurrences;
    AppUser owner;
    @BeforeEach void setup() {
        owner=new AppUser();owner.name="Teste";owner.email=UUID.randomUUID()+"@example.com";owner.password="not-a-login-password";
        users.saveAndFlush(owner);
    }
    BillService service(String day) {
        return new BillService(users,bills,recurrences,Clock.fixed(Instant.parse(day+"T15:00:00Z"),ZoneId.of("America/Sao_Paulo")));
    }
    BillService.Input input(int day) { return new BillService.Input("Aluguel","Moradia",new BigDecimal("1800.00"),day,3,"Contrato"); }
    @Test void monthlyPaymentsAreIndependentAndListingIsIdempotent() {
        Bill january=service("2026-01-06").add(owner.id,input(31));
        service("2026-01-06").paid(owner.id,january.id);
        var records=service("2026-03-06").list(owner.id,"2026-03");
        assertEquals(3,records.size()); assertTrue(records.getFirst().paid);
        assertFalse(records.get(1).paid);assertEquals(LocalDate.of(2026,2,28),records.get(1).due);
        assertEquals(LocalDate.of(2026,3,31),records.get(2).due);
        assertEquals(3,service("2026-03-06").list(owner.id,"2026-03").size());
        service("2026-03-06").update(owner.id,records.get(2).id,input(10));
        assertEquals(LocalDate.of(2026,1,31),bills.findById(january.id).orElseThrow().due);
        assertEquals(LocalDate.of(2026,4,10),service("2026-03-06").list(owner.id,"2026-04").getLast().due);
    }
    @Test void futureEditPreservesUnvisitedIntermediateMonths() {
        var service=service("2026-01-06"); service.add(owner.id,input(31));
        Bill may=service.list(owner.id,"2026-05").getLast();
        service.update(owner.id,may.id,new BillService.Input("Aluguel","Moradia",new BigDecimal("2000.00"),10,3,"Contrato"));
        Bill april=service.list(owner.id,"2026-04").stream().filter(b->b.billingMonth.equals(LocalDate.of(2026,4,1))).findFirst().orElseThrow();
        assertEquals(new BigDecimal("1800.00"),april.amount);assertEquals(LocalDate.of(2026,4,30),april.due);
        assertEquals(new BigDecimal("2000.00"),service.list(owner.id,"2026-06").getLast().amount);
    }
    @Test void deleteRemovesSeriesAndAllOccurrencesAndDoesNotRegenerate() {
        Bill first=service("2026-01-06").add(owner.id,input(10));
        service("2026-03-06").list(owner.id,"2027-01");
        service("2026-03-06").delete(owner.id,first.id);
        assertTrue(service("2026-03-06").list(owner.id,"2027-01").isEmpty());
        assertTrue(recurrences.findByOwnerId(owner.id).isEmpty());
    }
    @Test void otherOwnerCannotChangeOrDeleteOccurrence() {
        Bill first=service("2026-01-06").add(owner.id,input(10));
        AppUser other=new AppUser();other.email=UUID.randomUUID()+"@example.com";other.name="Outro";other.password="test";users.saveAndFlush(other);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service("2026-01-06").delete(other.id,first.id));
        assertTrue(service("2026-01-06").list(other.id,"2026-01").isEmpty());
        assertTrue(bills.findById(first.id).isPresent());
    }
    @Test void reminderPreparesNextMonthAndDoesNotResendForSameDate() {
        var service=service("2026-01-30");
        Bill first=service.add(owner.id,input(1));service.paid(owner.id,first.id);
        JavaMailSender mail=mock(JavaMailSender.class);
        service.remind(owner.id,mail,"app@example.com");
        verify(mail,times(1)).send(any(org.springframework.mail.SimpleMailMessage.class));
        service.remind(owner.id,mail,"app@example.com");
        verify(mail,times(1)).send(any(org.springframework.mail.SimpleMailMessage.class));
        assertEquals(LocalDate.of(2026,2,1),bills.findByOwnerIdOrderByDueAsc(owner.id).getLast().due);
    }
    @Test void migrationPreservesImportedHistoryAndSeparatesMonthCollisions() throws Exception {
        String schema="recurrence_upgrade_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("TEST_DATABASE_URL"),user=System.getenv("TEST_DATABASE_USER"),password=System.getenv("TEST_DATABASE_PASSWORD");
        try(Connection connection=DriverManager.getConnection(url,user,password)) {
            var previous=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("2").load();
            previous.migrate();connection.setSchema(schema);
            try(Statement sql=connection.createStatement()) {
                sql.execute("insert into app_user values ('00000000-0000-0000-0000-000000000001','Teste','legacy@example.com','test')");
                sql.execute("insert into bill(id,owner_id,title,category,amount,due,paid,reminder_days,payment_type,import_source_id,import_month) values "
                    +"('00000000-0000-0000-0000-000000000101','00000000-0000-0000-0000-000000000001','Conta','Outros',100,'2026-01-31',true,3,'NONE',null,null),"
                    +"('00000000-0000-0000-0000-000000000102','00000000-0000-0000-0000-000000000001','Conta','Outros',120,'2026-02-28',false,3,'NONE','00000000-0000-0000-0000-000000000101','2026-02-01'),"
                    +"('00000000-0000-0000-0000-000000000103','00000000-0000-0000-0000-000000000001','Conta','Outros',130,'2026-01-15',false,3,'NONE','00000000-0000-0000-0000-000000000101','2026-03-01')");
            }
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().migrate();
            try(Statement sql=connection.createStatement()) {
                var count=sql.executeQuery("select count(*) from bill");count.next();assertEquals(3,count.getInt(1));
                var series=sql.executeQuery("select count(*) from recurring_bill");series.next();assertEquals(2,series.getInt(1));
                var root=sql.executeQuery("select due_day,amount from recurring_bill where id='00000000-0000-0000-0000-000000000101'");root.next();assertEquals(31,root.getInt(1));assertEquals(new BigDecimal("120.00"),root.getBigDecimal(2));
                var paid=sql.executeQuery("select paid from bill where id='00000000-0000-0000-0000-000000000101'");paid.next();assertTrue(paid.getBoolean(1));
                var removed=sql.executeQuery("select count(*) from information_schema.columns where table_schema='"+schema+"' and table_name='bill' and column_name in ('payment_type','payment_code')");removed.next();assertEquals(0,removed.getInt(1));
            }
        } finally {
            try(Connection connection=DriverManager.getConnection(url,user,password);Statement sql=connection.createStatement()) {
                sql.execute("drop schema if exists "+schema+" cascade");
            }
        }
    }
}
