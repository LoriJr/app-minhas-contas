package br.com.fatura;

import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

@Service
class BillImport {
    record Request(@NotBlank @Pattern(regexp="[0-9]{4}-(0[1-9]|1[0-2])") String targetMonth,
                   @NotEmpty @Size(max=200) List<@NotNull UUID> sourceIds) {}
    record Result(int imported, int skipped) {}
    private final Users users;
    private final Bills bills;
    BillImport(Users users, Bills bills) { this.users = users; this.bills = bills; }

    @Transactional
    public Result copy(UUID ownerId, Request request) {
        YearMonth target;
        try { target = YearMonth.parse(request.targetMonth()); }
        catch (DateTimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mês inválido"); }
        if (target.getYear() < 1900 || target.getYear() > 9999)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mês fora do intervalo permitido");
        // Serialize imports for one owner; the unique index is an additional safeguard.
        users.lockById(ownerId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<Bill> owned = bills.findByOwnerIdOrderByDueAsc(ownerId);
        Map<UUID, Bill> sources = new HashMap<>();
        owned.stream().filter(b -> YearMonth.from(b.due).equals(target.minusMonths(1)))
                .forEach(b -> sources.put(b.id, b));
        Set<UUID> selected = new LinkedHashSet<>(request.sourceIds());
        // Validate the whole request before inserting anything, including ownership and source month.
        if (!sources.keySet().containsAll(selected))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione apenas suas contas do mês anterior");
        Set<UUID> alreadyImported = new HashSet<>();
        owned.stream().filter(b -> target.atDay(1).equals(b.importMonth))
                .map(b -> b.importSourceId).filter(Objects::nonNull).forEach(alreadyImported::add);
        List<Bill> copies = new ArrayList<>();
        int skipped = 0;
        for (UUID id : selected) {
            if (alreadyImported.contains(id)) { skipped++; continue; }
            Bill source = sources.get(id);
            Bill copy = new Bill();
            copy.ownerId = ownerId;
            copy.title = source.title;
            copy.category = source.category;
            copy.source = source.source;
            copy.amount = source.amount;
            copy.reminderDays = source.reminderDays;
            copy.due = target.atDay(Math.min(source.due.getDayOfMonth(), target.lengthOfMonth()));
            copy.paid = false;
            copy.paymentType = "NONE";
            copy.paymentCode = null;
            copy.lastReminded = null;
            copy.importSourceId = source.id;
            copy.importMonth = target.atDay(1);
            copies.add(copy);
        }
        bills.saveAllAndFlush(copies);
        return new Result(copies.size(), skipped);
    }
}
