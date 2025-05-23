package org.example;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

public class SearchController {

    @Controller("/search")
    @Requires(missingProperty = "execute-on")
    static class NonBlocking {
        @Post("find")
        public HttpResponse<?> find(@Body Input input) {
            return SearchController.find(input.haystack, input.needle);
        }
    }

    @Controller("/search")
    @Requires(property = "execute-on", value = "blocking")
    static class Blocking {
        @Post("find")
        @ExecuteOn(TaskExecutors.BLOCKING)
        public HttpResponse<?> find(@Body Input input) {
            return SearchController.find(input.haystack, input.needle);
        }
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
