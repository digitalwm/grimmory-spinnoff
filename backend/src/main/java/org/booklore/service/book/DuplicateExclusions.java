package org.booklore.service.book;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.function.ToLongFunction;

/** Pair decisions also constrain connected groups: indirect matches cannot reunite an excluded pair. */
public final class DuplicateExclusions {
    private DuplicateExclusions() {}
    public record Pair(long low,long high) {
        public static Pair of(long a,long b){return new Pair(Math.min(a,b),Math.max(a,b));}
    }
    public static Set<Pair> load(JdbcTemplate jdbc,long library) {
        return new HashSet<>(jdbc.query("SELECT book_low_id,book_high_id FROM catalogue_duplicate_exclusion WHERE library_id=?",
                (rs,n)->new Pair(rs.getLong(1),rs.getLong(2)),library));
    }
    public static <T> List<List<T>> split(List<T> records,ToLongFunction<T> id,Set<Pair> excluded) {
        if(excluded==null||excluded.isEmpty())return List.of(records);
        Map<Long,List<T>> byId=new TreeMap<>();
        for(T row:records)byId.computeIfAbsent(id.applyAsLong(row),k->new ArrayList<>()).add(row);
        List<List<T>> parts=new ArrayList<>();
        for(var entry:byId.entrySet()) {
            var part=parts.stream().filter(group->group.stream().noneMatch(row->excluded.contains(Pair.of(entry.getKey(),id.applyAsLong(row)))))
                    .findFirst().orElseGet(()->{var group=new ArrayList<T>();parts.add(group);return group;});
            part.addAll(entry.getValue());
        }
        return parts;
    }
}
