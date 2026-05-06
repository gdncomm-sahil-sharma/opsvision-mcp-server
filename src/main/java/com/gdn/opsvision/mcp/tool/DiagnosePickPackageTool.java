package com.gdn.opsvision.mcp.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.InventoryForItemEvidence.WarehouseItemMaster;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.BatchConsolidation;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.DemandShortage;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.InterpretiveHints;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickListAllocation;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickPackagePriority;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickPackageState;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerStatusBreakdown;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.ReplenishmentSignal;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.SourceAreaCoverage;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.WorkflowSignals;
import com.gdn.opsvision.mcp.repository.InventoryRepository;
import com.gdn.opsvision.mcp.repository.PickPackageDiagnosisRepository;
import com.gdn.opsvision.mcp.repository.PickPackageDiagnosisRepository.BatchSiblingRow;
import com.gdn.opsvision.mcp.repository.PickPackageDiagnosisRepository.DemandRow;
import com.gdn.opsvision.mcp.repository.PickPackageDiagnosisRepository.PickListAllocationRow;
import com.gdn.opsvision.mcp.repository.PickPackageDiagnosisRepository.PpPriorityRow;
import com.gdn.opsvision.mcp.repository.PickPackageDiagnosisRepository.PpStateRow;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository.PickerRow;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository.ZoneRow;

/**
 * Composite tool answering "why isn't this pick package being picked / progressing?". Reads
 * single-PP state across stockholm + cross-DB inventory, derives a flat boolean signal
 * block, and surfaces plain-English meaning for the picking_status enum so the agent can
 * quote it. Returns FACTS, not VERDICTS.
 */
@Service
public class DiagnosePickPackageTool {

    /**
     * {@code PickPackageStatus} enum mapped by ordinal. Hibernate persists pp.status as
     * EnumType.ORDINAL (the entity has no @Enumerated; default is ORDINAL). Verified
     * against QA2: status=4 → AWB_PENDING, 6 → SHIPMENT_BOOKING_FAILED, 9 →
     * WAITING_FOR_SHIPMENT_REQUEST, etc.
     */
    private static final String[] PP_STATUS_LABELS = {
            "OPEN",                          // 0
            "WEIGHT_CAPTURE_PENDING",        // 1
            "WEIGHT_CAPTURE_DONE",           // 2
            "GIN_COMPLETE",                  // 3
            "AWB_PENDING",                   // 4
            "AWB_RECEIVED",                  // 5
            "SHIPMENT_BOOKING_FAILED",       // 6
            "ADDED_TO_SHIPMENT_REQUEST",     // 7
            "PARTIAL_GIN_COMPLETE",          // 8
            "WAITING_FOR_SHIPMENT_REQUEST",  // 9
            "CANCELLATION_PENDING"           // 10
    };

    /** Plain-English meaning for each {@code PickPackageStatus} ordinal. */
    private static final Map<String, String> PP_STATUS_MEANING = Map.ofEntries(
            Map.entry("OPEN", "Default state — PP is open. picking_status carries the active sub-state."),
            Map.entry("WEIGHT_CAPTURE_PENDING", "PP picked + at packing station; awaiting weight capture before GIN."),
            Map.entry("WEIGHT_CAPTURE_DONE", "Weight captured; ready for GIN."),
            Map.entry("GIN_COMPLETE", "Goods Issue Note submitted; downstream of picking."),
            Map.entry("AWB_PENDING", "Awaiting AWB (airway bill) from logistics provider. Picking is done. Not a picking issue."),
            Map.entry("AWB_RECEIVED", "AWB received from logistics; ready for shipment-request creation."),
            Map.entry("SHIPMENT_BOOKING_FAILED", "Shipment booking with logistics provider failed (operational/third-party). Check logistic_option_code + AWB info; likely needs a retry, not a picking fix."),
            Map.entry("ADDED_TO_SHIPMENT_REQUEST", "PP attached to a shipment request; awaiting carrier handoff."),
            Map.entry("PARTIAL_GIN_COMPLETE", "Partial GIN issued (split shipment); remaining items still in flight."),
            Map.entry("WAITING_FOR_SHIPMENT_REQUEST", "Downstream of picking. Outbound shipment-request creation hasn't run yet — waiting on logistics-side workflow."),
            Map.entry("CANCELLATION_PENDING", "PP-level cancellation initiated; awaiting confirmation before stock/state release."));

