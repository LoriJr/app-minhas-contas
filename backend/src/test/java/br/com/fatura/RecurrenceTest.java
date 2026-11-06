package br.com.fatura;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecurrenceTest {
    @Mock Users users; @Mock Bills bills; @Mock Recurrences recurrences;
    BillService service; AppUser owner; RecurringBill recurring;
    @BeforeEach void setup() {
        service=new BillService(users,bills,recurrences,Clock.fixed(Instant.parse("2026-02-15T15:00:00Z"),ZoneId.of("America/Sao_Paulo")));
        owner=new AppUser(); recurring=new RecurringBill(); recurring.ownerId=owner.id;
        recurring.title="Aluguel"; recurring.category="Moradia"; recurring.amount=new BigDecimal("1800.00");
        recurring.dueDay=31; recurring.reminderDays=3; recurring.startMonth=LocalDate.of(2026,1,1);
    }
    void lock() { when(users.lockById(owner.id)).thenReturn(Optional.of(owner)); }
    @Test void repeatedMonthsClampButDoNotDriftFromDay31() {
        assertEquals(LocalDate.of(2026,2,28),BillService.due(YearMonth.of(2026,2),31));
        assertEquals(LocalDate.of(2028,2,29),BillService.due(YearMonth.of(2028,2),31));
        assertEquals(LocalDate.of(2026,3,31),BillService.due(YearMonth.of(2026,3),31));
    }
    @Test void projectsElapsedAndRequestedMonthsWithoutDuplicatingPaidHistory() {
        lock(); Bill january=service.createOccurrence(recurring,YearMonth.of(2026,1)); january.paid=true;
        when(recurrences.findByOwnerId(owner.id)).thenReturn(List.of(recurring));
        when(bills.findByOwnerIdOrderByDueAsc(owner.id)).thenReturn(List.of(january));
        List<Bill> result=service.list(owner.id,"2026-03");
        assertEquals(3,result.size()); assertTrue(result.getFirst().paid);
        assertFalse(result.get(1).paid); assertFalse(result.get(2).paid);
        assertEquals(LocalDate.of(2026,2,28),result.get(1).due);
        assertEquals(LocalDate.of(2026,3,31),result.get(2).due);
    }
    @Test void newAccountStartsInCurrentMonth() {
        lock(); var input=new BillService.Input("Internet","Internet",new BigDecimal("99.90"),10,3,"Claro");
        Bill first=service.add(owner.id,input);
        assertEquals(LocalDate.of(2026,2,1),first.billingMonth);
        assertEquals(LocalDate.of(2026,2,10),first.due); assertFalse(first.paid);
    }
    @Test void paymentDoesNotAffectFollowingMonth() {
        lock(); Bill feb=service.createOccurrence(recurring,YearMonth.of(2026,2));
        Bill march=service.createOccurrence(recurring,YearMonth.of(2026,3));
        when(bills.findByIdAndOwnerId(feb.id,owner.id)).thenReturn(Optional.of(feb));
        when(recurrences.findByIdAndOwnerId(recurring.id,owner.id)).thenReturn(Optional.of(recurring));
        service.paid(owner.id,feb.id); assertTrue(feb.paid); assertFalse(march.paid);
    }
    @Test void editsPreservePastAndPaidFutureWhileUpdatingOpenFuture() {
        lock(); Bill jan=service.createOccurrence(recurring,YearMonth.of(2026,1));
        Bill feb=service.createOccurrence(recurring,YearMonth.of(2026,2));
        Bill march=service.createOccurrence(recurring,YearMonth.of(2026,3));
        Bill april=service.createOccurrence(recurring,YearMonth.of(2026,4)); april.paid=true;
        when(bills.findByIdAndOwnerId(feb.id,owner.id)).thenReturn(Optional.of(feb));
        when(bills.findByOwnerIdOrderByDueAsc(owner.id)).thenReturn(List.of(jan,feb,march,april));
        when(recurrences.findByIdAndOwnerId(recurring.id,owner.id)).thenReturn(Optional.of(recurring));
        service.update(owner.id,feb.id,new BillService.Input("Aluguel","Moradia",new BigDecimal("1900.00"),10,3,"Proprietário"));
        assertEquals(new BigDecimal("1800.00"),jan.amount);
        assertEquals(new BigDecimal("1900.00"),feb.amount); assertEquals(new BigDecimal("1900.00"),march.amount);
        assertEquals(new BigDecimal("1800.00"),april.amount); assertTrue(april.paid);
        assertEquals(LocalDate.of(2026,3,10),march.due); assertEquals(10,recurring.dueDay);
    }
    @Test void deletingAnOccurrenceDeletesItsWholeSeries() {
        lock(); Bill feb=service.createOccurrence(recurring,YearMonth.of(2026,2));
        when(bills.findByIdAndOwnerId(feb.id,owner.id)).thenReturn(Optional.of(feb));
        when(recurrences.findByIdAndOwnerId(recurring.id,owner.id)).thenReturn(Optional.of(recurring));
        service.delete(owner.id,feb.id); verify(bills).deleteByRecurringId(recurring.id); verify(recurrences).delete(recurring);
    }
    @Test void foreignIdIsRejectedBeforeEditingOrDeleting() {
        lock(); UUID id=UUID.randomUUID(); when(bills.findByIdAndOwnerId(id,owner.id)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class,()->service.delete(owner.id,id)); verifyNoInteractions(recurrences);
    }
    @Test void invalidMonthIsRejectedBeforeDatabaseAccess() {
        assertThrows(ResponseStatusException.class,()->service.list(owner.id,"2026-13")); verifyNoInteractions(users,bills,recurrences);
    }
}
