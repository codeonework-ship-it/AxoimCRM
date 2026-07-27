package com.axiom.identity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamlAssertionValidatorTest {
    @Test void unsignedAssertionIsNeverAccepted() {
        String xml = """
                <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
                  xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion" InResponseTo="_request"
                  Destination="https://axiom.example/acs">
                  <samlp:Status><samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/></samlp:Status>
                  <saml:Assertion ID="_assertion"><saml:Issuer>https://idp.example</saml:Issuer></saml:Assertion>
                </samlp:Response>
                """;
        String encoded = Base64.getEncoder().encodeToString(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new SamlAssertionValidator().validate(encoded, "not-used",
                "https://idp.example", "https://axiom.example/saml/acme",
                "https://axiom.example/acs", "_request", Duration.ZERO))
                .hasMessageContaining("signed exactly once");
    }

    @Test void doctypesAreRejectedBeforeAnyAssertionProcessing() {
        String xml = "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><foo>&xxe;</foo>";
        String encoded = Base64.getEncoder().encodeToString(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new SamlAssertionValidator().validate(encoded, "unused", "issuer",
                "audience", "recipient", "request", Duration.ZERO))
                .hasMessageContaining("could not be validated");
    }
}
