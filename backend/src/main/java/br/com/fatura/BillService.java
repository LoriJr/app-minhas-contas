package br.com.fatura;

import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;

@Service
@Transactional
class BillService {
    record Input(@NotBlank @Size(max=120) String title,
                 @NotBlank @Size(max=50) String category,
                 @NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal amount,
                 @Min(1) @Max(31) int dueDay,
                 @Min(0) @Max(30) int reminderDays,
                 @Size(max=200) String source) {}
    private final Users users;
    private final Bills bills;
    private final Recurrences recurrences;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    BillService(Users users, Bills bills, Recurrences recurrences) {
        this(users, bills, recurrences, Clock.system(ZoneId.of("America/Sao_Paulo")));
    }
    BillService(Users users, Bills bills, Recurrences recurrences, Clock clock) {
        this.users = users; this.bills = bills; this.recurrences = recurrences; this.clock = clock;
    }
    void lock(UUID owner) { users.lockById(owner).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); }
    static YearMonth month(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw new DateTimeException("format");
            YearMonth month = YearMonth.parse(value);
            if (month.getYear() < 1900 || month.getYear() > 9999) throw new DateTimeException("range");
            return month;
        } catch (DateTimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Mês inválido"); }
    }
    YearMonth current() { return YearMonth.now(clock); }
    static LocalDate due(YearMonth month, int day) { return month.atDay(Math.min(day,month.lengthOfMonth())); }
    static void details(RecurringBill r, Input i) {
        r.title=i.title().trim(); r.category=i.category(); r.amount=i.amount();
        r.dueDay=i.dueDay(); r.reminderDays=i.reminderDays(); r.source=i.source();
    }
    static void snapshot(Bill b, RecurringBill r) {
        b.title=r.title; b.category=r.category; b.source=r.source; b.amount=r.amount;
        b.due=due(YearMonth.from(b.billingMonth),r.dueDay); b.dueDay=r.dueDay; b.reminderDays=r.reminderDays;
    }
    Bill createOccurrence(RecurringBill r, YearMonth month) {
        Bill b=new Bill(); b.ownerId=r.ownerId; b.recurringId=r.id; b.billingMonth=month.atDay(1);
        snapshot(b,r); return b;
    }
    public Bill add(UUID owner, Input input) {
        lock(owner); RecurringBill r=new RecurringBill(); r.ownerId=owner; r.startMonth=current().atDay(1);
        details(r,input); recurrences.saveAndFlush(r);
        Bill b=createOccurrence(r,current()); bills.saveAndFlush(b); return b;
    }
    public List<Bill> list(UUID owner, String target) {
        YearMonth requested = month(target); lock(owner); return materialize(owner, requested);
    }
    List<Bill> materialize(UUID owner, YearMonth requested) {
        List<RecurringBill> series=recurrences.findByOwnerId(owner);
        List<Bill> records=new ArrayList<>(bills.findByOwnerIdOrderByDueAsc(owner));
        Map<UUID,RecurringBill> byId=new HashMap<>(); Set<String> exists=new HashSet<>();
        series.forEach(r -> byId.put(r.id,r));
        records.forEach(b -> exists.add(b.recurringId+":"+b.billingMonth));
        List<Bill> fresh=new ArrayList<>();
        for(RecurringBill r:series) {
            // Catch up elapsed months after server downtime, so unpaid bills remain visible.
            for(YearMonth m=YearMonth.from(r.startMonth); !m.isAfter(current()); m=m.plusMonths(1)) {
                ensure(r,m,exists,fresh);
            }
            if(!requested.isBefore(YearMonth.from(r.startMonth))) ensure(r,requested,exists,fresh);
        }
        if(!fresh.isEmpty()) { bills.saveAllAndFlush(fresh); records.addAll(fresh); }
        records.forEach(b -> b.dueDay=byId.get(b.recurringId).dueDay);
        records.sort(Comparator.comparing(b -> b.due)); return records;
    }
    void ensure(RecurringBill r, YearMonth month, Set<String> exists, List<Bill> fresh) {
        if(exists.add(r.id+":"+month.atDay(1))) fresh.add(createOccurrence(r,month));
    }
    Bill owned(UUID owner, UUID id) {
        return bills.findByIdAndOwnerId(id,owner).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    public Bill update(UUID owner, UUID id, Input input) {
        lock(owner); Bill selected=owned(owner,id);
        RecurringBill r=recurrences.findByIdAndOwnerId(selected.recurringId,owner).orElseThrow();
        // Snapshot intervening months before a change made in a future month.
        List<Bill> before=bills.findByOwnerIdOrderByDueAsc(owner);
        Set<String> exists=new HashSet<>(); before.forEach(b -> exists.add(b.recurringId+":"+b.billingMonth));
        List<Bill> intermediate=new ArrayList<>();
        for(YearMonth m=current(); m.isBefore(YearMonth.from(selected.billingMonth)); m=m.plusMonths(1)) {
            if(!m.isBefore(YearMonth.from(r.startMonth))) ensure(r,m,exists,intermediate);
        }
        if(!intermediate.isEmpty()) bills.saveAllAndFlush(intermediate);
        details(r,input); recurrences.saveAndFlush(r);
        // Keep past history and other paid months unchanged. Update selected and future unpaid months.
        List<Bill> changes=new ArrayList<>();
        for(Bill b:bills.findByOwnerIdOrderByDueAsc(owner)) {
            if(!b.recurringId.equals(r.id)) continue;
            if(b.id.equals(selected.id) || (!b.paid && b.billingMonth.isAfter(selected.billingMonth))) {
                snapshot(b,r); changes.add(b);
            }
        }
        bills.saveAllAndFlush(changes); snapshot(selected,r); return selected;
    }
    public Bill paid(UUID owner, UUID id) {
        lock(owner); Bill b=owned(owner,id); b.paid=!b.paid;
        b.dueDay=recurrences.findByIdAndOwnerId(b.recurringId,owner).orElseThrow().dueDay;
        bills.saveAndFlush(b); return b;
    }
    public void delete(UUID owner, UUID id) {
        lock(owner); Bill b=owned(owner,id);
        RecurringBill r=recurrences.findByIdAndOwnerId(b.recurringId,owner).orElseThrow();
        bills.deleteByRecurringId(r.id); bills.flush(); recurrences.delete(r);
    }
    public void remind(UUID owner, JavaMailSender mail, String from) {
        lock(owner); LocalDate today=LocalDate.now(clock);
        // Next month is needed for reminders before a day-1 due date.
        List<Bill> records=materialize(owner,current().plusMonths(1));
        AppUser user=users.findById(owner).orElseThrow();
        for(Bill b:records) {
            if(b.paid || b.due.minusDays(b.reminderDays).isAfter(today) || today.equals(b.lastReminded)) continue;
            try {
                SimpleMailMessage message=new SimpleMailMessage(); message.setFrom(from); message.setTo(user.email);
                message.setSubject((b.due.isBefore(today)?"Conta vencida: ":"Lembrete: ")+b.title);
                message.setText("Sua conta "+b.title+" de R$ "+b.amount+" vence em "+b.due+". Confira os dados no app.");
                mail.send(message); b.lastReminded=today; bills.save(b);
            } catch(MailException e) {
                System.getLogger("reminders").log(System.Logger.Level.WARNING,"Falha no envio; nova tentativa no próximo ciclo.");
            }
        }
    }
}
