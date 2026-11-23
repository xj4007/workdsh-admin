package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:account-disabled;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workdsh.bootstrap.admin-email=disabled@account-disabled.test",
        "workdsh.bootstrap.admin-password=DisabledAdminPassword123!",
        "workdsh.accounts.registration-enabled=false"})
@AutoConfigureMockMvc
class AccountRegistrationDisabledTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void registrationEndpointsRespectConfiguration() throws Exception {
        mvc.perform(get("/api/account/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationEnabled").value(false));
        mvc.perform(post("/api/account/request-code").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "closed@account-disabled.test"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/account/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "closed@account-disabled.test",
                                "code", "123456", "password", "ClosedRegistration123!"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "closed@account-disabled.test",
                                "password", "ClosedRegistration123!"))))
                .andExpect(status().isUnauthorized());
    }
}
