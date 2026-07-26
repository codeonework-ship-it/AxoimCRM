package com.axiom.reporting;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportControllerTest {

    @Test
    void documentPreviewReturnsInlinePdfWithoutUsingTheDownloadContract() {
        ReportService reports = mock(ReportService.class);
        ReportSubscriptionService subscriptions = mock(ReportSubscriptionService.class);
        ReportController controller = new ReportController(reports, subscriptions);
        byte[] pdf = "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        when(reports.documentPreview(org.mockito.ArgumentMatchers.eq("pipeline_snapshot"), any()))
                .thenReturn(new ReportService.FilePayload(pdf, "application/pdf", "pipeline-snapshot-preview.pdf"));

        var response = controller.documentPreview("pipeline_snapshot", null, null, null, null, null);

        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertEquals("inline; filename=\"pipeline-snapshot-preview.pdf\"",
                response.getHeaders().getFirst("Content-Disposition"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertArrayEquals(pdf, response.getBody());
        verify(reports).documentPreview(org.mockito.ArgumentMatchers.eq("pipeline_snapshot"), any());
    }
}
