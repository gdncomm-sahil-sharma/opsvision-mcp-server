package com.gdn.opsvision.mcp.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerStatusBreakdown;
import com.gdn.opsvision.mcp.dto.PickerOperationalStage;
import com.gdn.opsvision.mcp.dto.SignalKind;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.AvailableWork;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.PickListSummary;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.PickerState;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.PickerStatusInterpretation;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.PickerWorkflowSignals;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.SiblingPickerSummary;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.ZoneActivity;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.ZoneGroupMembership;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.ZoneRef;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository.OpenPickListRow;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository.PickerRow;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository.PickerStateRow;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository.ZoneActivityRow;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository.ZoneGroupRow;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository.ZoneRow;

/**
 * Picker-rooted twin of {@code diagnosePickPackage}. Answers "why is THIS picker's queue
 * empty?" — covers scenario 1 from the user-not-aware-workflow set ("picker has no PP in
 * which they have access"). Returns FACTS, not VERDICTS.
 */
@Service
public class DiagnosePickerQueueTool {

    private static final String PICKER_STATUS_SOURCE_REF =
            "stockholm/InventoryUtilities/src/main/java/com/gdn/inventory/type/PickerStatus.java";

    /** Signal-name → {@link SignalKind} for this tool. Tested for full coverage. */
    static final Map<String, SignalKind> SIGNAL_KINDS = Map.<String, SignalKind>ofEntries(
            // Operator-controlled overrides on the picker.
            Map.entry("isInactive", SignalKind.OPERATOR_OVERRIDE),
            Map.entry("isDeleted",  SignalKind.OPERATOR_OVERRIDE),
            // Internal blockers — picker config gaps.
            Map.entry("hasNoZoneGroupMemberships",  SignalKind.BLOCKER_INTERNAL),
            Map.entry("hasZoneGroupsButNoZones",    SignalKind.BLOCKER_INTERNAL),
            // Stage indicators — picker's current operational state.
            Map.entry("pickerExists",          SignalKind.STAGE),
            Map.entry("pickerStatusAvailable", SignalKind.STAGE),
            Map.entry("pickerStatusBusy",      SignalKind.STAGE),
            Map.entry("pickerStatusOffline",   SignalKind.STAGE),
            Map.entry("pickerStatusOnBreak",   SignalKind.STAGE),
            // Context — queue / peer shape; supplementary.
            Map.entry("noOpenUnassignedPickListsInPickerZones", SignalKind.CONTEXT),
            Map.entry("openPickListsExistInPickerZones",        SignalKind.CONTEXT),
            Map.entry("isOnlyPickerForOwnZoneGroups",           SignalKind.CONTEXT),
            Map.entry("siblingPickersAllNonAvailable",          SignalKind.CONTEXT));

    /** Internal: stage + one-line meaning per picker.status value. */
    private record PickerStatusInfo(PickerOperationalStage stage, String meaning) {
    }

    /** {@code PickerStatus} (6 values) → operational stage + short meaning. */
    private static final Map<String, PickerStatusInfo> PICKER_STATUS_INFO = Map.ofEntries(
            Map.entry("AVAILABLE", new PickerStatusInfo(PickerOperationalStage.READY,
                    "Logged in and ready for assignments.")),
            Map.entry("BUSY", new PickerStatusInfo(PickerOperationalStage.ENGAGED,
                    "Already working a pick_list.")),
            Map.entry("OFFLINE", new PickerStatusInfo(PickerOperationalStage.NOT_LOGGED_IN,
                    "Not logged in; no assignments will be issued.")),
            Map.entry("BREAK_INITIATED", new PickerStatusInfo(PickerOperationalStage.ON_BREAK,
                    "On break; queue paused.")),
            Map.entry("BREAK_REJECT_PICKLIST", new PickerStatusInfo(PickerOperationalStage.ON_BREAK,
                    "Rejected a pick_list and entered break; queue paused.")),
            Map.entry("OCCUPIED", new PickerStatusInfo(PickerOperationalStage.ENGAGED_NON_PICKING,
                    "Engaged on a non-pick activity.")));

