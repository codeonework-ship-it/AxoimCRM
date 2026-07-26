package com.axiom.contracting;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Order-to-cash rules that are decided in Java: the money arithmetic, the state
 * machines, and segregation of duties on purchase-order approval.
 *
 * <p>Each of these is a way to misstate revenue or spend, which is why they are
 * refusals rather than warnings, and why they are worth pinning. The database
 * enforces the same things again where it can (over-fulfilment, over-payment and
 * approver inequality all have CHECK constraints); these tests cover the layer
 * that produces the message a human reads, and the longer-range rules a
 * single-row CHECK structurally cannot see.
 */
class OrderToCashTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ME = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID RECORD = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID LINE = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private JdbcTemplate jdbc;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        bindAs("TENANT_ADMIN");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void bindAs(String role) {
        TenantContext.set(new TenantContext.Principal(TENANT, ME, role, "Raj", "raj@example.test"));
    }

    // =========================================================== money arithmetic

    @Nested
    class Arithmetic {

        /**
         * Two decimal places, HALF_UP — the rounding a customer sees on a document.
         * Pinned because a change of scale or rounding mode here silently restates
         * every total in the system.
         */
        @Test
        void extendedAmountAppliesTheDiscountThenRoundsToTwoPlaces() {
            assertEquals(new BigDecimal("450000.00"),
                    SalesOrderService.extended(new BigDecimal("4"), new BigDecimal("125000"),
                            new BigDecimal("10")));
            assertEquals(new BigDecimal("45000.00"),
                    SalesOrderService.extended(BigDecimal.ONE, new BigDecimal("45000"), BigDecimal.ZERO));
        }

        @Test
        void aFullDiscountIsZeroAndNotNegative() {
            assertEquals(new BigDecimal("0.00"),
                    SalesOrderService.extended(new BigDecimal("3"), new BigDecimal("100"),
                            new BigDecimal("100")));
        }

        /** A third of a penny must round, not truncate or blow up. */
        @Test
        void anAwkwardThirdRoundsHalfUp() {
            assertEquals(new BigDecimal("66.67"),
                    SalesOrderService.extended(BigDecimal.ONE, new BigDecimal("100"),
                            new BigDecimal("33.333333")));
        }

        @Test
        void zeroAndNegativeQuantitiesAreRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> SalesOrderService.positive(BigDecimal.ZERO, "quantity"));
            assertThrows(IllegalArgumentException.class,
                    () -> SalesOrderService.positive(new BigDecimal("-1"), "quantity"));
            assertThrows(IllegalArgumentException.class,
                    () -> SalesOrderService.positive(null, "quantity"));
        }

        /** A price of zero is legitimate — a free line on a deal. Negative is not. */
        @Test
        void aZeroPriceIsAllowedButANegativeOneIsNot() {
            assertEquals(BigDecimal.ZERO, SalesOrderService.nonNegative(BigDecimal.ZERO, "unit price"));
            assertThrows(IllegalArgumentException.class,
                    () -> SalesOrderService.nonNegative(new BigDecimal("-0.01"), "unit price"));
        }
    }

    // ======================================================== sales order machine

    @Nested
    class SalesOrders {

        private SalesOrderService orders;

        @BeforeEach
        void setUp() {
            orders = new SalesOrderService(jdbc, audit);
        }

        @Test
        void anOrderCannotBeBookedWithNoLines() {
            stubOrder("DRAFT", List.of());

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> orders.transition(RECORD, new SalesOrderService.TransitionRequest("BOOKED", null)));

            assertTrue(thrown.getMessage().contains("nothing to fulfil or invoice"));
        }

        /**
         * A booked order has been promised to a customer and may already be in
         * fulfilment. Changing what was ordered underneath that is how a warehouse
         * ships the wrong thing.
         */
        @Test
        void aBookedOrdersLinesAreImmutable() {
            stubOrder("BOOKED", List.of(line(new BigDecimal("4"), BigDecimal.ZERO)));

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> orders.replaceLines(RECORD, 0L, List.of(new SalesOrderService.LineRequest(
                            null, null, "Sneaky", null, BigDecimal.ONE, BigDecimal.ONE, null))));

            assertTrue(thrown.getMessage().contains("BOOKED"));
            assertTrue(thrown.getMessage().contains("amendment"),
                    "refusing without naming the supported alternative leaves the user stuck");
        }

        /**
         * A CHECK sees only the row being written and cannot say "you cannot go
         * from FULFILLED back to DRAFT". The transition map can, and it names what
         * is reachable instead of only what is not.
         */
        @Test
        void anIllegalTransitionNamesWhatIsReachable() {
            stubOrder("BOOKED", List.of(line(new BigDecimal("4"), BigDecimal.ZERO)));

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> orders.transition(RECORD, new SalesOrderService.TransitionRequest("DRAFT", null)));

            assertTrue(thrown.getMessage().contains("cannot move to DRAFT"));
            assertTrue(thrown.getMessage().contains("CANCELLED"));
            assertTrue(thrown.getMessage().contains("FULFILMENT"));
        }

        @Test
        void aFinalStateSaysSoRatherThanListingNothing() {
            stubOrder("FULFILLED", List.of(line(new BigDecimal("4"), new BigDecimal("4"))));

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> orders.transition(RECORD, new SalesOrderService.TransitionRequest("BOOKED", null)));

            assertTrue(thrown.getMessage().contains("final state"));
        }

        @Test
        void cancellingRequiresAReasonBecauseItStaysOnTheRecord() {
            stubOrder("DRAFT", List.of(line(new BigDecimal("4"), BigDecimal.ZERO)));

            assertThrows(IllegalArgumentException.class,
                    () -> orders.transition(RECORD,
                            new SalesOrderService.TransitionRequest("CANCELLED", "   ")));
        }

        /** Over-fulfilment corrupts revenue recognition, so the refusal is arithmetic. */
        @Test
        void overFulfilmentIsRefusedAndStatesTheRemainder() {
            stubOrder("BOOKED", List.of(line(new BigDecimal("4"), new BigDecimal("1"))));

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> orders.fulfil(RECORD, new SalesOrderService.FulfilRequest(LINE, new BigDecimal("99"))));

            assertTrue(thrown.getMessage().contains("At most 3"),
                    "the message must state what CAN be recorded: " + thrown.getMessage());
        }

        @Test
        void fulfilmentIsRefusedOnAnOrderThatHasNotBeenBooked() {
            stubOrder("DRAFT", List.of(line(new BigDecimal("4"), BigDecimal.ZERO)));

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> orders.fulfil(RECORD, new SalesOrderService.FulfilRequest(LINE, BigDecimal.ONE)));

            assertTrue(thrown.getMessage().contains("booked or in-fulfilment"));
        }

        @Test
        void replacingLinesWithAnEmptyListIsRefusedAndPointsAtCancel() {
            stubOrder("DRAFT", List.of());

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> orders.replaceLines(RECORD, 0L, List.of()));

            assertTrue(thrown.getMessage().contains("cancel it instead"));
        }

        @Test
        void anOrderMustNameItsAccount() {
            assertThrows(IllegalArgumentException.class,
                    () -> orders.create(new SalesOrderService.OrderRequest(
                            null, null, null, "INR", null, null, null)));
        }

        @Test
        void aReadOnlyRoleCannotCreateAnOrder() {
            bindAs("AUDITOR");
            assertThrows(ForbiddenException.class,
                    () -> orders.create(new SalesOrderService.OrderRequest(
                            UUID.randomUUID(), null, null, "INR", null, null, null)));
        }

        @SuppressWarnings("unchecked")
        private void stubOrder(String status, List<SalesOrderService.OrderLine> lines) {
            SalesOrderService.OrderDetail detail = new SalesOrderService.OrderDetail(
                    RECORD, "SO-2026-00001", UUID.randomUUID(), "Arcstone", null, status,
                    LocalDate.now(), "INR", new BigDecimal("495000.00"), null, ME, "Raj", null, null,
                    Instant.now(), Instant.now(), 0L, lines, "DRAFT".equals(status));
            lenient().when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(TENANT), eq(RECORD)))
                    .thenReturn(detail);
            lenient().when(jdbc.query(anyString(), any(RowMapper.class), eq(TENANT), eq(RECORD)))
                    .thenReturn((List) lines);
        }

        private SalesOrderService.OrderLine line(BigDecimal quantity, BigDecimal fulfilled) {
            return new SalesOrderService.OrderLine(LINE, 1, null, "P-1", "CNC spindle", "EA",
                    quantity, new BigDecimal("125000"), new BigDecimal("10"),
                    new BigDecimal("450000.00"), fulfilled, null);
        }
    }

    // ============================================================== receivables

    @Nested
    class Receivables {

        private ReceivablesService receivables;

        @BeforeEach
        void setUp() {
            receivables = new ReceivablesService(jdbc, audit);
        }

        @Test
        void aDraftInvoiceCannotTakeAPayment() {
            stubInvoice("DRAFT", new BigDecimal("495000.00"), BigDecimal.ZERO, LocalDate.now());

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> receivables.recordPayment(RECORD,
                            new ReceivablesService.PaymentRequest(new BigDecimal("100"), null, null, null)));

            assertTrue(thrown.getMessage().contains("issued, part-paid or overdue"));
        }

        /**
         * An overpayment recorded against an invoice misstates the receivable, so
         * it is refused and the caller is pointed at the credit-note path.
         */
        @Test
        void anOverpaymentIsRefusedAndStatesTheOutstandingBalance() {
            stubInvoice("ISSUED", new BigDecimal("495000.00"), new BigDecimal("100000.00"), LocalDate.now());

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> receivables.recordPayment(RECORD, new ReceivablesService.PaymentRequest(
                            new BigDecimal("9999999"), null, null, null)));

            assertTrue(thrown.getMessage().contains("395000.00"),
                    "the outstanding balance must appear: " + thrown.getMessage());
            assertTrue(thrown.getMessage().contains("credit note"));
        }

        @Test
        void aPaidInvoiceCannotBeCancelledOnlyCredited() {
            stubInvoice("PAID", new BigDecimal("495000.00"), new BigDecimal("495000.00"), LocalDate.now());

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> receivables.transition(RECORD,
                            new ReceivablesService.TransitionRequest("CANCELLED", "changed my mind")));

            assertTrue(thrown.getMessage().contains("Credit it instead"));
            assertTrue(thrown.getMessage().contains("explainable"));
        }

        @Test
        void anIssuedInvoiceNeedsLinesAndADueDate() {
            stubInvoice("DRAFT", BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.now());
            ConflictException noLines = assertThrows(ConflictException.class,
                    () -> receivables.transition(RECORD,
                            new ReceivablesService.TransitionRequest("ISSUED", null)));
            assertTrue(noLines.getMessage().contains("bills for nothing"));

            stubInvoice("DRAFT", BigDecimal.ZERO, BigDecimal.ZERO, null,
                    List.of(invoiceLine()));
            IllegalArgumentException noDue = assertThrows(IllegalArgumentException.class,
                    () -> receivables.transition(RECORD,
                            new ReceivablesService.TransitionRequest("ISSUED", null)));
            assertTrue(noDue.getMessage().contains("ageing report"),
                    "explain why the due date matters, not just that it is required");
        }

        /** Paid status follows payments; setting it directly would let the ledger lie. */
        @Test
        void paidStatusCannotBeSetDirectly() {
            stubInvoice("ISSUED", new BigDecimal("100.00"), BigDecimal.ZERO, LocalDate.now());

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> receivables.transition(RECORD,
                            new ReceivablesService.TransitionRequest("PAID", null)));

            assertTrue(thrown.getMessage().contains("follows payments"));
        }

        @Test
        void writeOffAndCreditRequireAReason() {
            stubInvoice("ISSUED", new BigDecimal("100.00"), BigDecimal.ZERO, LocalDate.now());
            for (String target : List.of("WRITTEN_OFF", "CREDITED", "CANCELLED")) {
                assertThrows(IllegalArgumentException.class,
                        () -> receivables.transition(RECORD,
                                new ReceivablesService.TransitionRequest(target, "  ")),
                        target + " must require a reason");
            }
        }

        @Test
        void anInvoiceMustNameItsAccount() {
            assertThrows(IllegalArgumentException.class,
                    () -> receivables.create(new ReceivablesService.InvoiceRequest(
                            null, null, "INR", null, null, null)));
        }

        private void stubInvoice(String status, BigDecimal total, BigDecimal paid, LocalDate due) {
            stubInvoice(status, total, paid, due, total.signum() == 0 ? List.of() : List.of(invoiceLine()));
        }

        @SuppressWarnings("unchecked")
        private void stubInvoice(String status, BigDecimal total, BigDecimal paid, LocalDate due,
                                 List<ReceivablesService.InvoiceLine> lines) {
            ReceivablesService.InvoiceDetail detail = new ReceivablesService.InvoiceDetail(
                    RECORD, "INV-2026-00001", UUID.randomUUID(), "Arcstone", null, null, status,
                    LocalDate.now(), due, "INR", total, BigDecimal.ZERO, total, paid,
                    total.subtract(paid), null, null, null, Instant.now(), Instant.now(), 0L,
                    lines, List.of(), "DRAFT".equals(status));
            lenient().when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(TENANT), eq(RECORD)))
                    .thenReturn(detail);
            lenient().when(jdbc.query(anyString(), any(RowMapper.class), eq(TENANT), eq(RECORD)))
                    .thenReturn((List) lines, (List) List.of());
        }

        private ReceivablesService.InvoiceLine invoiceLine() {
            return new ReceivablesService.InvoiceLine(LINE, 1, "CNC spindle", new BigDecimal("4"),
                    new BigDecimal("125000"), BigDecimal.ZERO, new BigDecimal("450000.00"), null);
        }
    }

    // ============================================================== procurement

    @Nested
    class Procurement {

        private ProcurementService procurement;

        @BeforeEach
        void setUp() {
            procurement = new ProcurementService(jdbc, audit);
        }

        /**
         * The headline control: a purchase order commits money outward, so whoever
         * approves must not be whoever raised it. The database enforces the
         * inequality; this produces the message that names the conflict rather than
         * the column.
         */
        @Test
        void theRaiserOfAPurchaseOrderCannotApproveIt() {
            stubPo("PENDING_APPROVAL", ME, null, List.of(poLine(new BigDecimal("50"), BigDecimal.ZERO)));

            ForbiddenException thrown = assertThrows(ForbiddenException.class,
                    () -> procurement.transition(RECORD,
                            new ProcurementService.PoTransition("APPROVED", "looks fine")));

            assertTrue(thrown.getMessage().contains("You raised"));
            assertTrue(thrown.getMessage().contains("second pair of eyes"));
        }

        @Test
        void aDifferentApproverIsAllowed() {
            stubPo("PENDING_APPROVAL", OTHER, null, List.of(poLine(new BigDecimal("50"), BigDecimal.ZERO)));

            procurement.transition(RECORD, new ProcurementService.PoTransition("APPROVED", "within budget"));

            org.mockito.Mockito.verify(audit).record(eq("PO_APPROVED"), eq("PURCHASE_ORDER"),
                    eq(RECORD), anyString(), any());
        }

        @Test
        void approvalWithNoLinesIsRefused() {
            stubPo("DRAFT", ME, null, List.of());

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> procurement.transition(RECORD,
                            new ProcurementService.PoTransition("PENDING_APPROVAL", null)));

            assertTrue(thrown.getMessage().contains("nothing to approve"));
        }

        @Test
        void anApprovedPurchaseOrdersLinesAreFrozen() {
            stubPo("APPROVED", ME, OTHER, List.of(poLine(new BigDecimal("50"), BigDecimal.ZERO)));

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> procurement.replaceLines(RECORD, 0L, List.of(
                            new ProcurementService.PoLineRequest(null, "sneaky", "EA",
                                    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO))));

            assertTrue(thrown.getMessage().contains("the vendor has seen"));
        }

        @Test
        void overReceiptIsRefusedAndStatesTheRemainder() {
            stubPo("SENT", ME, OTHER, List.of(poLine(new BigDecimal("50"), new BigDecimal("20"))));

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> procurement.receive(RECORD,
                            new ProcurementService.ReceiveRequest(LINE, new BigDecimal("999"))));

            assertTrue(thrown.getMessage().contains("At most 30"),
                    "state what can still be received: " + thrown.getMessage());
        }

        @Test
        void goodsCannotBeReceivedBeforeThePurchaseOrderIsSent() {
            stubPo("APPROVED", ME, OTHER, List.of(poLine(new BigDecimal("50"), BigDecimal.ZERO)));

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> procurement.receive(RECORD,
                            new ProcurementService.ReceiveRequest(LINE, BigDecimal.ONE)));

            assertTrue(thrown.getMessage().contains("sent or partially received"));
        }

        @Test
        void cancellingAPurchaseOrderRequiresAReason() {
            stubPo("SENT", ME, OTHER, List.of(poLine(new BigDecimal("50"), BigDecimal.ZERO)));

            assertThrows(IllegalArgumentException.class,
                    () -> procurement.transition(RECORD,
                            new ProcurementService.PoTransition("CANCELLED", " ")));
        }

        @Test
        void aPurchaseOrderMustNameItsVendor() {
            assertThrows(IllegalArgumentException.class,
                    () -> procurement.createPurchaseOrder(
                            new ProcurementService.PoRequest(null, null, null, "INR", null)));
        }

        @Test
        void aVendorNeedsAName() {
            assertThrows(IllegalArgumentException.class,
                    () -> procurement.createVendor(new ProcurementService.VendorRequest(
                            null, "   ", null, null, null, null, null, null, null, null, null,
                            null, null, null, null, null)));
        }

        @SuppressWarnings("unchecked")
        private void stubPo(String status, UUID requestedBy, UUID approvedBy,
                            List<ProcurementService.PoLine> lines) {
            ProcurementService.PurchaseOrder po = new ProcurementService.PurchaseOrder(
                    RECORD, "PO-2026-00001", UUID.randomUUID(), "Bramwell Supplies", status,
                    LocalDate.now(), null, "INR", new BigDecimal("68000.00"), new BigDecimal("11200.00"),
                    new BigDecimal("79200.00"), requestedBy, "Raj", approvedBy,
                    approvedBy == null ? null : "Ava", null, null, null, null,
                    Instant.now(), Instant.now(), 0L, lines, "DRAFT".equals(status));
            lenient().when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(TENANT), eq(RECORD)))
                    .thenReturn(po);
            lenient().when(jdbc.query(anyString(), any(RowMapper.class), eq(TENANT), eq(RECORD)))
                    .thenReturn((List) lines);
        }

        private ProcurementService.PoLine poLine(BigDecimal quantity, BigDecimal received) {
            return new ProcurementService.PoLine(LINE, 1, null, "Bearing housing", "EA", quantity,
                    new BigDecimal("1200"), new BigDecimal("18"), new BigDecimal("60000.00"), received);
        }
    }
}
