package com.axiom.cpq;

import com.axiom.api.PageResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CpqControllerTest {
    @Test void productsDelegateSearchFilterAndPaginationContract() {
        CpqService service = mock(CpqService.class);
        CpqController controller = new CpqController(service, mock(QuoteAuthoringService.class));
        PageResult<CpqService.ProductRow> page = PageResult.of(List.of(), 2, 100, 0);
        when(service.products("platform", "Subscription", 2)).thenReturn(page);

        assertEquals(page, controller.products("platform", "Subscription", 2));
        verify(service).products("platform", "Subscription", 2);
    }

    @Test void quotesDelegateStatusAndPaginationContract() {
        CpqService service = mock(CpqService.class);
        CpqController controller = new CpqController(service, mock(QuoteAuthoringService.class));
        PageResult<CpqService.QuoteRow> page = PageResult.of(List.of(), 1, 100, 0);
        when(service.quotes("kestrel", "SENT", 1)).thenReturn(page);

        assertEquals(page, controller.quotes("kestrel", "SENT", 1));
        verify(service).quotes("kestrel", "SENT", 1);
    }

    @Test void quoteDocumentReturnsAttachment() {
        CpqService service = mock(CpqService.class);
        CpqController controller = new CpqController(service, mock(QuoteAuthoringService.class));
        UUID quoteId = UUID.randomUUID();
        CpqService.FilePayload file = new CpqService.FilePayload(
                "quote".getBytes(StandardCharsets.UTF_8), "text/plain", "q-1.txt");
        when(service.quoteDocument(quoteId, CpqService.QuoteDocumentFormat.PDF)).thenReturn(file);

        ResponseEntity<byte[]> response = controller.quoteDocument(quoteId, CpqService.QuoteDocumentFormat.PDF);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("attachment; filename=\"q-1.txt\"", response.getHeaders().getFirst("Content-Disposition"));
        assertEquals("quote", new String(response.getBody(), StandardCharsets.UTF_8));
        verify(service).quoteDocument(quoteId, CpqService.QuoteDocumentFormat.PDF);
    }
}
