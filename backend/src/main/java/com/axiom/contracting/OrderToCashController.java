package com.axiom.contracting;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The order-to-cash surface: sales orders, quote conversion, and customer
 * receivables.
 *
 * <p>One controller because it is one chain — a quote becomes an order, an order
 * becomes an invoice, an invoice takes payments — and each step's output is the
 * next step's input. Splitting it across three paths would hide that the endpoints
 * are meant to be called in sequence.
 *
 * <p>Write authorization is enforced twice by design: {@code JwtAuthFilter}
 * refuses any mutating method for a read-only audit role before this class is
 * reached, and each service also calls {@code CrmRole.requireWrite}. These are
 * commitments of money, so the belt-and-braces is deliberate here in a way it is
 * not for, say, a saved view.
 */
@RestController
@RequestMapping("/api/v1/order-to-cash")
public class OrderToCashController {

    private final SalesOrderService orders;
    private final QuoteConversionService conversion;
    private final ReceivablesService receivables;

    public OrderToCashController(SalesOrderService orders, QuoteConversionService conversion,
                                 ReceivablesService receivables) {
        this.orders = orders;
        this.conversion = conversion;
        this.receivables = receivables;
    }

    // ----------------------------------------------------------- sales orders

    @GetMapping("/orders")
    public List<SalesOrderService.OrderDetail> listOrders(@RequestParam(required = false) UUID accountId,
                                                          @RequestParam(required = false) String status) {
        return orders.list(accountId, status);
    }

    @GetMapping("/orders/{id}")
    public SalesOrderService.OrderDetail getOrder(@PathVariable UUID id) {
        return orders.get(id);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public SalesOrderService.OrderDetail createOrder(
            @RequestBody @Valid SalesOrderService.OrderRequest request) {
        return orders.create(request);
    }

    @PutMapping("/orders/{id}/lines")
    public SalesOrderService.OrderDetail replaceOrderLines(
            @PathVariable UUID id, @RequestParam long version,
            @RequestBody @Valid List<SalesOrderService.LineRequest> lines) {
        return orders.replaceLines(id, version, lines);
    }

    @PostMapping("/orders/{id}/status")
    public SalesOrderService.OrderDetail transitionOrder(
            @PathVariable UUID id, @RequestBody @Valid SalesOrderService.TransitionRequest request) {
        return orders.transition(id, request);
    }

    @PostMapping("/orders/{id}/fulfil")
    public SalesOrderService.OrderDetail fulfil(
            @PathVariable UUID id, @RequestBody @Valid SalesOrderService.FulfilRequest request) {
        return orders.fulfil(id, request);
    }

    // ------------------------------------------------------- quote conversion

    @PostMapping("/quotes/{quoteId}/convert")
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteConversionService.ConversionResult convert(
            @PathVariable UUID quoteId,
            @RequestBody(required = false) QuoteConversionService.ConvertRequest request) {
        return conversion.convert(quoteId, request);
    }

    // ------------------------------------------------------------- receivables

    @GetMapping("/invoices")
    public List<ReceivablesService.InvoiceDetail> listInvoices(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean overdueOnly) {
        return receivables.list(accountId, status, overdueOnly);
    }

    @GetMapping("/invoices/{id}")
    public ReceivablesService.InvoiceDetail getInvoice(@PathVariable UUID id) {
        return receivables.get(id);
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceivablesService.InvoiceDetail createInvoice(
            @RequestBody @Valid ReceivablesService.InvoiceRequest request) {
        return receivables.create(request);
    }

    /** Bills a booked order: lines copied with their agreed prices. */
    @PostMapping("/orders/{orderId}/invoice")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceivablesService.InvoiceDetail invoiceOrder(
            @PathVariable UUID orderId,
            @RequestBody(required = false) ReceivablesService.InvoiceRequest overrides) {
        return receivables.fromOrder(orderId, overrides);
    }

    @PutMapping("/invoices/{id}/lines")
    public ReceivablesService.InvoiceDetail replaceInvoiceLines(
            @PathVariable UUID id, @RequestParam long version,
            @RequestBody @Valid List<ReceivablesService.LineRequest> lines) {
        return receivables.replaceLines(id, version, lines);
    }

    @PostMapping("/invoices/{id}/status")
    public ReceivablesService.InvoiceDetail transitionInvoice(
            @PathVariable UUID id, @RequestBody @Valid ReceivablesService.TransitionRequest request) {
        return receivables.transition(id, request);
    }

    @PostMapping("/invoices/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceivablesService.InvoiceDetail recordPayment(
            @PathVariable UUID id, @RequestBody @Valid ReceivablesService.PaymentRequest request) {
        return receivables.recordPayment(id, request);
    }
}
