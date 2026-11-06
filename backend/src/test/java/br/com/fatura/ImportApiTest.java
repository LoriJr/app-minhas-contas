package br.com.fatura;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ImportApi.class) @Import(Security.class)
class ImportApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean Users users;
    @MockitoBean BillImport importer;
    @Test void anonymousCannotImport() throws Exception {
        mvc.perform(post("/api/bills/import-previous").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(importer);
    }
    @Test void importRequiresCsrf() throws Exception {
        mvc.perform(post("/api/bills/import-previous").with(user("a@example.com"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(importer);
    }
    @Test void emptySelectionIsRejected() throws Exception {
        mvc.perform(post("/api/bills/import-previous").with(user("a@example.com")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetMonth\":\"2026-11\",\"sourceIds\":[]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(importer);
    }
    @Test void importsForAuthenticatedOwner() throws Exception {
        AppUser owner = new AppUser(); owner.email = "a@example.com";
        UUID sourceId = UUID.randomUUID();
        var request = new BillImport.Request("2026-11",List.of(sourceId));
        when(users.findByEmail(owner.email)).thenReturn(Optional.of(owner));
        when(importer.copy(owner.id,request)).thenReturn(new BillImport.Result(1,0));
        mvc.perform(post("/api/bills/import-previous").with(user(owner.email)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetMonth\":\"2026-11\",\"sourceIds\":[\""+sourceId+"\"]}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"imported\":1,\"skipped\":0}"));
        verify(importer).copy(owner.id,request);
    }
}
