package br.com.fatura;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.TextStyle;
import java.util.*;

@Service @Transactional(readOnly = true)
class ExportImport {
    static final String[] HEADERS = {"titulo", "categoria", "origem", "valor", "dia_vencimento", "dias_lembrete", "mes_inicio"};
    private final Users users;
    private final Recurrences recurrences;
    ExportImport(Users users, Recurrences recurrences) { this.users = users; this.recurrences = recurrences; }
    record ImportResult(int imported, List<String> errors) {}

    // ------------------------------ CSV (portabilidade das recorrências) ------------------------------
    byte[] csv(UUID owner) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF'); // BOM: faz o Excel pt-BR abrir com acentos corretos
        sb.append(String.join(";", HEADERS)).append("\r\n");
        for (RecurringBill r : recurrences.findByOwnerId(owner))
            sb.append(csvCell(r.title)).append(';').append(csvCell(r.category)).append(';').append(csvCell(r.source == null ? "" : r.source)).append(';')
              .append(r.amount.toPlainString()).append(';').append(r.dueDay).append(';').append(r.reminderDays).append(';')
              .append(YearMonth.from(r.startMonth)).append("\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
    static String csvCell(String v) {
        if (v == null) v = "";
        if (v.indexOf(';') < 0 && v.indexOf('"') < 0 && v.indexOf('\r') < 0 && v.indexOf('\n') < 0) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    @Transactional(readOnly = false)
    ImportResult importCsv(UUID owner, byte[] content) {
        users.lockById(owner).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> rows = parseCsv(text);
        List<RecurringBill> fresh = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<String> c = rows.get(i);
            if (i == 0 && !c.isEmpty() && c.get(0).trim().equalsIgnoreCase(HEADERS[0])) continue; // cabeçalho
            if (c.isEmpty() || (c.size() == 1 && c.get(0).isBlank())) continue;                   // linha vazia
            try { fresh.add(rowToRecurring(owner, c)); }
            catch (IllegalArgumentException e) { errors.add("Linha " + (i + 1) + ": " + e.getMessage()); }
        }
        if (!fresh.isEmpty()) recurrences.saveAllAndFlush(fresh);
        return new ImportResult(fresh.size(), errors);
    }
    RecurringBill rowToRecurring(UUID owner, List<String> c) {
        if (c.size() < HEADERS.length) throw new IllegalArgumentException("colunas insuficientes (" + c.size() + "/" + HEADERS.length + ")");
        String title = c.get(0).trim(), category = c.get(1).trim(), source = c.get(2).trim();
        if (title.isEmpty() || title.length() > 120) throw new IllegalArgumentException("título inválido");
        if (category.isEmpty() || category.length() > 50) throw new IllegalArgumentException("categoria inválida");
        if (source.length() > 200) throw new IllegalArgumentException("origem longa demais");
        BigDecimal amount = parseAmount(c.get(3));
        if (amount == null) throw new IllegalArgumentException("valor inválido");
        if (amount.compareTo(new BigDecimal("0.01")) < 0 || amount.compareTo(new BigDecimal("999999999999.99")) > 0 || amount.stripTrailingZeros().scale() > 2)
            throw new IllegalArgumentException("valor fora do intervalo");
        int dueDay, reminderDays;
        try { dueDay = Integer.parseInt(c.get(4).trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException("dia de vencimento inválido"); }
        if (dueDay < 1 || dueDay > 31) throw new IllegalArgumentException("dia deve ser 1 a 31");
        try { reminderDays = Integer.parseInt(c.get(5).trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException("dias de lembrete inválidos"); }
        if (reminderDays < 0 || reminderDays > 30) throw new IllegalArgumentException("lembrete deve ser 0 a 30");
        LocalDate startMonth;
        try { startMonth = YearMonth.parse(c.get(6).trim()).atDay(1); } catch (DateTimeException e) { throw new IllegalArgumentException("mês inicial inválido (use AAAA-MM)"); }
        RecurringBill r = new RecurringBill();
        r.ownerId = owner; r.title = title; r.category = category; r.source = source;
        r.amount = amount; r.dueDay = dueDay; r.reminderDays = reminderDays; r.startMonth = startMonth;
        return r;
    }
    static BigDecimal parseAmount(String raw) {
        String v = raw == null ? "" : raw.trim().replace("R$", "").trim();
        if (v.isEmpty()) return null;
        if (v.indexOf(',') >= 0) v = v.replace(".", "").replace(',', '.'); // pt-BR: 1.800,00 -> 1800.00
        try { return new BigDecimal(v); } catch (NumberFormatException e) { return null; }
    }
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; } else inQuotes = false; }
                else cell.append(ch);
            } else {
                if (ch == '"') inQuotes = true;
                else if (ch == ';') { row.add(cell.toString()); cell.setLength(0); }
                else if (ch == '\r') { if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++; row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>(); }
                else if (ch == '\n') { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>(); }
                else cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }

