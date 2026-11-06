package br.com.fatura;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillImportTest {
    @Mock Users users;
    @Mock Bills bills;
    BillImport importer;
    AppUser owner;
    @BeforeEach void setup() {
        importer = new BillImport(users, bills);
        owner = new AppUser();
    }
    Bill source(String date) {
        Bill b = new Bill(); b.ownerId = owner.id; b.title = "Energia"; b.category = "Energia";
        b.source = "Enel"; b.amount = new BigDecimal("186.42"); b.reminderDays = 3;
        b.due = LocalDate.parse(date); b.paid = true; b.paymentType = "PIX";
        b.paymentCode = "codigo-do-mes-anterior"; b.lastReminded = b.due;
        return b;
    }
    void owned(Bill... records) {
        when(users.lockById(owner.id)).thenReturn(Optional.of(owner));
        when(bills.findByOwnerIdOrderByDueAsc(owner.id)).thenReturn(List.of(records));
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    List<Bill> saved() {
        ArgumentCaptor<List<Bill>> captor = ArgumentCaptor.forClass((Class)List.class);
        verify(bills).saveAllAndFlush(captor.capture());
        return captor.getValue();
    }
    @Test void clampsMonthEndAndResetsPaymentWithoutChangingHistory() {
        Bill source = source("2026-01-31"); owned(source);
        var result = importer.copy(owner.id, new BillImport.Request("2026-02", List.of(source.id)));
        Bill copy = saved().getFirst();
        assertEquals(1, result.imported());
        assertEquals(LocalDate.of(2026, 2, 28), copy.due);
        assertEquals(source.amount, copy.amount); assertEquals(source.title, copy.title);
        assertEquals(source.category, copy.category); assertEquals(source.source, copy.source);
        assertEquals(3, copy.reminderDays); assertEquals(owner.id, copy.ownerId);
        assertFalse(copy.paid); assertEquals("NONE", copy.paymentType);
        assertNull(copy.paymentCode); assertNull(copy.lastReminded);
        assertEquals(source.id, copy.importSourceId);
        assertEquals(LocalDate.of(2026,2,1), copy.importMonth);
        assertNotEquals(source.id, copy.id);
        assertTrue(source.paid); assertEquals("codigo-do-mes-anterior", source.paymentCode);
        assertEquals(LocalDate.of(2026,1,31), source.due);
    }
    @Test void handlesLeapYear() {
        Bill source = source("2028-01-31"); owned(source);
        importer.copy(owner.id, new BillImport.Request("2028-02", List.of(source.id)));
        assertEquals(LocalDate.of(2028,2,29), saved().getFirst().due);
    }
    @Test void handlesYearChangeAndImportsUnpaidBillsToo() {
        Bill source = source("2026-12-10"); source.paid = false; owned(source);
        importer.copy(owner.id, new BillImport.Request("2027-01", List.of(source.id)));
        assertEquals(LocalDate.of(2027,1,10), saved().getFirst().due);
    }
    @Test void skipsRepeatedImportEvenIfCopyWasEditedOrPaid() {
        Bill source = source("2026-10-10"); Bill existing = source("2026-12-01");
        existing.importSourceId = source.id; existing.importMonth = LocalDate.of(2026,11,1);
        owned(source, existing);
        var result = importer.copy(owner.id, new BillImport.Request("2026-11", List.of(source.id,source.id)));
        assertEquals(0, result.imported()); assertEquals(1, result.skipped()); assertTrue(saved().isEmpty());
    }
    @Test void rejectsForeignOrMissingSourceBeforeWritingAnyBill() {
        Bill source = source("2026-10-10"); owned(source);
        assertThrows(ResponseStatusException.class, () -> importer.copy(owner.id,
                new BillImport.Request("2026-11", List.of(source.id, UUID.randomUUID()))));
        verify(bills, never()).saveAllAndFlush(any());
    }
    @Test void rejectsSourceOutsidePreviousMonth() {
        Bill source = source("2026-09-10"); owned(source);
        assertThrows(ResponseStatusException.class, () -> importer.copy(owner.id,
                new BillImport.Request("2026-11", List.of(source.id))));
        verify(bills, never()).saveAllAndFlush(any());
    }
    @Test void rejectsInvalidTargetBeforeLoadingRecords() {
        assertThrows(ResponseStatusException.class, () -> importer.copy(owner.id,
                new BillImport.Request("2026-13", List.of(UUID.randomUUID()))));
        verifyNoInteractions(users,bills);
    }
}
