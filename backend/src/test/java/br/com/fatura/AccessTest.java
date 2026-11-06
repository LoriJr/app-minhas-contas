package br.com.fatura;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.mockito.Mockito.*;
import java.util.*;

@WebMvcTest(Api.class) @Import(Security.class)
class AccessTest {
    @Autowired MockMvc mvc;
    @MockitoBean Users users;
    @MockitoBean BillService service;
    @Test void anonymousCannotReadBills() throws Exception {
        mvc.perform(get("/api/bills")).andExpect(status().isUnauthorized());
    }
    @Test void writesRequireCsrf() throws Exception {
        mvc.perform(post("/api/bills").with(user("a@example.com"))).andExpect(status().isForbidden());
    }
    @Test void listPassesAuthenticatedOwnerAndMonth() throws Exception {
        AppUser owner=new AppUser(); owner.email="a@example.com";
        when(users.findByEmail(owner.email)).thenReturn(Optional.of(owner));
        when(service.list(owner.id,"2026-11")).thenReturn(List.of());
        mvc.perform(get("/api/bills?month=2026-11").with(user(owner.email)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).list(owner.id,"2026-11");
    }
    @Test void cannotDeleteAnotherUsersAccount() throws Exception {
        AppUser owner=new AppUser(); owner.email="a@example.com"; UUID id=UUID.randomUUID();
        when(users.findByEmail(owner.email)).thenReturn(Optional.of(owner));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(service).delete(owner.id,id);
        mvc.perform(delete("/api/bills/"+id).with(user(owner.email)).with(csrf()))
                .andExpect(status().isNotFound());
        verify(service).delete(owner.id,id);
    }
    @Test void dueDayOutsideMonthRangeIsRejected() throws Exception {
        mvc.perform(post("/api/bills").with(user("a@example.com")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Aluguel\",\"category\":\"Moradia\",\"amount\":1800,\"dueDay\":32,\"reminderDays\":3}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
