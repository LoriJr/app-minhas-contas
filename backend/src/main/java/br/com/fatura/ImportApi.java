package br.com.fatura;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/bills")
class ImportApi {
    private final Users users;
    private final BillImport importer;
    ImportApi(Users users, BillImport importer) { this.users = users; this.importer = importer; }
    @PostMapping("/import-previous")
    BillImport.Result copy(@Valid @RequestBody BillImport.Request request, Principal principal) {
        return importer.copy(users.findByEmail(principal.getName()).orElseThrow().id, request);
    }
}
