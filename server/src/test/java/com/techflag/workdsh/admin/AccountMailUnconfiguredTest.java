package com.techflag.workdsh.admin;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:account-mail-unconfigured;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workdsh.bootstrap.admin-email=owner@mail-unconfigured.test",
        "workdsh.bootstrap.admin-password=OwnerPassword123!",
        "workdsh.accounts.expose-verification-code=false"})
@AutoConfigureMockMvc
class AccountMailUnconfiguredTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void requestCodeFailsExplicitlyWhenMailIsNotConfigured() throws Exception {
        MvcResult result = mvc.perform(post("/api/account/request-code").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "nobody@mail-unconfigured.test"))))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        var error = assertInstanceOf(ResponseStatusException.class, result.getResolvedException());
        assertTrue(error.getReason().contains("Verification email is not configured"));
    }
}