    // ------------------------------ PDF (extrato do mês) ------------------------------
    byte[] pdf(AppUser user, YearMonth month, List<Bill> bills) {
        PDType1Font regular = PDType1Font.HELVETICA, bold = PDType1Font.HELVETICA_BOLD;
        NumberFormat money = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));
        List<Bill> ordered = new ArrayList<>(bills);
        ordered.sort(Comparator.comparing(b -> b.due));
        float left = 42, right = 553;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4); doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = 800;
            cs.beginText(); cs.setFont(bold, 16); cs.newLineAtOffset(left, y); cs.showText("Minhas Contas - Extrato Mensal"); cs.endText(); y -= 22;
            cs.beginText(); cs.setFont(regular, 10); cs.newLineAtOffset(left, y);
            cs.showText(fit(regular, 10, right - left, (user.name == null ? "" : user.name) + (user.email == null ? "" : " - " + user.email))); cs.endText(); y -= 16;
            cs.beginText(); cs.setFont(regular, 11); cs.newLineAtOffset(left, y);
            cs.showText("Mês de referência: " + month.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR")) + " de " + month.getYear()); cs.endText(); y -= 24;
            drawHeader(cs, bold, y); y -= 18;
            if (ordered.isEmpty()) { cs.beginText(); cs.setFont(regular, 10); cs.newLineAtOffset(left, y); cs.showText("Nenhuma conta neste mês."); cs.endText(); y -= 18; }
            for (Bill b : ordered) {
                if (y < 70) { cs.close(); page = new PDPage(PDRectangle.A4); doc.addPage(page); cs = new PDPageContentStream(doc, page); y = 800; drawHeader(cs, bold, y); y -= 18; }
                cs.beginText(); cs.setFont(regular, 10);
                cs.newLineAtOffset(left, y); cs.showText(fit(regular, 10, 150, b.title));
                cs.newLineAtOffset(205 - left, 0); cs.showText(fit(regular, 10, 125, b.source == null ? "" : b.source));
                cs.newLineAtOffset(345 - 205, 0); cs.showText(b.due.getDayOfMonth() + "/" + String.format("%02d", b.due.getMonthValue()));
                cs.newLineAtOffset(408 - 345, 0); cs.showText(b.paid ? "Pago" : (b.due.isBefore(LocalDate.now()) ? "Vencida" : "Em aberto"));
                cs.endText();
                String val = money.format(b.amount);
                cs.beginText(); cs.setFont(regular, 10); cs.newLineAtOffset(right - width(regular, 10, val), y); cs.showText(val); cs.endText();
                y -= 18;
            }
            cs.setLineWidth(0.8f); cs.moveTo(left, y + 12); cs.lineTo(right, y + 12); cs.stroke(); y -= 4;
            if (y < 70) { cs.close(); page = new PDPage(PDRectangle.A4); doc.addPage(page); cs = new PDPageContentStream(doc, page); y = 800; }
            BigDecimal total = ordered.stream().map(b -> b.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            cs.beginText(); cs.setFont(bold, 11); cs.newLineAtOffset(left, y); cs.showText("Total do mês (" + ordered.size() + (ordered.size() == 1 ? " conta)" : " contas)")); cs.endText();
            String val = money.format(total);
            cs.beginText(); cs.setFont(bold, 11); cs.newLineAtOffset(right - width(bold, 11, val), y); cs.showText(val); cs.endText(); y -= 24;
            cs.beginText(); cs.setFont(regular, 8); cs.newLineAtOffset(left, y); cs.showText("Documento de apoio. Pagamentos sao registrados separadamente no app."); cs.endText();
            cs.close(); doc.save(out); return out.toByteArray();
        } catch (IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Falha ao gerar o PDF"); }
    }
    static void drawHeader(PDPageContentStream cs, PDFont bold, float y) throws IOException {
        cs.beginText(); cs.setFont(bold, 9);
        cs.newLineAtOffset(42, y); cs.showText("CONTA");
        cs.newLineAtOffset(205 - 42, 0); cs.showText("EMPRESA");
        cs.newLineAtOffset(345 - 205, 0); cs.showText("VENC.");
        cs.newLineAtOffset(408 - 345, 0); cs.showText("STATUS");
        cs.endText();
        String h = "VALOR";
        cs.beginText(); cs.setFont(bold, 9); cs.newLineAtOffset(553 - width(bold, 9, h), y); cs.showText(h); cs.endText();
    }
    static float width(PDFont font, float size, String text) throws IOException { return font.getStringWidth(text) / 1000f * size; }
    static String fit(PDFont font, float size, float maxWidth, String text) throws IOException {
        if (text == null) text = "";
        if (width(font, size, text) <= maxWidth) return text;
        String t = text;
        while (!t.isEmpty() && width(font, size, t + "...") > maxWidth) t = t.substring(0, t.length() - 1);
        return t + "...";
    }
}
