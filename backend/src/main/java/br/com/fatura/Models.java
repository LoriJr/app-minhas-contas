package br.com.fatura;
import jakarta.persistence.*;import java.util.*;import java.time.*;import java.math.*;import org.springframework.data.jpa.repository.*;
@Entity @Table(name="app_user") class AppUser {@Id public UUID id=UUID.randomUUID();public String name;public String email;public String password;}
@Entity class Bill {@Id public UUID id=UUID.randomUUID();public UUID ownerId;public String title;public String category;public BigDecimal amount;public LocalDate due;public boolean paid;public int reminderDays;public String paymentCode;public String paymentType;public String source;public UUID importSourceId;public LocalDate importMonth;public LocalDate lastReminded;@Version public long version;}
interface Users extends JpaRepository<AppUser,UUID>{Optional<AppUser> findByEmail(String email);
@Lock(LockModeType.PESSIMISTIC_WRITE) @org.springframework.data.jpa.repository.Query("select u from AppUser u where u.id = :id") Optional<AppUser> lockById(@org.springframework.data.repository.query.Param("id") UUID id);}
interface Bills extends JpaRepository<Bill,UUID>{List<Bill> findByOwnerIdOrderByDueAsc(UUID ownerId);Optional<Bill> findByIdAndOwnerId(UUID id,UUID ownerId);List<Bill> findByPaidFalse();}