    /** Plain-English meaning for each {@code PriorityCalStatus} value the PP can carry. */
    private static final Map<String, String> PICKING_STATUS_MEANING = Map.ofEntries(
            Map.entry("PRIORITY_CAL_PENDING", "Priority calculation pending — pre-pick. PP is queued for priority assignment, not yet a picker candidate."),
            Map.entry("PRIORITY_CAL_DONE", "Priority calculation finished — pre-pick. Set during priority calc, well BEFORE picking starts. Not a 'picking done' signal."),
            Map.entry("READY_FOR_MANUAL_PICKING", "Pre-pick. Pick list eligible for manual-picking workflow; awaiting picker claim."),
            Map.entry("STORAGE_STOCK_RESERVED", "Pre-pick. Aggregate stock reserved; awaiting downstream pick-list / picker assignment."),
            Map.entry("STORAGE_NOT_AVAILABLE", "Pre-pick — system can't find storage for this PP. Stock isn't at the forward pick face yet; replenishment from reserve area pending. NOT a bug if stock is just not yet replenished."),
            Map.entry("WAITING_FOR_PUTAWAY", "Pre-pick. Putaway hasn't completed — stock physically not in pickable bins yet."),
            Map.entry("CATEGORY_EXPIRY_RULE_NOT_SET", "Pre-pick — configuration gap. The category-expiry rule isn't configured; PP can't proceed until ops adds the rule."),
            Map.entry("PICK_LIST_GENERATED", "Pre-pick. Pick list created but PP hasn't transitioned to PARTIAL_PACKAGE yet."),
            Map.entry("PARTIAL_PACKAGE", "Pick list has been generated for at least part of this PP — picking pending or in flight (set during pick-list creation, NOT post-pick). Common state for in-flight PPs."),
            Map.entry("PROBLEM_SOLVE", "PP routed to problem-solve queue — manual intervention required. Picker queue won't surface this until ops resolves it."),
            Map.entry("PICKING_COMPLETE", "Picking finished. Downstream stages (QC, packing) take over."),
            Map.entry("REACHED_TO_QC", "Past picking. Handling units transitioned out of pick zones into QC. Issue (if any) is downstream of picking."),
            Map.entry("QC_COMPLETE", "Past picking and QC. Ready for packing/handover stages."));

    private final PickPackageDiagnosisRepository diagnosisRepo;
    private final PickerAccessRepository pickerAccessRepo;
    private final InventoryRepository inventoryRepo;

    public DiagnosePickPackageTool(
            PickPackageDiagnosisRepository diagnosisRepo,
            PickerAccessRepository pickerAccessRepo,
            InventoryRepository inventoryRepo) {
        this.diagnosisRepo = diagnosisRepo;
        this.pickerAccessRepo = pickerAccessRepo;
        this.inventoryRepo = inventoryRepo;
    }

