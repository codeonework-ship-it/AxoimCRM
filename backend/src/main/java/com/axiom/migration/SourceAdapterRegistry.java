package com.axiom.migration;

import com.axiom.common.NotFoundException;
import com.axiom.migration.SourceContract.SourceAdapter;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one place a vendor name becomes an adapter.
 *
 * <p>Spring collects every {@link SourceAdapter} bean, so adding a vendor is
 * adding a bean — no switch statement in a service grows a fourth arm, and no
 * caller in this module ever names a vendor class.
 */
@Component
public class SourceAdapterRegistry {

    private final Map<String, SourceAdapter> byVendor;

    public SourceAdapterRegistry(List<SourceAdapter> adapters) {
        this.byVendor = adapters.stream().collect(Collectors.toUnmodifiableMap(
                a -> a.vendor().toUpperCase(Locale.ROOT), Function.identity()));
    }

    public List<SourceAdapter> all() {
        return byVendor.values().stream()
                .sorted(Comparator.comparing(SourceAdapter::displayName))
                .toList();
    }

    public Optional<SourceAdapter> find(String vendor) {
        return vendor == null ? Optional.empty()
                : Optional.ofNullable(byVendor.get(vendor.toUpperCase(Locale.ROOT)));
    }

    public SourceAdapter require(String vendor) {
        return find(vendor).orElseThrow(() -> new NotFoundException(
                "No migration source adapter for vendor " + vendor + ". Available: " + byVendor.keySet()));
    }
}
