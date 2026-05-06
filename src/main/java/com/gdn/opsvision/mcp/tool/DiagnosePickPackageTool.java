package com.gdn.opsvision.mcp.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.InventoryForItemEvidence.WarehouseItemMaster;
import com.gdn.opsvision.mcp.dto.LifecycleStage;
import com.gdn.opsvision.mcp.dto.MovementHistoryEvidence;
import com.gdn.opsvision.mcp.dto.PackingOrderLifecycleStage;
import com.gdn.opsvision.mcp.dto.PickListLifecycleStage;
import com.gdn.opsvision.mcp.dto.PickingTaskLifecycleStage;
import com.gdn.opsvision.mcp.dto.PickingTaskRequestLifecycleStage;
import com.gdn.opsvision.mcp.dto.SignalKind;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.BatchConsolidation;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.DemandShortage;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PackingOrderInfo;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickListAllocation;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickPackagePriority;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickPackageState;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerStatusBreakdown;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.ReplenishmentSignal;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.SourceAreaCoverage;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.StatusInterpretation;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.WorkflowSignals;
import com.gdn.opsvision.mcp.repository.InventoryRepository;
import com.gdn.opsvision.mcp.repository.MovementRepository;
import com.gdn.opsvision.mcp.repository.MovementRepository.TaskRequestRow;
import com.gdn.opsvision.mcp.repository.MovementRepository.TaskRow;
import com.gdn.opsvision.mcp.repository.PickPackageDiagnosisRepository;
import com.gdn.opsvision.mcp.repository.PickPackageDiagnosisRepository.BatchSiblingRow;
import com.gdn.opsvision.mcp.repository.PickPackageDiagnosisRepository.DemandRow;
import com.gdn.opsvision.mcp.repository.PickPackageDiagnosisRepository.PackingOrderRow;
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
     * WAITING_FOR_SHIPMENT_REQUEST, etc. Ordinal index = ordinal in the enum file.
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

    private static final String PICKING_STATUS_SOURCE_REF =
            "stockholm/InventoryModel/src/main/java/com/gdn/inventory/entity/PriorityCalStatus.java";
    private static final String PP_STATUS_SOURCE_REF =
            "stockholm/InventoryUtilities/src/main/java/com/gdn/inventory/type/PickPackageStatus.java";

    /**
     * Signal-name → {@link SignalKind} classification. The kinds encode the dominance
     * hierarchy: OPERATOR_OVERRIDE before BLOCKER_* before STAGE before CONTEXT.
     *
     * <p>Every WorkflowSignals boolean field has an entry here. The
     * {@code allKnownSignalsClassified} test asserts coverage; CI fails if a new
     * signal field lacks a kind.
     */
    static final Map<String, SignalKind> SIGNAL_KINDS = Map.<String, SignalKind>ofEntries(
            // Operator-controlled overrides — explicit human/system overrides on the PP.
            Map.entry("isCanceled",          SignalKind.OPERATOR_OVERRIDE),
            Map.entry("isDeprioritized",     SignalKind.OPERATOR_OVERRIDE),
            Map.entry("isRejected",          SignalKind.OPERATOR_OVERRIDE),
            Map.entry("isPriorityBoosted",   SignalKind.OPERATOR_OVERRIDE),
            Map.entry("isAlreadyAssigned",   SignalKind.OPERATOR_OVERRIDE),
            Map.entry("isCancellationPending", SignalKind.OPERATOR_OVERRIDE),
            // External blockers — third-party / upstream waits.
            Map.entry("isStorageNotAvailable",    SignalKind.BLOCKER_EXTERNAL),
            Map.entry("isAwaitingPutaway",        SignalKind.BLOCKER_EXTERNAL),
            Map.entry("isAwbPending",             SignalKind.BLOCKER_EXTERNAL),
            Map.entry("isShipmentBookingFailed",  SignalKind.BLOCKER_EXTERNAL),
            Map.entry("isWaitingForShipmentRequest", SignalKind.BLOCKER_EXTERNAL),
            // Internal blockers — system / config issues.
            Map.entry("isInProblemSolve",                       SignalKind.BLOCKER_INTERNAL),
            Map.entry("isPriorityCalPending",                   SignalKind.BLOCKER_INTERNAL),
            Map.entry("hasNoEligiblePickerForAnyOpenPickList",  SignalKind.BLOCKER_INTERNAL),
            Map.entry("hasEligiblePickersButNoneAvailable",     SignalKind.BLOCKER_INTERNAL),
            Map.entry("hasReplenishmentDeficit",                SignalKind.BLOCKER_INTERNAL),
            Map.entry("packingOrderMissing",                    SignalKind.BLOCKER_INTERNAL),
            Map.entry("hasFailedMovementTask",                  SignalKind.BLOCKER_INTERNAL),
            // Stage indicators — current lifecycle position.
            Map.entry("isPartialPackage",        SignalKind.STAGE),
            Map.entry("isReadyForManualPicking", SignalKind.STAGE),
            Map.entry("isReachedToQc",           SignalKind.STAGE),
            Map.entry("isPickingComplete",       SignalKind.STAGE),
            Map.entry("isWeightCapturePending",  SignalKind.STAGE),
            Map.entry("isWeightCaptureDone",     SignalKind.STAGE),
            Map.entry("isGinComplete",           SignalKind.STAGE),
            Map.entry("isPartialGinComplete",    SignalKind.STAGE),
            Map.entry("isAwbReceived",           SignalKind.STAGE),
            Map.entry("isAddedToShipmentRequest", SignalKind.STAGE),
            // Context — supplementary information about queue/batch shape.
            Map.entry("hasAnyOpenPickList",      SignalKind.CONTEXT),
            Map.entry("hasMultipleSourceAreas",  SignalKind.CONTEXT),
            Map.entry("isInBatchOrWave",         SignalKind.CONTEXT),
            Map.entry("hasOpenTaskRequest",      SignalKind.CONTEXT),
            Map.entry("hasOpenMovementTask",     SignalKind.CONTEXT));

    /** Internal: lifecycle stage + one-line meaning per status value. */
    private record StatusInfo(LifecycleStage stage, String meaning) {
    }

    /** {@code PriorityCalStatus} → stage + short meaning. 13 values. */
    private static final Map<String, StatusInfo> PICKING_STATUS_INFO = Map.ofEntries(
            Map.entry("PRIORITY_CAL_PENDING", new StatusInfo(LifecycleStage.PRE_PICK_LIST,
                    "Priority calculation pending; pick list not generated yet.")),
            Map.entry("PRIORITY_CAL_DONE", new StatusInfo(LifecycleStage.PRE_PICK_LIST,
                    "Priority calc finished; PRE-pick (set during priority calc, NOT a 'picking done' signal).")),
            Map.entry("READY_FOR_MANUAL_PICKING", new StatusInfo(LifecycleStage.PICK_LIST_GENERATED,
                    "Pick list eligible for manual-picking workflow; awaiting picker claim.")),
            Map.entry("STORAGE_STOCK_RESERVED", new StatusInfo(LifecycleStage.PRE_PICK_LIST,
                    "Aggregate stock reserved; awaiting downstream pick-list / picker assignment.")),
            Map.entry("STORAGE_NOT_AVAILABLE", new StatusInfo(LifecycleStage.PRE_PICK_LIST_BLOCKED,
                    "System can't find storage with stock for this PP; replenishment from reserve area pending.")),
            Map.entry("WAITING_FOR_PUTAWAY", new StatusInfo(LifecycleStage.PRE_PICK_LIST_BLOCKED,
                    "Putaway hasn't completed; stock not in pickable bins yet.")),
            Map.entry("CATEGORY_EXPIRY_RULE_NOT_SET", new StatusInfo(LifecycleStage.PRE_PICK_LIST_BLOCKED,
                    "Category-expiry rule not configured; PP can't proceed until ops adds the rule.")),
            Map.entry("PICK_LIST_GENERATED", new StatusInfo(LifecycleStage.PICK_LIST_GENERATED,
                    "Pick list created.")),
            Map.entry("PARTIAL_PACKAGE", new StatusInfo(LifecycleStage.IN_FLIGHT,
                    "Pick list generated for at least part of this PP; picking pending or in flight (set during pick-list creation, NOT post-pick).")),
            Map.entry("PROBLEM_SOLVE", new StatusInfo(LifecycleStage.MANUAL_INTERVENTION,
                    "Routed to problem-solve queue; manual intervention required.")),
            Map.entry("PICKING_COMPLETE", new StatusInfo(LifecycleStage.POST_PICK,
                    "Picking finished; downstream stages take over.")),
            Map.entry("REACHED_TO_QC", new StatusInfo(LifecycleStage.POST_PICK,
                    "Past picking; HUs transitioned out of pick zones into QC.")),
            Map.entry("QC_COMPLETE", new StatusInfo(LifecycleStage.POST_PICK,
                    "Past picking and QC; ready for packing/handover.")));

    /** {@code PickPackageStatus} → stage + short meaning. 11 ordinals. */
    private static final Map<String, StatusInfo> PP_STATUS_INFO = Map.ofEntries(
            Map.entry("OPEN", new StatusInfo(LifecycleStage.DEFAULT,
                    "Default state; picking_status carries the active sub-state.")),
            Map.entry("WEIGHT_CAPTURE_PENDING", new StatusInfo(LifecycleStage.POST_PICK_PACKING,
                    "PP picked + at packing station; awaiting weight capture before GIN.")),
            Map.entry("WEIGHT_CAPTURE_DONE", new StatusInfo(LifecycleStage.POST_PICK_PACKING,
                    "Weight captured; ready for GIN.")),
            Map.entry("GIN_COMPLETE", new StatusInfo(LifecycleStage.POST_PICK_SHIPMENT_PIPELINE,
                    "Goods Issue Note submitted.")),
            Map.entry("AWB_PENDING", new StatusInfo(LifecycleStage.POST_PICK_SHIPMENT_PIPELINE,
                    "Awaiting AWB from logistics provider; downstream of picking.")),
            Map.entry("AWB_RECEIVED", new StatusInfo(LifecycleStage.POST_PICK_SHIPMENT_PIPELINE,
                    "AWB received; ready for shipment-request creation.")),
            Map.entry("SHIPMENT_BOOKING_FAILED", new StatusInfo(LifecycleStage.SHIPMENT_BLOCKED,
                    "Logistics-side booking failed; usually resolved by retry, not a picking fix.")),
            Map.entry("ADDED_TO_SHIPMENT_REQUEST", new StatusInfo(LifecycleStage.POST_PICK_SHIPMENT_PIPELINE,
                    "Attached to a shipment request; awaiting carrier handoff.")),
            Map.entry("PARTIAL_GIN_COMPLETE", new StatusInfo(LifecycleStage.POST_PICK_SHIPMENT_PIPELINE,
                    "Partial GIN issued (split shipment); remaining items in flight.")),
            Map.entry("WAITING_FOR_SHIPMENT_REQUEST", new StatusInfo(LifecycleStage.POST_PICK_SHIPMENT_PIPELINE,
                    "Outbound shipment-request creation hasn't run yet.")),
            Map.entry("CANCELLATION_PENDING", new StatusInfo(LifecycleStage.TERMINATING,
                    "PP-level cancellation initiated; awaiting confirmation.")));

    private final PickPackageDiagnosisRepository diagnosisRepo;
    private final PickerAccessRepository pickerAccessRepo;
    private final InventoryRepository inventoryRepo;
    private final MovementRepository movementRepo;

    public DiagnosePickPackageTool(
            PickPackageDiagnosisRepository diagnosisRepo,
            PickerAccessRepository pickerAccessRepo,
            InventoryRepository inventoryRepo,
            MovementRepository movementRepo) {
        this.diagnosisRepo = diagnosisRepo;
        this.pickerAccessRepo = pickerAccessRepo;
        this.inventoryRepo = inventoryRepo;
        this.movementRepo = movementRepo;
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

            'pickingStatusInterpretation' and 'ppStatusInterpretation' carry: a structured \
            'lifecycleStage' enum (PRE_PICK_LIST / PRE_PICK_LIST_BLOCKED / IN_FLIGHT / POST_PICK / \
            POST_PICK_PACKING / POST_PICK_SHIPMENT_PIPELINE / SHIPMENT_BLOCKED / MANUAL_INTERVENTION / \
            TERMINATING / DEFAULT / OTHER) for triage, plus a one-line 'meaning', plus a \
            'sourceRef' to the entity / enum file. Use lifecycleStage to compare and triage; quote \
            meaning when explaining to a user. Notable: PRIORITY_CAL_DONE is PRE_PICK_LIST (not \
            'picking done'); PARTIAL_PACKAGE is IN_FLIGHT (set during pick-list creation, NOT \
            post-pick).

            'signals.derivationNotes' is a per-derived-signal one-liner showing the rule and \
            inputs each derived boolean was computed under. Use it to sanity-check a signal \
            before quoting it — e.g. if hasNoEligiblePickerForAnyOpenPickList=true but the \
            derivation note says 'considered 0 OPEN+unassigned PLs', the signal is technically \
            vacuous.

            Vacuous-true suppression: derived booleans whose precondition is empty are forced \
            to false (not vacuously true). e.g. hasMultipleSourceAreas is false when no source \
            areas exist, not vacuously-false-because-distinct-areas-trivially-not-greater-than-1.

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
                    PickListLifecycleStage.forStatusAndPicker(pl.pickListStatus(), pl.pickerId()),
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

        // §5c packing_order presence + stage (Pattern C downstream confirmation)
        PackingOrderInfo packingOrder = computePackingOrder(ppId);

        // §5d movement-DB state — non-CLOSED rows only, sorted most-recent first.
        // Surfaces Pattern A (request stuck in HOLD) directly without chaining.
        List<MovementHistoryEvidence.TaskRequest> movementTaskRequests =
                fetchOpenMovementTaskRequests(ppId);
        List<MovementHistoryEvidence.Task> movementTasks =
                fetchOpenMovementTasks(ppId);

        // §6 derived booleans + per-derivation notes
        WorkflowSignals signals = computeSignals(s, pickListAllocations, replenishment,
                distinctSourceAreas, batchConsolidation, packingOrder,
                movementTaskRequests, movementTasks);

        // §7 structured status interpretations (replaces previous applicableHints prose)
        StatusInterpretation pickingInterp = buildPickingStatusInterpretation(s.pickingStatus());
        StatusInterpretation ppInterp = buildPpStatusInterpretation(state.statusLabel());

        return new PickPackageDiagnosisEvidence(
                s.ppCode(),
                /*found=*/true,
                state,
                priority,
                pickListAllocations,
                sourceAreas,
                replenishment,
                batchConsolidation,
                packingOrder,
                movementTaskRequests,
                movementTasks,
                signals,
                pickingInterp,
                ppInterp);
    }

    // ─── movement-DB fetch + non-CLOSED filter ──────────────────────────────

    private static final int MAX_MOVEMENT_ROWS = 20;

    private List<MovementHistoryEvidence.TaskRequest> fetchOpenMovementTaskRequests(long ppId) {
        return movementRepo.findRequestsForPp(ppId).stream()
                .filter(r -> !"CLOSED".equalsIgnoreCase(r.status()))
                .sorted((a, b) -> b.createdDate().compareTo(a.createdDate()))
                .limit(MAX_MOVEMENT_ROWS)
                .map(DiagnosePickPackageTool::mapTaskRequest)
                .toList();
    }

    private List<MovementHistoryEvidence.Task> fetchOpenMovementTasks(long ppId) {
        return movementRepo.findTasksForPp(ppId).stream()
                .filter(t -> !"CLOSED".equalsIgnoreCase(t.status()))
                .sorted((a, b) -> b.createdDate().compareTo(a.createdDate()))
                .limit(MAX_MOVEMENT_ROWS)
                .map(DiagnosePickPackageTool::mapTask)
                .toList();
    }

    private static MovementHistoryEvidence.TaskRequest mapTaskRequest(TaskRequestRow r) {
        return new MovementHistoryEvidence.TaskRequest(
                r.id(),
                r.status(),
                PickingTaskRequestLifecycleStage.forStatus(r.status()),
                r.previousStatus(),
                PickingTaskRequestLifecycleStage.forStatus(r.previousStatus()),
                r.createdDate(),
                r.lastModifiedDate(),
                r.lastModifiedBy(),
                r.referenceType(),
                r.targetAreaCode(),
                r.pickingType(),
                r.type(),
                r.multiSkuBatchFailedReason());
    }

    private static MovementHistoryEvidence.Task mapTask(TaskRow t) {
        return new MovementHistoryEvidence.Task(
                t.id(),
                t.status(),
                PickingTaskLifecycleStage.forStatus(t.status()),
                t.previousStatus(),
                PickingTaskLifecycleStage.forStatus(t.previousStatus()),
                t.createdDate(),
                t.lastModifiedDate(),
                t.lastModifiedBy(),
                t.pickingTaskRequestDetail(),
                t.pickingTaskList(),
                t.sourceAreaCode(),
                t.skuCode(),
                t.quantity(),
                t.automation(),
                t.stockTraceId(),
                t.type(),
                t.retryCount(),
                t.failureReason(),
                t.reason());
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
                code, /*found=*/false, null, null, List.of(), List.of(), null, null, null,
                List.of(), List.of(), null, null, null);
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

    // ─── packing order ──────────────────────────────────────────────────────

    /**
     * Resolve the latest packing_order for a PP. Returns a {@code present:false}
     * record if no row exists — this is the Pattern C downstream confirmation
     * (PP picked but no packing_order ever created).
     */
    PackingOrderInfo computePackingOrder(long ppId) {
        return diagnosisRepo.findLatestPackingOrderForPp(ppId)
                .map(DiagnosePickPackageTool::mapPackingOrder)
                .orElseGet(() -> new PackingOrderInfo(
                        false, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    private static PackingOrderInfo mapPackingOrder(PackingOrderRow r) {
        PackingOrderLifecycleStage stage = PackingOrderLifecycleStage.forPackingOrder(
                r.active(), r.claimedDate(), r.goodIssuedNote());
        return new PackingOrderInfo(
                true,
                r.id(),
                r.code(),
                stage,
                r.active(),
                r.awbInfo() != null,
                r.goodIssuedNote() != null,
                r.claimedBy(),
                r.claimedDate(),
                r.reClaimedBy(),
                r.reClaimedDate(),
                r.workZoneCode(),
                r.createdDate(),
                r.lastModifiedDate());
    }

    // ─── boolean signal derivation ──────────────────────────────────────────

    /**
     * Computes the WorkflowSignals block. Package-private so unit tests can drive it
     * with hand-built fixtures (no DB).
     *
     * <p>Vacuous-true suppression:
     * <ul>
     *   <li>{@code hasMultipleSourceAreas} requires distinctSourceAreas to be non-empty
     *       AND have size &gt; 1 (otherwise vacuous).</li>
     *   <li>{@code hasNoEligiblePickerForAnyOpenPickList} requires {@code hasOpenPl=true}
     *       (already enforced in the loop).</li>
     *   <li>{@code hasEligiblePickersButNoneAvailable} similarly requires an OPEN PL with
     *       eligible pickers existing.</li>
     *   <li>{@code hasReplenishmentDeficit} only fires if at least one shortage row has
     *       deficit &gt; 0 (false on empty shortage list).</li>
     * </ul>
     */
    static WorkflowSignals computeSignals(
            PpStateRow s,
            List<PickListAllocation> allocations,
            ReplenishmentSignal replenishment,
            List<String> distinctSourceAreas,
            BatchConsolidation batchConsolidation,
            PackingOrderInfo packingOrder,
            List<MovementHistoryEvidence.TaskRequest> movementTaskRequests,
            List<MovementHistoryEvidence.Task> movementTasks) {
        String ps = s.pickingStatus() == null ? "" : s.pickingStatus();
        int statusInt = s.status();
        boolean isCanceled = s.canceled();
        boolean isDeprioritized = Boolean.TRUE.equals(s.deprioritized());
        boolean isRejected = Boolean.TRUE.equals(s.rejected());
        boolean isPriorityBoosted = Boolean.TRUE.equals(s.priorityBoosted());
        boolean isAssigned = s.assignedPickerId() != null;

        int openUnassignedCount = 0;
        int openWithEligibleCount = 0;
        boolean hasOpenPl = false;
        boolean allOpenLackEligible = true;
        boolean anyOpenHasEligibleNoneAvailable = false;
        for (PickListAllocation a : allocations) {
            if ("OPEN".equalsIgnoreCase(a.pickListStatus()) && a.pickerId() == null) {
                openUnassignedCount++;
                hasOpenPl = true;
                if (a.eligiblePickerCount() > 0) {
                    openWithEligibleCount++;
                    allOpenLackEligible = false;
                    if (a.eligiblePickerStatus() != null
                            && a.eligiblePickerStatus().available() == 0) {
                        anyOpenHasEligibleNoneAvailable = true;
                    }
                }
            }
        }
        boolean hasNoEligibleForAnyOpen = hasOpenPl && allOpenLackEligible;

        int shortageCount = replenishment == null ? 0 : replenishment.shortages().size();
        long deficitRows = replenishment == null ? 0
                : replenishment.shortages().stream().filter(ds -> ds.deficit() > 0).count();
        boolean hasReplenishmentDeficit = deficitRows > 0;

        boolean hasMultipleSourceAreas =
                !distinctSourceAreas.isEmpty() && distinctSourceAreas.size() > 1;
        boolean inBatch = batchConsolidation != null && batchConsolidation.inBatch();

        // Pattern C downstream: post-pick PP missing its packing_order.
        boolean isPostPick = "REACHED_TO_QC".equals(ps) || "PICKING_COMPLETE".equals(ps)
                || "QC_COMPLETE".equals(ps);
        boolean packingOrderPresent = packingOrder != null && packingOrder.present();
        boolean packingOrderMissing = isPostPick && !packingOrderPresent;

        // Movement-DB existence + failure indicators. Lists carry the structural detail
        // (lifecycleStage on each row); these flags are convenience for "did the WCS
        // layer get involved at all" questions.
        boolean hasOpenTaskRequest =
                movementTaskRequests != null && !movementTaskRequests.isEmpty();
        boolean hasOpenMovementTask =
                movementTasks != null && !movementTasks.isEmpty();
        boolean hasFailedMovementTask = movementTasks != null
                && movementTasks.stream().anyMatch(t ->
                        (t.retryCount() != null && t.retryCount() > 0)
                                || (t.failureReason() != null && !t.failureReason().isBlank()));

        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("hasAnyOpenPickList",
                "TRUE iff any pickListAllocations row has status=OPEN AND pickerId=null. Considered "
                        + allocations.size() + " allocation row(s); "
                        + openUnassignedCount + " were OPEN+unassigned.");
        notes.put("hasNoEligiblePickerForAnyOpenPickList",
                "TRUE iff hasAnyOpenPickList AND every OPEN+unassigned PL has eligiblePickerCount==0. "
                        + "Considered " + openUnassignedCount + " OPEN+unassigned PL(s); "
                        + openWithEligibleCount + " had any eligible picker.");
        notes.put("hasEligiblePickersButNoneAvailable",
                "TRUE iff any OPEN+unassigned PL has eligiblePickerCount>0 AND eligiblePickerStatus.available==0. "
                        + "Considered " + openUnassignedCount + " OPEN+unassigned PL(s).");
        notes.put("hasReplenishmentDeficit",
                "TRUE iff any DemandShortage has deficit>0. Considered "
                        + shortageCount + " shortage row(s); "
                        + deficitRows + " had deficit>0.");
        notes.put("hasMultipleSourceAreas",
                "TRUE iff distinctSourceAreas has size>1 (suppressed-vacuous on empty). distinctSourceAreas size="
                        + distinctSourceAreas.size() + ".");
        notes.put("isInBatchOrWave",
                "TRUE iff batchConsolidation.inBatch (i.e. pp.batch_id or pp.wave_number is non-blank). batchConsolidation.inBatch="
                        + inBatch + ".");
        notes.put("packingOrderMissing",
                "TRUE iff picking_status is post-pick (REACHED_TO_QC / PICKING_COMPLETE / QC_COMPLETE) "
                        + "AND no active packing_order row exists. picking_status=" + ps
                        + ", packingOrder.present=" + packingOrderPresent
                        + ". Pattern C downstream confirmation.");
        notes.put("hasOpenTaskRequest",
                "TRUE iff any non-CLOSED picking_task_request exists for this PP. Considered "
                        + (movementTaskRequests == null ? 0 : movementTaskRequests.size())
                        + " non-CLOSED request row(s).");
        notes.put("hasOpenMovementTask",
                "TRUE iff any non-CLOSED picking_task exists for this PP. Considered "
                        + (movementTasks == null ? 0 : movementTasks.size())
                        + " non-CLOSED task row(s). Pattern A signature when this is FALSE but "
                        + "hasOpenTaskRequest is TRUE — request created but never spawned tasks.");
        notes.put("hasFailedMovementTask",
                "TRUE iff any picking_task has retryCount>0 OR failureReason populated. Considered "
                        + (movementTasks == null ? 0 : movementTasks.size())
                        + " non-CLOSED task row(s).");

        // Build active-only signalKinds: only signals that are TRUE get an entry.
        Map<String, SignalKind> kinds = new LinkedHashMap<>();
        addIfTrue(kinds, "isCanceled", isCanceled);
        addIfTrue(kinds, "isDeprioritized", isDeprioritized);
        addIfTrue(kinds, "isRejected", isRejected);
        addIfTrue(kinds, "isPriorityBoosted", isPriorityBoosted);
        addIfTrue(kinds, "isAlreadyAssigned", isAssigned);
        addIfTrue(kinds, "isInProblemSolve", "PROBLEM_SOLVE".equals(ps));
        addIfTrue(kinds, "isStorageNotAvailable", "STORAGE_NOT_AVAILABLE".equals(ps));
        addIfTrue(kinds, "isAwaitingPutaway", "WAITING_FOR_PUTAWAY".equals(ps));
        addIfTrue(kinds, "isPriorityCalPending", "PRIORITY_CAL_PENDING".equals(ps));
        addIfTrue(kinds, "isPartialPackage", "PARTIAL_PACKAGE".equals(ps));
        addIfTrue(kinds, "isReadyForManualPicking", "READY_FOR_MANUAL_PICKING".equals(ps));
        addIfTrue(kinds, "isReachedToQc", "REACHED_TO_QC".equals(ps));
        addIfTrue(kinds, "isPickingComplete", "PICKING_COMPLETE".equals(ps));
        addIfTrue(kinds, "isWeightCapturePending",  statusInt == 1);
        addIfTrue(kinds, "isWeightCaptureDone",     statusInt == 2);
        addIfTrue(kinds, "isGinComplete",           statusInt == 3);
        addIfTrue(kinds, "isPartialGinComplete",    statusInt == 8);
        addIfTrue(kinds, "isAwbPending",            statusInt == 4);
        addIfTrue(kinds, "isAwbReceived",           statusInt == 5);
        addIfTrue(kinds, "isShipmentBookingFailed", statusInt == 6);
        addIfTrue(kinds, "isAddedToShipmentRequest", statusInt == 7);
        addIfTrue(kinds, "isWaitingForShipmentRequest", statusInt == 9);
        addIfTrue(kinds, "isCancellationPending",   statusInt == 10);
        addIfTrue(kinds, "hasAnyOpenPickList", hasOpenPl);
        addIfTrue(kinds, "hasNoEligiblePickerForAnyOpenPickList", hasNoEligibleForAnyOpen);
        addIfTrue(kinds, "hasEligiblePickersButNoneAvailable", anyOpenHasEligibleNoneAvailable);
        addIfTrue(kinds, "hasReplenishmentDeficit", hasReplenishmentDeficit);
        addIfTrue(kinds, "hasMultipleSourceAreas", hasMultipleSourceAreas);
        addIfTrue(kinds, "isInBatchOrWave", inBatch);
        addIfTrue(kinds, "packingOrderMissing", packingOrderMissing);
        addIfTrue(kinds, "hasOpenTaskRequest", hasOpenTaskRequest);
        addIfTrue(kinds, "hasOpenMovementTask", hasOpenMovementTask);
        addIfTrue(kinds, "hasFailedMovementTask", hasFailedMovementTask);

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
                hasMultipleSourceAreas,
                inBatch,
                packingOrderMissing,
                hasOpenTaskRequest,
                hasOpenMovementTask,
                hasFailedMovementTask,
                notes,
                kinds);
    }

    /** Add the signal name → its known kind to the map iff the signal is TRUE. */
    private static void addIfTrue(Map<String, SignalKind> kinds, String name, boolean value) {
        if (!value) {
            return;
        }
        SignalKind kind = SIGNAL_KINDS.get(name);
        if (kind != null) {
            kinds.put(name, kind);
        }
    }

    // ─── status interpretations (replaces applicableHints) ───────────────────

    static StatusInterpretation buildPickingStatusInterpretation(String pickingStatus) {
        if (pickingStatus == null) {
            return null;
        }
        StatusInfo info = PICKING_STATUS_INFO.get(pickingStatus);
        if (info == null) {
            return new StatusInterpretation(pickingStatus, LifecycleStage.OTHER,
                    "Unknown picking_status value — check source for additions.",
                    PICKING_STATUS_SOURCE_REF);
        }
        return new StatusInterpretation(pickingStatus, info.stage(), info.meaning(),
                PICKING_STATUS_SOURCE_REF);
    }

    static StatusInterpretation buildPpStatusInterpretation(String ppStatusLabel) {
        if (ppStatusLabel == null) {
            return null;
        }
        StatusInfo info = PP_STATUS_INFO.get(ppStatusLabel);
        if (info == null) {
            return new StatusInterpretation(ppStatusLabel, LifecycleStage.OTHER,
                    "Unknown pp.status value — check source for additions.",
                    PP_STATUS_SOURCE_REF);
        }
        return new StatusInterpretation(ppStatusLabel, info.stage(), info.meaning(),
                PP_STATUS_SOURCE_REF);
    }
}