    @Tool(description = """
            Composite tool: "why isn't this pick package being picked / progressing?". Single \
            PP in, structured evidence pack with seven sections out: state, priority, per-\
            pick_list allocation + eligible-picker counts, source-area zone coverage, \
            replenishment deficit, a flat boolean signal block, and plain-English picking_status \
            meaning.

            Use this as the entry point for "user-not-aware-workflow" investigations — cases \
            where ops thinks the PP is broken but the system is correctly in a normal-but-\
            unintuitive state. Covers (at minimum) the four scenarios the SCPS team most \
            commonly sees:
              1. Picker has no access to the PP's source zones.
              2. PP's zone has no active picker.
              3. PP blocked because replenishment from reserve to forward-pick hasn't run.
              4. PP in queue but lower priority — too many higher-priority PPs ahead.
            Plus structured signals for PROBLEM_SOLVE / WAITING_FOR_PUTAWAY / cancelled / \
            deprioritized / rejected / already-assigned.

            ID format: pp.code (e.g. 'PK/MAR-01/V-2026/225374') OR numeric pp.id. Same idOrCode \
            convention as evaluatePickListReadiness.

            The 'signals' block is the agent's quick-scan: isStorageNotAvailable, isInProblemSolve, \
            isAlreadyAssigned, hasNoEligiblePickerForAnyOpenPickList, hasEligiblePickersButNoneAvailable, \
            hasReplenishmentDeficit, isLowerPriorityInBusyZone (compare queueRankAmongOpen vs \
            openPickListsInZone), etc. Each is a single boolean.

            'pickListAllocations[].queueRankAmongOpen' lets the agent quantify the queue-position \
            scenario: 'rank 47 of 80 OPEN pick_lists in this zone' is a clear "lower-priority, not \
            broken" answer. Mirrors the production picker-queue ordering in PickListRepo.java:85-92 \
            (precedence asc nulls last → priority desc → sub_level_priority desc → created_date asc).

            'sourceAreas' surfaces the storage-table bridge from source_area_code (e.g. 'M4-STOR') \
            to specific work-zone codes. Many-to-many in practice (M4-STOR resolves to 15 zones at \
            MAR; GF-STOR to 51). The list of resolved zone codes is capped; the eligible-picker \
            count is computed against the FULL list, not the capped sample.

            'hints.pickingStatusMeaning' is plain-English text the agent can quote. Especially \
            useful for non-obvious values: PRIORITY_CAL_DONE is PRE-pick (not "picking done"); \
            PARTIAL_PACKAGE is set during pick-list creation, NOT post-pick.

            Returns FACTS, not VERDICTS. Not-found returns found=false with all sections null.
            """)
    public PickPackageDiagnosisEvidence diagnosePickPackage(
            @ToolParam(description = "Pick package code (e.g. 'PK/MAR-01/V-2026/...') or numeric id") String idOrCode) {

        Optional<Long> ppIdOpt = lookupPpId(idOrCode);
        if (ppIdOpt.isEmpty()) {
            return notFound(idOrCode);
        }
        long ppId = ppIdOpt.get();

        Optional<PpStateRow> stateOpt = diagnosisRepo.findStateById(ppId);
        if (stateOpt.isEmpty()) {
            return notFound(idOrCode);
        }
        PpStateRow s = stateOpt.get();
        PickPackageState state = mapState(s);

        PickPackagePriority priority = diagnosisRepo.findPriorityById(ppId)
                .map(this::mapPriority).orElse(null);

        // §3 pick_list allocations + per-zone eligible-picker pool + queue rank
        List<PickListAllocationRow> plRows = diagnosisRepo.findPickListAllocationsByPpId(ppId);
        List<PickListAllocation> pickListAllocations = new ArrayList<>(plRows.size());
        for (PickListAllocationRow pl : plRows) {
            List<String> sourceAreaCodes = diagnosisRepo.findSourceAreasByPickListAndPp(pl.pickListId(), ppId);
            EligiblePickerInfo info = computeEligible(pl.allottedZoneId(), s.siteCode());
            Integer rank = null;
            Integer openInZone = null;
            if ("OPEN".equalsIgnoreCase(pl.pickListStatus()) && pl.pickerId() == null) {
                rank = pickerAccessRepo.rankOpenPickListInZone(
                        pl.pickListId(), pl.allottedZoneId(),
                        pl.pickingPriorityPrecedence(), pl.priority(),
                        pl.subLevelPriority(), pl.createdDate());
                openInZone = pickerAccessRepo.countOpenPickListsInZone(pl.allottedZoneId());
            }
            pickListAllocations.add(new PickListAllocation(
                    pl.pickListId(),
                    pl.pickListStatus(),
                    pl.allottedZoneId(),
                    pl.allottedZoneCode(),
                    pl.pickerId(),
                    pl.pickerCode(),
                    pl.pickerStatus(),
                    pl.priority(),
                    pl.pickingPriorityLevelId(),
                    pl.pickingPriorityPrecedence(),
                    pl.subLevelPriority(),
                    pl.createdDate(),
                    sourceAreaCodes,
                    info.count,
                    info.breakdown,
                    rank,
                    openInZone));
        }

        // §4 source areas → zones → eligible pickers (warehouse-level fan-out)
        List<String> distinctSourceAreas = diagnosisRepo.findDistinctSourceAreasByPpId(ppId);
        List<SourceAreaCoverage> sourceAreas = new ArrayList<>(distinctSourceAreas.size());
        for (String area : distinctSourceAreas) {
            List<ZoneRow> zones = pickerAccessRepo.findZonesForSourceArea(area, s.siteCode());
            int max = pickerAccessRepo.maxZonesPerArea();
            boolean truncated = zones.size() > max;
            int distinctCount = truncated ? max : zones.size();
            List<ZoneRow> capped = truncated ? zones.subList(0, max) : zones;
            List<String> zoneCodes = capped.stream().map(ZoneRow::zoneCode).toList();
            List<Long> zoneIds = capped.stream().map(ZoneRow::id).toList();
            EligiblePickerInfo info = zoneIds.isEmpty()
                    ? EligiblePickerInfo.empty()
                    : computeEligibleForZones(zoneIds, s.siteCode());
            sourceAreas.add(new SourceAreaCoverage(
                    area,
                    distinctCount,
                    zoneCodes,
                    truncated,
                    info.count,
                    info.breakdown));
        }

        // §5 replenishment / SNA — per-SKU deficit
        ReplenishmentSignal replenishment = computeReplenishment(ppId, s);

        // §5b batch / wave consolidation — sibling breakdown
        BatchConsolidation batchConsolidation = computeBatchConsolidation(ppId, s);

        // §6 derived booleans
        WorkflowSignals signals = computeSignals(s, pickListAllocations, replenishment, distinctSourceAreas, batchConsolidation);

        // §7 hints
        InterpretiveHints hints = buildHints(s.pickingStatus(), state.statusLabel(), signals, batchConsolidation);

        return new PickPackageDiagnosisEvidence(
                s.ppCode(),
                /*found=*/true,
                state,
                priority,
                pickListAllocations,
                sourceAreas,
                replenishment,
                batchConsolidation,
                signals,
                hints);
    }

