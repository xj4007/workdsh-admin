package com.techflag.workdsh.admin.catalog;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.techflag.workdsh.admin.AccountAuthService;

/** Platform-admin catalog and credit issuance. Only the platform superadmin may call these. */
@RestController
@RequestMapping("/api/admin/platform")
public class PlatformCatalogController {
    private final AccountAuthService auth;
    private final PlatformCatalog catalog;
    private final PlatformCredits credits;

    public PlatformCatalogController(AccountAuthService auth, PlatformCatalog catalog, PlatformCredits credits) {
        this.auth = auth;
        this.catalog = catalog;
        this.credits = credits;
    }

    @GetMapping("/models")
    public List<PlatformCatalog.AdminModel> models(
            @RequestHeader(value = "Authorization", required = false) String bearer) {
        admin(bearer);
        return catalog.list();
    }

    @GetMapping("/models/{id}")
    public PlatformCatalog.AdminModel model(@RequestHeader(value = "Authorization", required = false) String bearer,
                                            @PathVariable String id) {
        admin(bearer);
        return catalog.get(id);
    }

    @PostMapping("/models")
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformCatalog.AdminModel create(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @RequestBody PlatformCatalog.Create request) {
        return catalog.create(admin(bearer), request);
    }

    @PutMapping("/models/{id}")
    public PlatformCatalog.AdminModel update(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable String id, @RequestBody PlatformCatalog.Update request) {
        return catalog.update(admin(bearer), id, request);
    }

    @PostMapping("/models/{id}/enabled")
    public PlatformCatalog.AdminModel enabled(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable String id, @RequestBody PlatformCatalog.Enabled request) {
        return catalog.setEnabled(admin(bearer), id, request == null ? null : request.enabled());
    }

    @PostMapping("/models/{id}/vendor-key")
    public PlatformCatalog.AdminModel rotateKey(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable String id, @RequestBody PlatformCatalog.RotateKey request) {
        return catalog.rotateKey(admin(bearer), id, request);
    }

    @PostMapping("/credits")
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformCredits.Issued issue(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @RequestBody PlatformCredits.Issue request) {
        return credits.issue(admin(bearer), request);
    }

    private AccountAuthService.Account admin(String bearer) {
        AccountAuthService.Account account = auth.requireAccount(bearer);
        auth.requirePlatformAdmin(account);
        return account;
    }
}
