package com.axiom.contracting;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Vendors and purchase orders.
 *
 * <p>Kept on its own path rather than folded into {@code /order-to-cash}, because
 * procure-to-pay is the opposite direction of money and a different audience:
 * the people who raise purchase orders are usually not the people who book sales
 * orders, and the two surfaces get different permissions.
 */
@RestController
@RequestMapping("/api/v1/procurement")
public class ProcurementController {

    private final ProcurementService procurement;

    public ProcurementController(ProcurementService procurement) {
        this.procurement = procurement;
    }

    // ------------------------------------------------------------------ vendors

    @GetMapping("/vendors")
    public List<ProcurementService.Vendor> listVendors(@RequestParam(required = false) String search,
                                                       @RequestParam(required = false) String status) {
        return procurement.listVendors(search, status);
    }

    @GetMapping("/vendors/{id}")
    public ProcurementService.Vendor getVendor(@PathVariable UUID id) {
        return procurement.getVendor(id);
    }

    @PostMapping("/vendors")
    @ResponseStatus(HttpStatus.CREATED)
    public ProcurementService.Vendor createVendor(
            @RequestBody @Valid ProcurementService.VendorRequest request) {
        return procurement.createVendor(request);
    }

    @PutMapping("/vendors/{id}")
    public ProcurementService.Vendor updateVendor(
            @PathVariable UUID id, @RequestParam long version,
            @RequestBody @Valid ProcurementService.VendorRequest request) {
        return procurement.updateVendor(id, version, request);
    }

    @DeleteMapping("/vendors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVendor(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        procurement.deleteVendor(id, reason);
    }

    // ---------------------------------------------------------- purchase orders

    @GetMapping("/purchase-orders")
    public List<ProcurementService.PurchaseOrder> listPurchaseOrders(
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) String status) {
        return procurement.listPurchaseOrders(vendorId, status);
    }

    @GetMapping("/purchase-orders/{id}")
    public ProcurementService.PurchaseOrder getPurchaseOrder(@PathVariable UUID id) {
        return procurement.getPurchaseOrder(id);
    }

    @PostMapping("/purchase-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ProcurementService.PurchaseOrder createPurchaseOrder(
            @RequestBody @Valid ProcurementService.PoRequest request) {
        return procurement.createPurchaseOrder(request);
    }

    @PutMapping("/purchase-orders/{id}/lines")
    public ProcurementService.PurchaseOrder replaceLines(
            @PathVariable UUID id, @RequestParam long version,
            @RequestBody @Valid List<ProcurementService.PoLineRequest> lines) {
        return procurement.replaceLines(id, version, lines);
    }

    @PostMapping("/purchase-orders/{id}/status")
    public ProcurementService.PurchaseOrder transition(
            @PathVariable UUID id, @RequestBody @Valid ProcurementService.PoTransition request) {
        return procurement.transition(id, request);
    }

    @PostMapping("/purchase-orders/{id}/receive")
    public ProcurementService.PurchaseOrder receive(
            @PathVariable UUID id, @RequestBody @Valid ProcurementService.ReceiveRequest request) {
        return procurement.receive(id, request);
    }
}