    // ─── lookup + mapping helpers ────────────────────────────────────────────

    private Optional<Long> lookupPpId(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            return Optional.empty();
        }
        String trimmed = idOrCode.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            long parsed = Long.parseLong(trimmed);
            return diagnosisRepo.idExists(parsed) ? Optional.of(parsed) : Optional.empty();
        }
        return diagnosisRepo.findIdByCode(trimmed);
    }

    private static PickPackageState mapState(PpStateRow s) {
        return new PickPackageState(
                s.ppId(),
                s.ppCode(),
                s.status(),
                statusLabel(s.status()),
                s.pickingStatus(),
                s.canceled(),
                s.inProgress(),
                s.shortPick(),
                s.deprioritized(),
                s.rejected(),
                s.priorityBoosted(),
                s.assignedPickerId(),
                s.assignedPickerCode(),
                s.assignedPickerDate(),
                s.distributionZoneCode(),
                s.batchId(),
                s.batchType(),
                s.waveNumber(),
                s.createdDate(),
                s.updatedDate(),
                s.autoCancelDate(),
                s.siteCode());
    }

    private static String statusLabel(int status) {
        if (status >= 0 && status < PP_STATUS_LABELS.length) {
            return PP_STATUS_LABELS[status];
        }
        return "STATUS_" + status;
    }

    private PickPackagePriority mapPriority(PpPriorityRow p) {
        return new PickPackagePriority(
                p.topPriority(),
                p.topPriorityCode(),
                p.priority(),
                p.subLevelPriority(),
                p.priorityBoosted(),
                p.pickingPriorityLevelId(),
                p.pickingPriorityLevelName(),
                p.pickingPriorityPrecedence());
    }

    private static PickPackageDiagnosisEvidence notFound(String code) {
        return new PickPackageDiagnosisEvidence(
                code, /*found=*/false, null, null, List.of(), List.of(), null, null, null, null);
    }

    // ─── eligible-picker computation ────────────────────────────────────────

    private EligiblePickerInfo computeEligible(Long allottedZoneId, String siteCode) {
        if (allottedZoneId == null || siteCode == null) {
            return EligiblePickerInfo.empty();
        }
        return computeEligibleForZones(List.of(allottedZoneId), siteCode);
    }

    private EligiblePickerInfo computeEligibleForZones(List<Long> zoneIds, String siteCode) {
        List<PickerRow> pickers = pickerAccessRepo.findEligiblePickersForZones(zoneIds, siteCode);
        return new EligiblePickerInfo(pickers.size(), breakdown(pickers));
    }

    private static PickerStatusBreakdown breakdown(List<PickerRow> pickers) {
        int avail = 0, busy = 0, off = 0, brkInit = 0, brkRej = 0, occ = 0, other = 0;
        for (PickerRow p : pickers) {
            String s = p.status() == null ? "" : p.status();
            switch (s) {
                case "AVAILABLE" -> avail++;
                case "BUSY" -> busy++;
                case "OFFLINE" -> off++;
                case "BREAK_INITIATED" -> brkInit++;
                case "BREAK_REJECT_PICKLIST" -> brkRej++;
                case "OCCUPIED" -> occ++;
                default -> other++;
            }
        }
        return new PickerStatusBreakdown(avail, busy, off, brkInit, brkRej, occ, other);
    }

    private record EligiblePickerInfo(int count, PickerStatusBreakdown breakdown) {
        static EligiblePickerInfo empty() {
            return new EligiblePickerInfo(0, new PickerStatusBreakdown(0, 0, 0, 0, 0, 0, 0));
        }
    }

    // ─── replenishment / SNA ────────────────────────────────────────────────

    private ReplenishmentSignal computeReplenishment(long ppId, PpStateRow s) {
        boolean sna = "STORAGE_NOT_AVAILABLE".equals(s.pickingStatus());
        List<DemandRow> demand = diagnosisRepo.findRemainingDemandByPpId(ppId);
        if (demand.isEmpty() || s.siteCode() == null) {
            return new ReplenishmentSignal(sna, List.of());
        }
        // Sum remaining demand per SKU (PP can have multiple picking_items for the same SKU).
        Map<String, Integer> demandBySku = new LinkedHashMap<>();
        for (DemandRow d : demand) {
            demandBySku.merge(d.skuCode(), d.remainingDemand(), Integer::sum);
        }
        List<DemandShortage> shortages = new ArrayList<>(demandBySku.size());
        for (Map.Entry<String, Integer> e : demandBySku.entrySet()) {
            String sku = e.getKey();
            int remaining = e.getValue();
            List<WarehouseItemMaster> wims = inventoryRepo.findStockForItem(sku, s.siteCode());
            Integer unrestrictedAvail = wims.isEmpty() ? null : wims.stream()
                    .filter(w -> "UNRESTRICTED".equalsIgnoreCase(w.stockIndicator()))
                    .mapToInt(w -> w.aggregateAvailableQty() == null ? 0 : w.aggregateAvailableQty())
                    .sum();
            int deficit = unrestrictedAvail == null
                    ? remaining
                    : Math.max(0, remaining - unrestrictedAvail);
            shortages.add(new DemandShortage(sku, remaining, unrestrictedAvail, deficit));
        }
        return new ReplenishmentSignal(sna, shortages);
    }

    // ─── batch / wave consolidation ─────────────────────────────────────────

    private BatchConsolidation computeBatchConsolidation(long ppId, PpStateRow s) {
        boolean inBatch = (s.batchId() != null && !s.batchId().isBlank())
                || (s.waveNumber() != null && !s.waveNumber().isBlank());
        if (!inBatch) {
            return new BatchConsolidation(
                    false, s.batchId(), s.batchType(), s.waveNumber(), 0,
                    Map.of(), Map.of());
        }
        List<BatchSiblingRow> siblings = diagnosisRepo.findBatchSiblings(
                s.batchId(), s.waveNumber(), ppId);
        Map<String, Integer> pickingBreakdown = new LinkedHashMap<>();
        Map<String, Integer> ppStatusBreakdown = new LinkedHashMap<>();
        for (BatchSiblingRow r : siblings) {
            String ps = r.pickingStatus() == null ? "(null)" : r.pickingStatus();
            pickingBreakdown.merge(ps, 1, Integer::sum);
            String label = statusLabel(r.status());
            ppStatusBreakdown.merge(label, 1, Integer::sum);
        }
        return new BatchConsolidation(
                true, s.batchId(), s.batchType(), s.waveNumber(),
                siblings.size(), pickingBreakdown, ppStatusBreakdown);
    }

    // ─── boolean signal derivation ──────────────────────────────────────────

    private static WorkflowSignals computeSignals(
            PpStateRow s,
            List<PickListAllocation> allocations,
            ReplenishmentSignal replenishment,
            List<String> distinctSourceAreas,
            BatchConsolidation batchConsolidation) {
        String ps = s.pickingStatus() == null ? "" : s.pickingStatus();
        int statusInt = s.status();
        boolean isCanceled = s.canceled();
        boolean isDeprioritized = Boolean.TRUE.equals(s.deprioritized());
        boolean isRejected = Boolean.TRUE.equals(s.rejected());
        boolean isPriorityBoosted = Boolean.TRUE.equals(s.priorityBoosted());
        boolean isAssigned = s.assignedPickerId() != null;

        boolean hasOpenPl = false;
        boolean allOpenLackEligible = true;
        boolean anyOpenHasEligibleNoneAvailable = false;
        for (PickListAllocation a : allocations) {
            if ("OPEN".equalsIgnoreCase(a.pickListStatus()) && a.pickerId() == null) {
                hasOpenPl = true;
                if (a.eligiblePickerCount() > 0) {
                    allOpenLackEligible = false;
                    if (a.eligiblePickerStatus() != null
                            && a.eligiblePickerStatus().available() == 0) {
                        anyOpenHasEligibleNoneAvailable = true;
                    }
                }
            }
        }
        boolean hasNoEligibleForAnyOpen = hasOpenPl && allOpenLackEligible;
        boolean hasReplenishmentDeficit = replenishment != null
                && replenishment.shortages().stream().anyMatch(ds -> ds.deficit() > 0);

        return new WorkflowSignals(
                // picking_status booleans
                isCanceled,
                isDeprioritized,
                isRejected,
                isPriorityBoosted,
                isAssigned,
                "PROBLEM_SOLVE".equals(ps),
                "STORAGE_NOT_AVAILABLE".equals(ps),
                "WAITING_FOR_PUTAWAY".equals(ps),
                "PRIORITY_CAL_PENDING".equals(ps),
                "PARTIAL_PACKAGE".equals(ps),
                "READY_FOR_MANUAL_PICKING".equals(ps),
                "REACHED_TO_QC".equals(ps),
                "PICKING_COMPLETE".equals(ps),
                // pp.status (PickPackageStatus enum) booleans
                statusInt == 1,  // WEIGHT_CAPTURE_PENDING
                statusInt == 2,  // WEIGHT_CAPTURE_DONE
                statusInt == 3,  // GIN_COMPLETE
                statusInt == 8,  // PARTIAL_GIN_COMPLETE
                statusInt == 4,  // AWB_PENDING
                statusInt == 5,  // AWB_RECEIVED
                statusInt == 6,  // SHIPMENT_BOOKING_FAILED
                statusInt == 7,  // ADDED_TO_SHIPMENT_REQUEST
                statusInt == 9,  // WAITING_FOR_SHIPMENT_REQUEST
                statusInt == 10, // CANCELLATION_PENDING
                // derived
                hasOpenPl,
                hasNoEligibleForAnyOpen,
                anyOpenHasEligibleNoneAvailable,
                hasReplenishmentDeficit,
                distinctSourceAreas.size() > 1,
                batchConsolidation != null && batchConsolidation.inBatch());
    }

    // ─── hints ──────────────────────────────────────────────────────────────

    private static InterpretiveHints buildHints(
            String pickingStatus,
            String ppStatusLabel,
            WorkflowSignals signals,
            BatchConsolidation batchConsolidation) {
        String pickingMeaning = pickingStatus == null
                ? null
                : PICKING_STATUS_MEANING.getOrDefault(pickingStatus,
                        "Unknown picking_status enum value; check PriorityCalStatus.java for additions.");
        String ppStatusMeaning = ppStatusLabel == null ? null
                : PP_STATUS_MEANING.getOrDefault(ppStatusLabel, null);
        // Compose pickingStatusMeaning to surface BOTH pp.status and picking_status when
        // the pp.status is post-pick / shipment-pipeline (more informative than picking_status alone).
        String composed = composeMeaning(pickingMeaning, ppStatusLabel, ppStatusMeaning);

        List<String> applicable = new ArrayList<>();
        if (signals.isReachedToQc() || signals.isPickingComplete()) {
            applicable.add("This PP is past the picking gate. If it appears stuck, the issue is downstream of picking — check handling-unit packing handoff (selected_for_packing / packing_order presence).");
        }
        if (signals.isStorageNotAvailable()) {
            applicable.add("STORAGE_NOT_AVAILABLE means the system can't find storage with stock for this PP. Cross-check replenishment.shortages: if aggregateUnrestrictedAvailable > 0 but deficit > 0, it's the 'SNA-but-stock-exists-elsewhere' pattern from SCPS-54110.");
        }
        if (signals.isInProblemSolve()) {
            applicable.add("PP is in problem-solve queue — manual ops intervention required before it returns to the picker queue.");
        }
        if (signals.hasNoEligiblePickerForAnyOpenPickList()) {
            applicable.add("No picker has access to the OPEN pick_list(s)' allotted zone(s). Either no picker_zone_group is configured for these zones, or all eligible pickers are deleted/inactive.");
        } else if (signals.hasEligiblePickersButNoneAvailable()) {
            applicable.add("Pickers exist for the allotted zone(s) but none are AVAILABLE — they're BUSY / OFFLINE / on break. PP will be picked when one frees up.");
        }
        if (signals.isCanceled()) {
            applicable.add("PP is canceled — won't be picked. Check why ops cancelled it.");
        }
        if (signals.isDeprioritized()) {
            applicable.add("PP has deprioritized=true — pulled out of the picker queue regardless of priority numbers.");
        }
        if (signals.isRejected()) {
            applicable.add("PP has rejected=true — explicitly removed from the picker queue.");
        }
        if (signals.isAlreadyAssigned()) {
            applicable.add("PP already has a picker assigned (state.assignedPickerId). It IS being worked; the question may be 'why is it slow' rather than 'why no picker'.");
        }
        // pp.status (shipment pipeline) hints — these mean picking is done; defer.
        if (signals.isAwbPending()) {
            applicable.add("AWB_PENDING — PP picked + GIN-ready, awaiting AWB from logistics provider. Picking-side answer is 'done'; this is a third-party / outbound pipeline state.");
        }
        if (signals.isShipmentBookingFailed()) {
            applicable.add("SHIPMENT_BOOKING_FAILED — operational/third-party. Check logistic_option_code + AWB info; usually resolved by retry, not a picking fix.");
        }
        if (signals.isWaitingForShipmentRequest()) {
            applicable.add("WAITING_FOR_SHIPMENT_REQUEST — outbound shipment-request creation hasn't run. Downstream of picking; not a picker-queue issue.");
        }
        if (signals.isAddedToShipmentRequest()) {
            applicable.add("ADDED_TO_SHIPMENT_REQUEST — PP attached to a shipment request, awaiting carrier handoff. Picking is done.");
        }
        if (signals.isWeightCapturePending()) {
            applicable.add("WEIGHT_CAPTURE_PENDING — PP at packing station, awaiting weight capture. Picking is done; weighing operator hasn't processed it yet.");
        }
        if (signals.isCancellationPending()) {
            applicable.add("CANCELLATION_PENDING — PP-level cancellation initiated, awaiting confirmation. Don't expect picker activity.");
        }
        // batch / wave hints
        if (batchConsolidation != null && batchConsolidation.inBatch() && batchConsolidation.siblingCount() > 0) {
            String batchOrWave = batchConsolidation.batchId() != null && !batchConsolidation.batchId().isBlank()
                    ? "batch"
                    : "wave";
            applicable.add("PP is in a multi-PP " + batchOrWave + " (" + batchConsolidation.siblingCount() +
                    " siblings). Inspect batchConsolidation.siblingPickingStatusBreakdown — if most siblings are at REACHED_TO_QC and this PP isn't, it's behind the wave; if this PP is REACHED_TO_QC and siblings aren't, the batch is waiting on stragglers.");
        }
        return new InterpretiveHints(composed, applicable);
    }

    private static String composeMeaning(String pickingMeaning, String ppStatusLabel, String ppStatusMeaning) {
        if (ppStatusLabel != null && !"OPEN".equals(ppStatusLabel) && ppStatusMeaning != null) {
            return "[pp.status=" + ppStatusLabel + "] " + ppStatusMeaning
                    + (pickingMeaning != null ? " | [picking_status] " + pickingMeaning : "");
        }
        return pickingMeaning;
    }
}
