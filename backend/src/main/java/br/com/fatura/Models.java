package br.com.fatura;
import jakarta.persistence.*;import java.util.*;import java.time.*;import java.math.*;import org.springframework.data.jpa.repository.*;
@Entity @Table(name="app_user") class AppUser {@Id public UUID id=UUID.randomUUID();public String name;public String email;public String password;}
@Entity class Bill {@Id public UUID id=UUID.randomUUID();public UUID ownerId;public String title;public String category;public BigDecimal amount;public LocalDate due;public boolean paid;public int reminderDays;public String paymentCode;public String paymentType;public String source;public LocalDate lastReminded;@Version public long version;}
interface Users extends JpaRepository<AppUser,UUID>{Optional<AppUser> findByEmail(String email);}
interface Bills extends JpaRepository<Bill,UUID>{List<Bill> findByOwnerIdOrderByDueAsc(UUID ownerId);Optional<Bill> findByIdAndOwnerId(UUID id,UUID ownerId);List<Bill> findByPaidFalse();}
