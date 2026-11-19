package br.com.fatura;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExportImportTest {
    @Mock Users users; @Mock Recurrences recurrences;
    ExportImport service; AppUser owner;
    @BeforeEach void setup() {
        service = new ExportImport(users, recurrences);
        owner = new AppUser(); owner.name = "Marina Silva"; owner.email = "marina@exemplo.com";
    }
    RecurringBill recurring() {
        RecurringBill r = new RecurringBill(); r.ownerId = owner.id;
        r.title = "Água e esgoto"; r.category = "Água"; r.source = "Sabesp - São Paulo";
        r.amount = new BigDecimal("92.35"); r.dueDay = 18; r.reminderDays = 3; r.startMonth = LocalDate.of(2026, 1, 1);
        return r;
    }
    @Test void csvRoundTripRecreatesTheRecurrence() {
        RecurringBill source = recurring();
        when(recurrences.findByOwnerId(owner.id)).thenReturn(List.of(source));
        byte[] csv = service.csv(owner.id);
        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("\uFEFF"), "deve começar com BOM");
        assertTrue(text.contains("titulo;categoria;origem;valor;dia_vencimento;dias_lembrete;mes_inicio"));
        assertTrue(text.contains("Água e esgoto"), "acentos preservados");
        assertTrue(text.contains(";92.35;"), "valor em formato plano");
        assertTrue(text.contains(";2026-01"), "mês inicial");

        when(users.lockById(owner.id)).thenReturn(Optional.of(owner));
        ExportImport.ImportResult result = service.importCsv(owner.id, csv);
        assertEquals(1, result.imported());
        assertTrue(result.errors().isEmpty(), () -> "sem erros, mas houve: " + result.errors());

        ArgumentCaptor<List<RecurringBill>> saved = ArgumentCaptor.forClass(List.class);
        verify(recurrences).saveAllAndFlush(saved.capture());
        RecurringBill restored = saved.getValue().getFirst();
        assertEquals(owner.id, restored.ownerId);
        assertEquals("Água e esgoto", restored.title);
        assertEquals("Água", restored.category);
        assertEquals("Sabesp - São Paulo", restored.source);
        assertEquals(0, new BigDecimal("92.35").compareTo(restored.amount));
        assertEquals(18, restored.dueDay);
        assertEquals(3, restored.reminderDays);
        assertEquals(LocalDate.of(2026, 1, 1), restored.startMonth);
    }
    @Test void importSkipsInvalidRowsAndReportsLineNumber() {
        when(users.lockById(owner.id)).thenReturn(Optional.of(owner));
        String csv = "titulo;categoria;origem;valor;dia_vencimento;dias_lembrete;mes_inicio\r\n"
                + "Internet;Internet;Claro;99.90;12;3;2026-02\r\n"
                + "Sem dia;Outros;;10.00;99;3;2026-02\r\n";
        ExportImport.ImportResult result = service.importCsv(owner.id, csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.imported());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().getFirst().startsWith("Linha 3"), () -> "erro aponta a linha certa: " + result.errors());
    }
    @Test void acceptsBrazilianNumberFormatFromHandEditedFiles() {
        assertEquals(0, new BigDecimal("1800.00").compareTo(ExportImport.parseAmount("1.800,00")));
        assertEquals(0, new BigDecimal("186.42").compareTo(ExportImport.parseAmount("R$ 186,42")));
        assertEquals(0, new BigDecimal("99.90").compareTo(ExportImport.parseAmount("99.90")));
        assertNull(ExportImport.parseAmount(""));
    }
    @Test void pdfIsAValidDocumentWithTheMonthStatement() throws Exception {
        Bill bill = new Bill();
        bill.title = "Aluguel da casa"; bill.source = "Contrato de aluguel";
        bill.amount = new BigDecimal("1800.00"); bill.due = LocalDate.of(2026, 2, 5); bill.paid = true;
        byte[] pdf = service.pdf(owner, YearMonth.of(2026, 2), List.of(bill));
        assertEquals("%PDF", new String(Arrays.copyOfRange(pdf, 0, 4), StandardCharsets.ISO_8859_1));
        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Extrato"), text);
            assertTrue(text.contains("Aluguel da casa"), text);
            assertTrue(text.contains("1.800,00"), "valor formatado em BRL: " + text);
            assertTrue(text.contains("Total do mês"), text);
        }
    }
}
