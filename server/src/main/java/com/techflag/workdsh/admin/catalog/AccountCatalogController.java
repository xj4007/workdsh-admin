package com.techflag.workdsh.admin.catalog;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.techflag.workdsh.admin.AccountAuthService;
import com.techflag.workdsh.admin.CreditLedger;

/**
 * Personal-space catalog and wallet. Enterprise spaces never see the platform catalog and never
 * spend platform credits, so both endpoints reject them.
 */
@RestController
public class AccountCatalogController {
    private final AccountAuthService auth;
    private final PlatformCatalog catalog;
    private final CreditLedger ledger;

    public AccountCatalogController(AccountAuthService auth, PlatformCatalog catalog, CreditLedger ledger) {
        this.auth = auth;
        this.catalog = catalog;
        this.ledger = ledger;
    }

    @GetMapping("/api/account/models")
    public List<PlatformCatalog.ModelView> models(
            @RequestHeader(value = "Authorization", required = false) String bearer) {
        AccountAuthService.Session session = auth.requireSession(bearer);
        if (!session.space().personal()) return List.of();
        return catalog.enabledModels();
    }

    @GetMapping("/api/account/wallet")
    public WalletView wallet(@RequestHeader(value = "Authorization", required = false) String bearer) {
        AccountAuthService.Space space = auth.requirePersonalSpace(bearer, null);
        CreditLedger.Wallet wallet = ledger.wallet(space.id());
        return new WalletView(wallet.spaceId(), wallet.balanceUnits(), wallet.reservedUnits(),
                wallet.availableUnits(), FixedPoint.credits(wallet.balanceUnits(), wallet.scale()),
                FixedPoint.credits(wallet.availableUnits(), wallet.scale()), wallet.unit(), wallet.scale(),
                wallet.revision());
    }

    public record WalletView(String spaceId, long balanceUnits, long reservedUnits, long availableUnits,
                             String balanceCredits, String availableCredits, String unit, int scale, long revision) {}
}
