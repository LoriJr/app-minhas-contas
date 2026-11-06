package br.com.fatura;

import jakarta.persistence.*;
import java.util.*;
import java.time.*;
import java.math.*;
import org.springframework.data.jpa.repository.*;

@Entity @Table(name="app_user")
class AppUser {
    @Id public UUID id = UUID.randomUUID();
    public String name;
    public String email;
    public String password;
}
@Entity @Table(name="recurring_bill")
class RecurringBill {
    @Id public UUID id = UUID.randomUUID();
    public UUID ownerId;
    public String title;
    public String category;
    public String source;
    public BigDecimal amount;
    public int dueDay;
    public int reminderDays;
    public LocalDate startMonth;
    @Version public long version;
}
@Entity
class Bill {
    @Id public UUID id = UUID.randomUUID();
    public UUID ownerId;
    public UUID recurringId;
    public LocalDate billingMonth;
    public String title;
    public String category;
    public String source;
    public BigDecimal amount;
    public LocalDate due;
    public boolean paid;
    public int reminderDays;
    public LocalDate lastReminded;
    @Transient public int dueDay;
    @Version public long version;
}
interface Users extends JpaRepository<AppUser,UUID> {
    Optional<AppUser> findByEmail(String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> lockById(@org.springframework.data.repository.query.Param("id") UUID id);
}
interface Bills extends JpaRepository<Bill,UUID> {
    List<Bill> findByOwnerIdOrderByDueAsc(UUID ownerId);
    Optional<Bill> findByIdAndOwnerId(UUID id, UUID ownerId);
    void deleteByRecurringId(UUID recurringId);
}
interface Recurrences extends JpaRepository<RecurringBill,UUID> {
    List<RecurringBill> findByOwnerId(UUID ownerId);
    Optional<RecurringBill> findByIdAndOwnerId(UUID id, UUID ownerId);
}
