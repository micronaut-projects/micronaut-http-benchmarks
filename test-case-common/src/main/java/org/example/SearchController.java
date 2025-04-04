package org.example;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Controller("/search")
public class SearchController {
    @Post("find")
    public HttpResponse<?> find(@Body Input input) {
        return find(input.haystack, input.needle);
    }

    private static MutableHttpResponse<?> find(List<String> haystack, String needle) {
        for (int listIndex = 0; listIndex < haystack.size(); listIndex++) {
            String s = haystack.get(listIndex);
            int stringIndex = s.indexOf(needle);
            if (stringIndex != -1) {
                return HttpResponse.ok(new Result(listIndex, stringIndex));
            }
        }
        return HttpResponse.notFound();
    }

    @Introspected
    @Serdeable
    record Input(List<String> haystack, String needle) {}

    @Introspected
    @Serdeable
    record Result(int listIndex, int stringIndex) {}
}