    private final PickerAccessRepository pickerAccessRepo;

    public DiagnosePickerQueueTool(PickerAccessRepository pickerAccessRepo) {
        this.pickerAccessRepo = pickerAccessRepo;
    }

    @Tool(description = """
            Picker-rooted diagnostic: "why is THIS picker's queue empty?" Single picker in, \
            structured evidence pack out (state, zone-group memberships with the zones in \
            each, available work across those zones, per-zone activity counts, sibling \
            pickers competing for the same zone groups, pre-computed boolean signals, and \
            a structured PickerStatusInterpretation with operational stage + one-line \
            meaning + source-file ref).

            Use as the entry point when a picker reports "I'm logged in but I don't see any \
            pick packages" or when ops is investigating a specific picker's queue. Pairs \
            with diagnosePickPackage for the inverse view ("why isn't THIS PP being picked").

            ID format: picker.code (e.g. 'PIC-0000023391') or numeric picker.id.

            Key signals the agent scans:
              - hasNoZoneGroupMemberships: picker is configured but not in any zone_group → \
                they'll never see anything until ops adds them.
              - hasZoneGroupsButNoZones: edge case where the zone_groups exist but contain \
                no active zones.
              - noOpenUnassignedPickListsInPickerZones: picker has access but the queue is \
                genuinely empty in their zones — work just isn't there right now.
              - openPickListsExistInPickerZones: queue has work; if picker isn't getting any, \
                check pickerStatus / sibling competition.
              - pickerStatusOffline / Busy / OnBreak: picker isn't in a state that accepts \
                new assignments.
              - isOnlyPickerForOwnZoneGroups: picker has unique access to their zones — if \
                their queue is empty, no one else can pick those PPs either.
              - siblingPickersAllNonAvailable: peers exist but none are AVAILABLE.

            zoneBreakdown gives per-zone activity counts. The agent can spot 'all my zones \
            have 0 open pick_lists' vs 'one zone has 50 OPEN, the others 0'.

            availableWork.openPickListsSample is the queue HEAD (top 20) ordered by the \
            production picker-queue ordering: ppl.precedence asc nulls last → priority desc \
            → sub_level_priority desc → created_date asc. Each entry includes a sample \
            pickPackageCode for chain hops into diagnosePickPackage.

            'pickerStatusInterpretation' carries operationalStage (READY / ENGAGED / \
            NOT_LOGGED_IN / ON_BREAK / ENGAGED_NON_PICKING / OTHER) for triage, plus a \
            one-line meaning + source-file ref.

            'signals.derivationNotes' shows the rule + inputs each derived boolean was \
            computed under. Use it to sanity-check a signal before quoting it.

            Vacuous-true suppression: when picker has no zone access (hasNoZoneGroupMemberships \
            OR hasZoneGroupsButNoZones is true), secondary signals like \
            noOpenUnassignedPickListsInPickerZones, openPickListsExistInPickerZones, and \
            isOnlyPickerForOwnZoneGroups are forced to false. They would otherwise be \
            vacuously true and mislead the agent into reading e.g. 'they're the only picker' \
            when the real problem is 'no zones configured.'

            Returns FACTS, not VERDICTS. Not-found returns found=false with all sections \
            null.
            """)
    public PickerQueueDiagnosisEvidence diagnosePickerQueue(
            @ToolParam(description = "Picker code (e.g. 'PIC-0000023391') or numeric picker.id") String pickerCodeOrId) {

        Optional<PickerStateRow> pickerOpt = lookupPicker(pickerCodeOrId);
        if (pickerOpt.isEmpty()) {
            return notFound(pickerCodeOrId);
        }
        PickerStateRow p = pickerOpt.get();
        PickerState state = mapState(p);

        // §2 zone group memberships + zones in each
        List<ZoneGroupRow> groupRows = pickerAccessRepo.findZoneGroupsByPicker(p.id());
        List<ZoneGroupMembership> memberships = new ArrayList<>(groupRows.size());
        Set<Long> allZoneIds = new java.util.LinkedHashSet<>();
        Set<Long> allZoneGroupIds = new java.util.LinkedHashSet<>();
        int zoneCap = pickerAccessRepo.maxZonesPerZoneGroup();
        for (ZoneGroupRow g : groupRows) {
            allZoneGroupIds.add(g.id());
            int totalCount = pickerAccessRepo.countZonesInZoneGroup(g.id());
            List<ZoneRow> zoneRows = pickerAccessRepo.findZonesInZoneGroup(g.id(), zoneCap + 1);
            boolean truncated = zoneRows.size() > zoneCap;
            int reportedCount = totalCount;
            List<ZoneRow> capped = truncated ? zoneRows.subList(0, zoneCap) : zoneRows;
            List<ZoneRef> zones = capped.stream()
                    .map(z -> new ZoneRef(z.id(), z.zoneCode(), z.zoneName()))
                    .toList();
            for (ZoneRow z : capped) {
                allZoneIds.add(z.id());
            }
            memberships.add(new ZoneGroupMembership(
                    g.id(), g.zoneGroupName(), reportedCount, truncated, zones));
        }

        // §3 available work across all picker's zones
        AvailableWork availableWork = computeAvailableWork(allZoneIds);

        // §4 per-zone activity breakdown
        List<ZoneActivity> zoneBreakdown = computeZoneBreakdown(allZoneIds);

        // §5 sibling pickers
        SiblingPickerSummary siblings = computeSiblings(allZoneGroupIds, p.id());

        // §6 derived booleans + per-derivation notes (with vacuous suppression)
        PickerWorkflowSignals signals = computeSignals(p, memberships, allZoneIds, availableWork, siblings);

        // §7 structured status interpretation (replaces previous applicableHints prose)
        PickerStatusInterpretation interp = buildPickerStatusInterpretation(p.status());

        return new PickerQueueDiagnosisEvidence(
                p.code(),
                /*found=*/true,
                state,
                memberships,
                availableWork,
                zoneBreakdown,
                siblings,
                signals,
                interp);
    }

