package com.axiom.identity;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Hardened SAML HTTP-POST assertion validation with anti-wrapping checks. */
@Component
public class SamlAssertionValidator {
    private static final String SAMLP = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String SAML = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String DS = XMLSignature.XMLNS;

    public Map<String, Object> validate(String encodedResponse, String certificatePem, String expectedIssuer,
                                        String expectedAudience, String expectedRecipient, String requestId,
                                        Duration skew) {
        try {
            byte[] xml = Base64.getDecoder().decode(encodedResponse);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            Element response = doc.getDocumentElement();
            require(response, SAMLP, "Response", "The SAML document is not a Response");
            if (!requestId.equals(response.getAttribute("InResponseTo"))) {
                throw new IllegalArgumentException("SAML InResponseTo does not match the single-use request");
            }
            if (!expectedRecipient.equals(response.getAttribute("Destination"))) {
                throw new IllegalArgumentException("SAML response destination mismatch");
            }
            String status = text(first(response, SAMLP, "StatusCode"), "Value");
            if (!"urn:oasis:names:tc:SAML:2.0:status:Success".equals(status)) {
                throw new IllegalArgumentException("The identity provider returned " + status);
            }
            NodeList assertions = response.getElementsByTagNameNS(SAML, "Assertion");
            if (assertions.getLength() != 1 || assertions.item(0).getParentNode() != response) {
                throw new IllegalArgumentException("The SAML response must contain exactly one direct assertion");
            }
            Element assertion = (Element) assertions.item(0);
            String assertionId = assertion.getAttribute("ID");
            if (assertionId.isBlank()) throw new IllegalArgumentException("The SAML assertion has no ID");
            assertion.setIdAttribute("ID", true);
            NodeList signatures = assertion.getElementsByTagNameNS(DS, "Signature");
            if (signatures.getLength() != 1) throw new IllegalArgumentException("The SAML assertion must be signed exactly once");
            X509Certificate certificate = certificate(certificatePem);
            DOMValidateContext context = new DOMValidateContext(certificate.getPublicKey(), signatures.item(0));
            context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
            XMLSignature signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            if (signature.getSignedInfo().getReferences().size() != 1
                    || !(signature.getSignedInfo().getReferences().get(0) instanceof Reference reference)
                    || !("#" + assertionId).equals(reference.getURI()) || !signature.validate(context)) {
                throw new IllegalArgumentException("The SAML assertion signature is invalid or references another element");
            }
            Element issuer = first(assertion, SAML, "Issuer");
            if (!expectedIssuer.equals(issuer.getTextContent().trim())) throw new IllegalArgumentException("SAML issuer mismatch");
            Duration allowance = skew == null ? Duration.ofMinutes(2) : skew;
            Instant now = Instant.now();
            Element conditions = first(assertion, SAML, "Conditions");
            validateWindow(conditions, now, allowance);
            boolean audience = false;
            NodeList audiences = conditions.getElementsByTagNameNS(SAML, "Audience");
            for (int i = 0; i < audiences.getLength(); i++) {
                if (expectedAudience.equals(audiences.item(i).getTextContent().trim())) audience = true;
            }
            if (!audience) throw new IllegalArgumentException("SAML audience mismatch");
            Element confirmation = first(assertion, SAML, "SubjectConfirmation");
            if (!"urn:oasis:names:tc:SAML:2.0:cm:bearer".equals(confirmation.getAttribute("Method"))) {
                throw new IllegalArgumentException("Only SAML bearer subject confirmation is accepted");
            }
            Element data = first(confirmation, SAML, "SubjectConfirmationData");
            if (!expectedRecipient.equals(data.getAttribute("Recipient"))) throw new IllegalArgumentException("SAML recipient mismatch");
            if (!requestId.equals(data.getAttribute("InResponseTo"))) throw new IllegalArgumentException("SAML subject request mismatch");
            validateNotOnOrAfter(data.getAttribute("NotOnOrAfter"), now, allowance);

            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", first(assertion, SAML, "NameID").getTextContent().trim());
            NodeList attributes = assertion.getElementsByTagNameNS(SAML, "Attribute");
            for (int i = 0; i < attributes.getLength(); i++) {
                Element attribute = (Element) attributes.item(i);
                NodeList values = attribute.getElementsByTagNameNS(SAML, "AttributeValue");
                if (values.getLength() == 1) claims.put(attribute.getAttribute("Name"), values.item(0).getTextContent().trim());
                else if (values.getLength() > 1) {
                    java.util.List<String> list = new java.util.ArrayList<>();
                    for (int n = 0; n < values.getLength(); n++) list.add(values.item(n).getTextContent().trim());
                    claims.put(attribute.getAttribute("Name"), list);
                }
            }
            return claims;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("The SAML response could not be validated: " + e.getMessage(), e);
        }
    }

    private static void validateWindow(Element conditions, Instant now, Duration skew) {
        String before = conditions.getAttribute("NotBefore");
        if (!before.isBlank() && Instant.parse(before).minus(skew).isAfter(now)) throw new IllegalArgumentException("SAML assertion is not active yet");
        validateNotOnOrAfter(conditions.getAttribute("NotOnOrAfter"), now, skew);
    }

    private static void validateNotOnOrAfter(String value, Instant now, Duration skew) {
        if (value.isBlank() || !now.isBefore(Instant.parse(value).plus(skew))) throw new IllegalArgumentException("SAML assertion is expired");
    }

    private static X509Certificate certificate(String pem) throws Exception {
        String body = pem.replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "").replaceAll("\\s", "");
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(body)));
    }

    private static Element first(Element root, String ns, String name) {
        NodeList nodes = root.getElementsByTagNameNS(ns, name);
        if (nodes.getLength() == 0) throw new IllegalArgumentException("SAML response is missing " + name);
        return (Element) nodes.item(0);
    }

    private static void require(Element e, String ns, String local, String message) {
        if (!ns.equals(e.getNamespaceURI()) || !local.equals(e.getLocalName())) throw new IllegalArgumentException(message);
    }

    private static String text(Element e, String attribute) {
        String value = e.getAttribute(attribute);
        if (value.isBlank()) throw new IllegalArgumentException("SAML response is missing " + attribute);
        return value;
    }
}
