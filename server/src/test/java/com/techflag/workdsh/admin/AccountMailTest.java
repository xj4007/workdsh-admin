package com.techflag.workdsh.admin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:account-mail;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workdsh.bootstrap.admin-email=owner@mail.test",
        "workdsh.bootstrap.admin-password=OwnerPassword123!",
        "workdsh.accounts.expose-verification-code=false",
        "WORKDSH_ACCOUNTS_MAIL_FROM=noreply@workdsh.test"})
@AutoConfigureMockMvc
@Import(AccountMailTest.Config.class)
class AccountMailTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean CapturingMailSender mailSender() { return new CapturingMailSender(); }
    }

    static class CapturingMailSender extends JavaMailSenderImpl {
        final List<SimpleMailMessage> messages = new CopyOnWriteArrayList<>();

        @Override public void send(SimpleMailMessage simpleMessage) { messages.add(simpleMessage); }

        @Override public void send(SimpleMailMessage... simpleMessages) {
            messages.addAll(List.of(simpleMessages));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired CapturingMailSender mail;
    @Autowired ObjectMapper json;

    @Test
    void verificationCodeIsEmailedAndNeverReturnedInTheResponse() throws Exception {
        String email = "mail@mail.test";
        mvc.perform(post("/api/account/request-code").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.code").doesNotExist());

        assertEquals(1, mail.messages.size());
        SimpleMailMessage message = mail.messages.get(0);
        assertEquals(email, message.getTo()[0]);
        assertEquals("noreply@workdsh.test", message.getFrom());
        Matcher matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(message.getText());
        assertTrue(matcher.find(), "mail body must contain the verification code");

        mvc.perform(post("/api/account/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "code", matcher.group(1),
                                "password", "MailedAccount123!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email").value(email));
    }
}