    private Optional<PickerStateRow> lookupPicker(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            return Optional.empty();
        }
        String t = idOrCode.trim();
        if (t.chars().allMatch(Character::isDigit)) {
            return pickerAccessRepo.findPickerById(Long.parseLong(t));
        }
        return pickerAccessRepo.findPickerByCode(t);
    }

    private static PickerState mapState(PickerStateRow p) {
        return new PickerState(
                p.id(), p.code(), p.name(),
                p.warehouseId(), p.warehouseCode(),
                p.status(), p.lastLoginTime(),
                p.active(), p.deleted(), p.type());
    }

    private AvailableWork computeAvailableWork(Set<Long> zoneIds) {
        if (zoneIds.isEmpty()) {
            return new AvailableWork(0, false, List.of());
        }
        int sampleCap = pickerAccessRepo.maxOpenPickListsSample();
        List<OpenPickListRow> rows = pickerAccessRepo.findOpenPickListsInZones(
                zoneIds, sampleCap + 1);
        boolean truncated = rows.size() > sampleCap;
        List<OpenPickListRow> capped = truncated ? rows.subList(0, sampleCap) : rows;
        List<PickListSummary> sample = capped.stream()
                .map(r -> new PickListSummary(
                        r.pickListId(), r.pickListStatus(),
                        r.allottedZoneId(), r.allottedZoneCode(),
                        r.priority(), r.pickingPriorityLevelId(),
                        r.pickingPriorityPrecedence(), r.subLevelPriority(),
                        r.createdDate(), r.pickPackageCodeSample()))
                .toList();
        // Total count via per-zone breakdown query (already computed cheaply); sum here.
        int total = computeTotalOpenUnassigned(zoneIds);
        return new AvailableWork(total, truncated, sample);
    }

    private int computeTotalOpenUnassigned(Set<Long> zoneIds) {
        int total = 0;
        List<ZoneActivityRow> rows = pickerAccessRepo.countPickListActivityPerZone(zoneIds);
        for (ZoneActivityRow r : rows) {
            total += (int) r.openUnassigned();
        }
        return total;
    }

    private List<ZoneActivity> computeZoneBreakdown(Set<Long> zoneIds) {
        if (zoneIds.isEmpty()) {
            return List.of();
        }
        List<ZoneActivityRow> rows = pickerAccessRepo.countPickListActivityPerZone(zoneIds);
        List<ZoneActivity> out = new ArrayList<>(rows.size());
        for (ZoneActivityRow r : rows) {
            out.add(new ZoneActivity(
                    r.zoneId(), r.zoneCode(),
                    (int) r.openUnassigned(),
                    (int) r.openTotal(),
                    (int) r.totalAnyStatus()));
        }
        return out;
    }

    private SiblingPickerSummary computeSiblings(Set<Long> zoneGroupIds, long pickerId) {
        if (zoneGroupIds.isEmpty()) {
            return new SiblingPickerSummary(0, new PickerStatusBreakdown(0, 0, 0, 0, 0, 0, 0));
        }
        List<PickerRow> sibs = pickerAccessRepo.findSiblingPickers(zoneGroupIds, pickerId);
        return new SiblingPickerSummary(sibs.size(), breakdown(sibs));
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

    /**
     * Computes the PickerWorkflowSignals block. Package-private so unit tests can drive
     * it with hand-built fixtures (no DB).
     *
     * <p>Vacuous-true suppression: when the dominant fact "picker has no zone access"
     * (either {@code hasNoMemberships} or {@code hasGroupsButNoZones}) is true, secondary
     * derived signals about queue activity in the picker's zones, and "only picker"
     * claims, are forced to {@code false}. They would otherwise be vacuously true on
     * empty inputs and mislead readers.
     */
    static PickerWorkflowSignals computeSignals(
            PickerStateRow p,
            List<ZoneGroupMembership> memberships,
            Set<Long> allZoneIds,
            AvailableWork availableWork,
            SiblingPickerSummary siblings) {
        boolean hasNoMemberships = memberships.isEmpty();
        boolean hasGroupsButNoZones = !memberships.isEmpty() && allZoneIds.isEmpty();
        boolean hasNoZoneAccess = hasNoMemberships || hasGroupsButNoZones;

        int openCount = availableWork.openUnassignedPickListsAcrossPickerZones();
        // Vacuous-true suppression: when the picker has no zone access, claims about
        // "queue empty in their zones" or "they're the only picker" are vacuously true
        // (universal quantifier over empty set). Force false; the dominant
        // hasNoZoneGroupMemberships / hasZoneGroupsButNoZones signal carries the meaning.
        boolean noOpen = !hasNoZoneAccess && openCount == 0;
        boolean openExist = !hasNoZoneAccess && openCount > 0;

        String s = p.status() == null ? "" : p.status();
        boolean isOnBreak = "BREAK_INITIATED".equals(s) || "BREAK_REJECT_PICKLIST".equals(s);
        int siblingCount = siblings.countInSameZoneGroups();
        PickerStatusBreakdown sb = siblings.statusBreakdown();
        // isOnlyPickerForOwnZoneGroups: only meaningful when picker has working zone access.
        // Suppressed when no memberships (vacuously true — no peers possible without groups)
        // and when groups have no zones (vacuous — competition is empty either way).
        boolean isOnlyPicker = !hasNoZoneAccess && siblingCount == 0;
        boolean siblingsAllNonAvail = siblingCount > 0 && sb.available() == 0;

        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("noOpenUnassignedPickListsInPickerZones",
                "TRUE iff picker has zone access AND availableWork.openCount==0. Suppressed-vacuous when "
                        + "no zone access. picker zone access=" + (!hasNoZoneAccess) + ", openCount=" + openCount + ".");
        notes.put("openPickListsExistInPickerZones",
                "TRUE iff picker has zone access AND availableWork.openCount>0. Suppressed-vacuous when "
                        + "no zone access. picker zone access=" + (!hasNoZoneAccess) + ", openCount=" + openCount + ".");
        notes.put("isOnlyPickerForOwnZoneGroups",
                "TRUE iff picker has working zone access AND siblingCount==0. Suppressed-vacuous when "
                        + "no zone access (no memberships OR groups have no active zones). picker zone access="
                        + (!hasNoZoneAccess) + ", siblingCount=" + siblingCount + ".");
        notes.put("siblingPickersAllNonAvailable",
                "TRUE iff siblingCount>0 AND siblingStatusBreakdown.available==0. siblingCount="
                        + siblingCount + ", available=" + sb.available() + ".");

        // Build active-only signalKinds map.
        Map<String, SignalKind> kinds = new LinkedHashMap<>();
        addIfTrue(kinds, "pickerExists", true);
        addIfTrue(kinds, "isInactive", !p.active());
        addIfTrue(kinds, "isDeleted",  p.deleted());
        addIfTrue(kinds, "hasNoZoneGroupMemberships", hasNoMemberships);
        addIfTrue(kinds, "hasZoneGroupsButNoZones",   hasGroupsButNoZones);
        addIfTrue(kinds, "pickerStatusAvailable", "AVAILABLE".equals(s));
        addIfTrue(kinds, "pickerStatusBusy",      "BUSY".equals(s));
        addIfTrue(kinds, "pickerStatusOffline",   "OFFLINE".equals(s));
        addIfTrue(kinds, "pickerStatusOnBreak",   isOnBreak);
        addIfTrue(kinds, "noOpenUnassignedPickListsInPickerZones", noOpen);
        addIfTrue(kinds, "openPickListsExistInPickerZones",        openExist);
        addIfTrue(kinds, "isOnlyPickerForOwnZoneGroups",           isOnlyPicker);
        addIfTrue(kinds, "siblingPickersAllNonAvailable",          siblingsAllNonAvail);

        return new PickerWorkflowSignals(
                /*pickerExists=*/true,
                !p.active(),
                p.deleted(),
                hasNoMemberships,
                hasGroupsButNoZones,
                "AVAILABLE".equals(s),
                "BUSY".equals(s),
                "OFFLINE".equals(s),
                isOnBreak,
                noOpen,
                openExist,
                isOnlyPicker,
                siblingsAllNonAvail,
                notes,
                kinds);
    }

    private static void addIfTrue(Map<String, SignalKind> kinds, String name, boolean value) {
        if (!value) {
            return;
        }
        SignalKind kind = SIGNAL_KINDS.get(name);
        if (kind != null) {
            kinds.put(name, kind);
        }
    }

    static PickerStatusInterpretation buildPickerStatusInterpretation(String status) {
        if (status == null) {
            return null;
        }
        PickerStatusInfo info = PICKER_STATUS_INFO.get(status);
        if (info == null) {
            return new PickerStatusInterpretation(status, PickerOperationalStage.OTHER,
                    "Unknown picker status — check source for additions.",
                    PICKER_STATUS_SOURCE_REF);
        }
        return new PickerStatusInterpretation(status, info.stage(), info.meaning(),
                PICKER_STATUS_SOURCE_REF);
    }

    private static PickerQueueDiagnosisEvidence notFound(String code) {
        return new PickerQueueDiagnosisEvidence(
                code, /*found=*/false, null, List.of(), null, List.of(), null,
                new PickerWorkflowSignals(
                        /*pickerExists=*/false,
                        false, false, false, false,
                        false, false, false, false,
                        false, false, false, false,
                        Map.of(), Map.of()),
                null);
    }
}
