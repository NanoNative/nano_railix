package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.step.StepCatalog;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Derived functional changes. Examples, presentation and runtime observations are not graph edits. */
final class ProjectComparison {
    private ProjectComparison() { }

    static RailixValue.ObjectValue between(final String baseline, final String draft) {
        final var old = ((RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(baseline)).value()).values();
        final var current = ((RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(draft)).value()).values();
        final Map<String, RailixValue.ObjectValue> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        for (final var side : List.of(Map.entry(old, before), Map.entry(current, after))) {
            final Map<String, List<RailixValue>> outgoing = new LinkedHashMap<>();
            for (final var link : ((RailixValue.ArrayValue) side.getKey().get("links")).values()) {
                final String from = ((RailixValue.StringValue) ((RailixValue.ObjectValue) link).values().get("from")).value();
                outgoing.computeIfAbsent(from.substring(0, from.indexOf('.')), ignored -> new java.util.ArrayList<>()).add(link);
            }
            for (final var value : ((RailixValue.ArrayValue) side.getKey().get("nodes")).values()) {
                final var node = (RailixValue.ObjectValue) value;
                final var fields = new LinkedHashMap<>(node.values());
                fields.remove("examples");
                final String id = ((RailixValue.StringValue) fields.get("id")).value();
                if (id.equals("app") && side.getKey().containsKey("configuration"))
                    fields.put("configuration", side.getKey().get("configuration"));
                fields.put("connections", RailixValue.array(outgoing.getOrDefault(id, List.of())));
                side.getValue().put(id, RailixValue.object(fields));
            }
        }
        final var ids = new LinkedHashSet<>(before.keySet());
        ids.addAll(after.keySet());
        final var changes = new LinkedHashMap<String, RailixValue>();
        for (final String id : ids) {
            if (java.util.Objects.equals(before.get(id), after.get(id))) continue;
            final var change = new LinkedHashMap<String, RailixValue>();
            change.put("state", RailixValue.string(!before.containsKey(id) ? "added" : !after.containsKey(id) ? "removed" : "changed"));
            if (before.containsKey(id)) change.put("before", before.get(id));
            if (after.containsKey(id)) change.put("after", after.get(id));
            changes.put(id, RailixValue.object(change));
        }
        return RailixValue.object(changes);
    }

    /** Restores the connected deleted section when surviving boundaries still match its deletion bypass. */
    static RailixValue.ObjectValue restore(final String baseline, final RailixValue.ObjectValue draft, final String id,
                                          final StepCatalog catalog) {
        final var old=(RailixValue.ObjectValue)((RailixJson.Parsed)RailixJson.parse(baseline)).value();
        final Map<String,RailixValue> original=new LinkedHashMap<>(),current=new LinkedHashMap<>();
        for(final var value:CreatorChanges.array(old,"nodes")) original.put(CreatorChanges.text(CreatorChanges.object(value),"id"),value);
        for(final var value:CreatorChanges.array(draft,"nodes")) current.put(CreatorChanges.text(CreatorChanges.object(value),"id"),value);
        if(!original.containsKey(id) || current.containsKey(id)) throw new IllegalArgumentException("This Step is not removed from the draft.");
        final Map<String,List<RailixValue>> oldPorts=new LinkedHashMap<>(),newPorts=new LinkedHashMap<>();
        final Map<String,List<String>> adjacent=new LinkedHashMap<>();
        for(final var side:List.of(Map.entry(old,oldPorts),Map.entry(draft,newPorts))) for(final var edge:CreatorChanges.array(side.getKey(),"links")) {
            final var fields=CreatorChanges.object(edge);
            final String from=CreatorChanges.text(fields,"from"),owner=from.substring(0,from.indexOf('.')),to=CreatorChanges.text(fields,"to");
            side.getValue().computeIfAbsent(from,ignored->new java.util.ArrayList<>()).add(edge);
            if(side.getKey()==old && !to.equals("end")) {
                adjacent.computeIfAbsent(owner,ignored->new java.util.ArrayList<>()).add(to);
                adjacent.computeIfAbsent(to,ignored->new java.util.ArrayList<>()).add(owner);
            }
        }
        final var restored=new LinkedHashMap<String,RailixValue>();
        final var pending=new java.util.ArrayDeque<String>();pending.add(id);
        while(!pending.isEmpty()) {
            final String next=pending.removeFirst();
            if(current.containsKey(next) || restored.putIfAbsent(next,original.get(next))!=null) continue;
            pending.addAll(adjacent.getOrDefault(next,List.of()));
        }
        final Map<String,String> bypass=new LinkedHashMap<>();
        final var needed=new LinkedHashSet<String>();
        for(final var port:oldPorts.entrySet()) {
            final String owner=port.getKey().substring(0,port.getKey().indexOf('.'));
            if(current.containsKey(owner) && port.getValue().stream().anyMatch(link->restored.containsKey(CreatorChanges.text(CreatorChanges.object(link),"to"))))
                for(final var link:port.getValue()) {
                    final String target=CreatorChanges.text(CreatorChanges.object(link),"to");
                    if(!target.equals("end") && !current.containsKey(target))needed.add(target);
                }
        }
        for(final String removed:needed) {
            final var path=new LinkedHashSet<String>();String target=removed;
            while(!target.equals("end") && !current.containsKey(target) && !bypass.containsKey(target)) {
                if(!path.add(target)) throw new IllegalArgumentException("Deleted routes contain a cycle.");
                final var node=CreatorChanges.object(original.get(target));
                final String use=CreatorChanges.text(node,"use");
                final String primary=catalog.find(use).orElseThrow(()->new IllegalArgumentException("Install the Step definition for "+use+" before restoring.")).primaryOutcome();
                final var exits=oldPorts.getOrDefault(target+"."+primary,List.of());
                if(exits.size()>1) throw new IllegalArgumentException("Reconnect the deleted section's primary output before restoring.");
                target=exits.isEmpty()?"end":CreatorChanges.text(CreatorChanges.object(exits.getFirst()),"to");
            }
            final String destination=bypass.getOrDefault(target,target);
            path.forEach(member->bypass.put(member,destination));
        }
        final var links=new LinkedHashMap<String,RailixValue>();
        for(final var port:oldPorts.entrySet()) {
            final String owner=port.getKey().substring(0,port.getKey().indexOf('.'));
            if(restored.containsKey(owner)) {links.put(port.getKey(),RailixValue.array(port.getValue()));continue;}
            if(!current.containsKey(owner) || port.getValue().stream().noneMatch(link->restored.containsKey(CreatorChanges.text(CreatorChanges.object(link),"to")))) continue;
            final var expected=port.getValue().stream().map(link->{final String to=CreatorChanges.text(CreatorChanges.object(link),"to");
                return bypass.containsKey(to)?CreatorChanges.with((RailixValue.ObjectValue)link,"to",RailixValue.string(bypass.get(to))):link;}).distinct().toList();
            if(!new LinkedHashSet<>(newPorts.getOrDefault(port.getKey(),List.of())).equals(new LinkedHashSet<>(expected)))
                throw new IllegalArgumentException("A neighboring connection changed. Undo that connection or insert the Step explicitly.");
            links.put(port.getKey(),RailixValue.array(port.getValue()));
        }
        return RailixValue.object(Map.of("kind",RailixValue.string("patch"),"node",RailixValue.string(id),
                "project",RailixValue.object(Map.of("nodes",RailixValue.object(restored),"links",RailixValue.object(links)))));
    }
}
