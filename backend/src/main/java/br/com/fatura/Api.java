package br.com.fatura;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.validation.*;
import jakarta.validation.constraints.*;

import java.security.Principal;
import java.util.*;
import java.time.YearMonth;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
class Api {
    final Users users;
    final BillService service;
    final PasswordEncoder encoder;
    final ExportImport export;

    Api(Users users, BillService service, PasswordEncoder encoder, ExportImport export) {
        this.users = users;
        this.service = service;
        this.encoder = encoder;
        this.export = export;
    }

    record Registration(@NotBlank @Size(max = 100) String name, @Email @NotBlank @Size(max = 254) String email,
                        @NotBlank @Size(min = 12, max = 72) String password) {
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "header", token.getHeaderName());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    void register(@Valid @RequestBody Registration input) {
        if (input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Senha deve ter até 72 bytes UTF-8");
        AppUser user = new AppUser();
        user.name = input.name().trim();
        user.email = input.email().trim().toLowerCase();
        user.password = encoder.encode(input.password());
        try {
            users.saveAndFlush(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
    }

    AppUser owner(Principal principal) {
        return users.findByEmail(principal.getName()).orElseThrow();
    }

    @GetMapping("/me")
    Map<String, String> me(Principal principal) {
        AppUser u = owner(principal);
        return Map.of("name", u.name, "email", u.email);
    }

    @GetMapping("/bills")
    List<Bill> list(@RequestParam(required = false) String month, Principal principal) {
        return service.list(owner(principal).id, month == null ? service.current().toString() : month);
    }

    @PostMapping("/bills")
    Bill add(@Valid @RequestBody BillService.Input input, Principal principal) {
        return service.add(owner(principal).id, input);
    }

    @PutMapping("/bills/{id}")
    Bill update(@PathVariable UUID id, @Valid @RequestBody BillService.Input input, Principal principal) {
        return service.update(owner(principal).id, id, input);
    }

    @PostMapping("/bills/{id}/paid")
    Bill paid(@PathVariable UUID id, Principal principal) {
        return service.paid(owner(principal).id, id);
    }

    @DeleteMapping("/bills/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, Principal principal) {
        service.delete(owner(principal).id, id);
    }

    @GetMapping("/export/csv")
    ResponseEntity<byte[]> exportCsv(Principal principal) {
        byte[] body = export.csv(owner(principal).id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contas-minhas-contas.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(body);
    }

    @GetMapping("/export/pdf")
    ResponseEntity<byte[]> exportPdf(@RequestParam(required = false) String month, Principal principal) {
        AppUser user = owner(principal);
        YearMonth ym = BillService.month(month == null ? service.current().toString() : month);
        List<Bill> allBills = service.list(user.id, ym.toString());
        List<Bill> bills = allBills.stream().filter(b -> YearMonth.from(b.billingMonth).equals(ym)).toList();
        byte[] body = export.pdf(user, ym, bills);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"extrato-" + ym + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF).body(body);
    }

    @PostMapping("/import/csv")
    Map<String, Object> importCsv(@RequestParam("file") MultipartFile file, Principal principal) throws IOException {
        ExportImport.ImportResult r = export.importCsv(owner(principal).id, file.getBytes());
        return Map.of("imported", r.imported(), "errors", r.errors());
    }
}
