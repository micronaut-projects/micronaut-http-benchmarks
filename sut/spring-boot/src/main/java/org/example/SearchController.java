package org.example;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SearchController {
    @PostMapping("/search/find")
    public ResponseEntity<?> find(@RequestBody Input input) {
        return find(input.haystack, input.needle);
    }

    private static ResponseEntity<?> find(List<String> haystack, String needle) {
        for (int listIndex = 0; listIndex < haystack.size(); listIndex++) {
            String s = haystack.get(listIndex);
            int stringIndex = s.indexOf(needle);
            if (stringIndex != -1) {
                return ResponseEntity.ok(new Result(listIndex, stringIndex));
            }
        }
        return ResponseEntity.notFound().build();
    }

    record Input(List<String> haystack, String needle) {}

    record Result(int listIndex, int stringIndex) {}
}
