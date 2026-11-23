package com.techflag.workdsh.admin;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Platform account entry points for personal and enterprise spaces. */
@RestController
@RequestMapping("/api/account")
public class AccountController {
    private static final int BROWSER_SESSION_SECONDS = 2_592_000;

    private final AccountSpaces accounts;
    private final AccountAuthService auth;
    private final VerificationCodes codes;
    private final boolean secureCookie;
    private final boolean registrationEnabled;

    public AccountController(AccountSpaces accounts, AccountAuthService auth, VerificationCodes codes,
                             @Value("${workdsh.accounts.registration-enabled:true}") boolean registrationEnabled,
                             @Value("${workdsh.web.origin:http://127.0.0.1:18891}") String webOrigin) {
        this.accounts = accounts;
        this.auth = auth;
        this.codes = codes;
        this.registrationEnabled = registrationEnabled;
        this.secureCookie = webOrigin.startsWith("https://");
    }

    public record RequestCode(@NotBlank @Email @Size(max=254) String email) {}
    public record Register(@NotBlank @Email @Size(max=254) String email, @NotBlank @Size(max=16) String code,
                           @NotBlank @Size(min=12, max=200) String password, @Size(max=120) String displayName) {}
    public record Credentials(@NotBlank @Email @Size(max=254) String email, @NotBlank @Size(max=512) String password) {}
    public record PasswordChange(@NotBlank @Size(max=512) String currentPassword,
                                 @NotBlank @Size(min=12, max=200) String newPassword) {}
    public record CodeIssued(Instant expiresAt, String code) {}
    public record Me(AccountAuthService.Account account, AccountAuthService.Space space,
                     List<AccountAuthService.Space> spaces) {}
    public record Config(boolean registrationEnabled) {}

    @PostMapping("/request-code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CodeIssued requestCode(@Valid @RequestBody RequestCode request) {
        requireRegistration();
        var issued = codes.issue(request.email(), VerificationCodes.REGISTER);
        return new CodeIssued(issued.expiresAt(), issued.code());
    }

    @PostMapping("/register")
    public AccountSpaces.LoginResult register(@Valid @RequestBody Register request) {
        requireRegistration();
        return accounts.register(request.email(), request.code(), request.password(), request.displayName());
    }

    @PostMapping("/login")
    public AccountSpaces.LoginResult login(@Valid @RequestBody Credentials request) {
        return accounts.login(request.email(), request.password());
    }

    @PostMapping("/browser-login")
    public Me browserLogin(@Valid @RequestBody Credentials request, HttpServletResponse response) {
        var login = accounts.login(request.email(), request.password());
        response.addHeader("Set-Cookie",
                BrowserSessionFilter.sessionCookie(login.token(), BROWSER_SESSION_SECONDS, secureCookie));
        return new Me(login.account(), login.space(), login.spaces());
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestHeader(value="Authorization", required=false) String bearer,
                               @Valid @RequestBody PasswordChange change) {
        accounts.changePassword(bearer, change.currentPassword(), change.newPassword());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value="Authorization", required=false) String bearer,
                       HttpServletResponse response) {
        auth.logout(bearer);
        response.addHeader("Set-Cookie", BrowserSessionFilter.sessionCookie("", 0, secureCookie));
    }

    @GetMapping("/me")
    public Me me(@RequestHeader(value="Authorization", required=false) String bearer) {
        var session = auth.requireSession(bearer);
        return new Me(session.account(), session.space(), accounts.spaces(session.account().id()));
    }

    @GetMapping("/spaces")
    public List<AccountAuthService.Space> spaces(@RequestHeader(value="Authorization", required=false) String bearer) {
        return accounts.spaces(auth.requireAccount(bearer).id());
    }

    @PostMapping("/spaces/{spaceId}/token")
    public AccountSpaces.LoginResult switchSpace(@PathVariable String spaceId,
                                                 @RequestHeader(value="Authorization", required=false) String bearer) {
        return accounts.switchSpace(bearer, spaceId);
    }

    @GetMapping("/config")
    public Config config() { return new Config(registrationEnabled); }

    private void requireRegistration() {
        if (!registrationEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registration is disabled");
        }
    }
}
