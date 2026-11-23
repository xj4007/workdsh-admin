package com.techflag.workdsh.admin.managed;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Managed model route for personal spaces. Authentication is always the session bearer. */
@RestController
public class ManagedGatewayController {
    private final ManagedGateway gateway;

    public ManagedGatewayController(ManagedGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/api/managed/v1/chat/completions")
    public ResponseEntity<StreamingResponseBody> completions(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @RequestBody byte[] body) {
        return gateway.completions(bearer, body);
    }
}
