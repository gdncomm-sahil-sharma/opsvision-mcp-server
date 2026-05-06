package com.gdn.opsvision.mcp.tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerStatusBreakdown;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.AvailableWork;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.InterpretiveHints;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.PickListSummary;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.PickerState;
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

    private static final Map<String, String> PICKER_STATUS_MEANING = Map.ofEntries(
            Map.entry("AVAILABLE", "Picker is logged in and ready for assignments. If queue is empty, the issue is upstream (no work in their zones, or all work already taken)."),
            Map.entry("BUSY", "Picker is actively working a pick_list. Empty queue is irrelevant — they're already engaged."),
            Map.entry("OFFLINE", "Picker is not logged in. They won't receive new assignments until they log in. (last_login_time may be stale.)"),
            Map.entry("BREAK_INITIATED", "Picker is on break. Queue won't deliver work until break ends."),
            Map.entry("BREAK_REJECT_PICKLIST", "Picker rejected a pick_list and entered break state. Queue paused for them."),
            Map.entry("OCCUPIED", "Picker is occupied by a non-pick activity. Queue won't deliver work."));

    private final PickerAccessRepository pickerAccessRepo;

    public DiagnosePickerQueueTool(PickerAccessRepository pickerAccessRepo) {
        this.pickerAccessRepo = pickerAccessRepo;
    }

    @Tool(description = """
            Picker-rooted diagnostic: "why is THIS picker's queue empty?" Single picker in, \
            structured evidence pack out (state, zone-group memberships with the zones in \
            each, available work across those zones, per-zone activity counts, sibling \
            pickers competing for the same zone groups, pre-computed boolean signals, and \
            a hints block).

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

        // §6 derived booleans
        PickerWorkflowSignals signals = computeSignals(p, memberships, allZoneIds, availableWork, siblings);

        // §7 hints
        InterpretiveHints hints = buildHints(p.status(), signals);

        return new PickerQueueDiagnosisEvidence(
                p.code(),
                /*found=*/true,
                state,
                memberships,
                availableWork,
                zoneBreakdown,
                siblings,
                signals,
                hints);
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

    private static PickerWorkflowSignals computeSignals(
            PickerStateRow p,
            List<ZoneGroupMembership> memberships,
            Set<Long> allZoneIds,
            AvailableWork availableWork,
            SiblingPickerSummary siblings) {
        boolean hasNoMemberships = memberships.isEmpty();
        boolean hasGroupsButNoZones = !memberships.isEmpty() && allZoneIds.isEmpty();
        boolean noOpen = availableWork.openUnassignedPickListsAcrossPickerZones() == 0;
        boolean openExist = availableWork.openUnassignedPickListsAcrossPickerZones() > 0;
        String s = p.status() == null ? "" : p.status();
        boolean isOnBreak = "BREAK_INITIATED".equals(s) || "BREAK_REJECT_PICKLIST".equals(s);
        int siblingCount = siblings.countInSameZoneGroups();
        PickerStatusBreakdown sb = siblings.statusBreakdown();
        boolean siblingsAllNonAvail = siblingCount > 0 && sb.available() == 0;
        return new PickerWorkflowSignals(
                /*pickerExists=*/true,
                !p.active(),
                p.deleted(),
                hasNoMemberships,
                hasGroupsButNoZones,
                noOpen,
                openExist,
                "AVAILABLE".equals(s),
                "BUSY".equals(s),
                "OFFLINE".equals(s),
                isOnBreak,
                siblingCount == 0,
                siblingsAllNonAvail);
    }

    private static InterpretiveHints buildHints(String status, PickerWorkflowSignals signals) {
        String meaning = status == null ? null
                : PICKER_STATUS_MEANING.getOrDefault(status,
                        "Unknown picker status enum value; check PickerStatus.java for additions.");
        List<String> applicable = new ArrayList<>();
        if (signals.isInactive()) {
            applicable.add("Picker has active=false — disabled in the system. They won't be issued any pick_lists. Check why ops disabled them.");
        }
        if (signals.isDeleted()) {
            applicable.add("Picker has deleted=true — soft-deleted. They will never receive work.");
        }
        if (signals.hasNoZoneGroupMemberships()) {
            applicable.add("Picker is not in any zone_group. They have access to NO zones, so their queue will always be empty until ops adds them to a zone_group via the picker_zone_group table.");
        } else if (signals.hasZoneGroupsButNoZones()) {
            applicable.add("Picker is in zone_group(s), but those groups contain no active zones. Effectively the same as having no memberships — queue will be empty.");
        }
        if (signals.noOpenUnassignedPickListsInPickerZones() && !signals.hasNoZoneGroupMemberships()) {
            applicable.add("Picker has zone access, but currently 0 OPEN unassigned pick_lists in any of their zones. Either no work has reached those zones yet (look upstream — replenishment / batch) or other pickers already claimed everything.");
        }
        if (signals.openPickListsExistInPickerZones()) {
            applicable.add("Open unassigned pick_lists DO exist in the picker's zones (see availableWork.openUnassignedPickListsAcrossPickerZones). If this picker isn't getting them, check pickerStatus (must be AVAILABLE), and check sibling pickers — high BUSY count among siblings means competition is fierce.");
        }
        if (signals.pickerStatusOffline()) {
            applicable.add("pickerStatus=OFFLINE. They aren't logged in — no assignments will go to them until they log into the picker app.");
        }
        if (signals.pickerStatusBusy()) {
            applicable.add("pickerStatus=BUSY — they're already on a pick_list. Empty queue from their POV is normal until they finish.");
        }
        if (signals.pickerStatusOnBreak()) {
            applicable.add("Picker is on break (BREAK_INITIATED / BREAK_REJECT_PICKLIST). Queue is paused for them.");
        }
        if (signals.isOnlyPickerForOwnZoneGroups()) {
            applicable.add("This picker is the ONLY one configured for their zone group(s). If their queue is empty, no other picker can pick those PPs either — work just isn't there.");
        }
        if (signals.siblingPickersAllNonAvailable()) {
            applicable.add("Sibling pickers exist for the same zone groups but none are AVAILABLE — they're all BUSY/OFFLINE/on break. This picker may be competing for work that's all already in flight.");
        }
        return new InterpretiveHints(meaning, applicable);
    }

    private static PickerQueueDiagnosisEvidence notFound(String code) {
        return new PickerQueueDiagnosisEvidence(
                code, /*found=*/false, null, List.of(), null, List.of(), null,
                new PickerWorkflowSignals(false, false, false, false, false, false, false, false, false, false, false, false, false),
                null);
    }

    @SuppressWarnings("unused")
    private static <K, V> LinkedHashMap<K, V> linkedMap() {
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unused")
    private static <T> HashSet<T> hashSet() {
        return new HashSet<>();
    }
}
