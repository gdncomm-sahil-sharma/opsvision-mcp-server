package com.gdn.opsvision.mcp.tool;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.PickPackageEvidence;
import com.gdn.opsvision.mcp.repository.PickPackageRepository;

@Service
public class PickPackageTool {

    private final PickPackageRepository repo;

    public PickPackageTool(PickPackageRepository repo) {
        this.repo = repo;
    }

    @Tool(description = """
            Fetch a pick package and its closure (handling units + sales orders) from the Stockholm WMS \
            for the Marunda warehouse. Use this as the entry point when investigating any specific pick \
            package — it returns header state plus all PPHUs and SOs in one call.

            ID format: package code is 'PK/{SITE}-01/{ROMAN_MONTH}-{YEAR}/{SEQ}', e.g. 'PK/MAR-01/V-2026/7747838'. \
            Numeric pick_package.id (long) is also accepted.

            Returns FACTS, not VERDICTS — the response contains state fields (picking_status, canceled, \
            short_pick, last_status, etc.); the caller decides what they mean.
            """)
    public PickPackageEvidence getPickPackage(
            @ToolParam(description = "Pick package code (PK/MAR-01/V-2026/...) or numeric id") String idOrCode) {

        Optional<PickPackageEvidence.Header> header = lookupHeader(idOrCode);
        if (header.isEmpty()) {
            return new PickPackageEvidence(null, List.of(), List.of());
        }
        long ppId = header.get().id();
        return new PickPackageEvidence(
                header.get(),
                repo.findHandlingUnits(ppId),
                repo.findSalesOrders(ppId));
    }

    private Optional<PickPackageEvidence.Header> lookupHeader(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            return Optional.empty();
        }
        String trimmed = idOrCode.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            return repo.findHeaderById(Long.parseLong(trimmed));
        }
        return repo.findHeaderByCode(trimmed);
    }
}
