package org.booklore.service.book;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DuplicateExclusionsTest {
 @Test void excludedPairsCannotReuniteThroughAnIndirectCandidate(){
  var parts=DuplicateExclusions.split(List.of(1L,2L,3L),Long::longValue,Set.of(DuplicateExclusions.Pair.of(1,2)));
  assertTrue(parts.stream().noneMatch(part->part.contains(1L)&&part.contains(2L)));
  assertEquals(3,parts.stream().mapToInt(List::size).sum());
 }
 @Test void completeUncouplingMakesEveryRecordSeparate(){
  var parts=DuplicateExclusions.split(List.of(1L,2L,3L),Long::longValue,Set.of(DuplicateExclusions.Pair.of(1,2),DuplicateExclusions.Pair.of(1,3),DuplicateExclusions.Pair.of(2,3)));
  assertTrue(parts.stream().allMatch(part->part.size()==1));
 }
}
