package com.axiom.cpq;

import com.axiom.api.PageResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CpqControllerTest {
    @Test void productsDelegateSearchFilterAndPaginationContract() {
        CpqService service = mock(CpqService.class);
        CpqController controller = new CpqController(service);
        PageResult<CpqService.ProductRow> page = PageResult.of(List.of(), 2, 100, 0);
        when(service.products("platform", "Subscription", 2)).thenReturn(page);

        assertEquals(page, controller.products("platform", "Subscription", 2));
        verify(service).products("platform", "Subscription", 2);
    }

    @Test void quotesDelegateStatusAndPaginationContract() {
        CpqService service = mock(CpqService.class);
        CpqController controller = new CpqController(service);
        PageResult<CpqService.QuoteRow> page = PageResult.of(List.of(), 1, 100, 0);
        when(service.quotes("kestrel", "SENT", 1)).thenReturn(page);

        assertEquals(page, controller.quotes("kestrel", "SENT", 1));
        verify(service).quotes("kestrel", "SENT", 1);
    }
}
